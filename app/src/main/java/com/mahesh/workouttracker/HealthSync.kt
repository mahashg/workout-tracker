package com.mahesh.workouttracker

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * v3.4: Health Connect sync (wish-list item 1, un-parked 2026-10-13).
 *
 * After a workout is finished, the session is written to the on-device
 * Health Connect store as one [ExerciseSessionRecord] with one
 * [ExerciseSegment] per done exercise. No Google account, OAuth or Cloud
 * project is involved — Health Connect permissions are granted on-device
 * and the data stays on the phone (other apps, e.g. Google Fit via its
 * own Health Connect sync, read it from there).
 *
 * Opt-in: the `hc_sync` pref (Settings -> Health Connect) defaults OFF.
 * Every entry point no-ops silently when the pref is off, Health Connect
 * is unavailable, or the write permission is missing — a sync failure
 * must never surface during a workout finish.
 *
 * API notes (verified with javap against the resolved connect-client
 * AAR — do not assume a different surface). The pinned version is
 * 1.1.0-alpha08: the 1.1.0 stable's AAR metadata requires compileSdk
 * 36 / AGP 8.9.1+, beyond this project's toolchain (34 / 8.5.2);
 * alpha08 is the newest release built against compileSdk 34.
 * - ExerciseSegment(startTime, endTime, segmentType, repetitions) has NO
 *   weight field. Per-exercise weight therefore rides in the session
 *   notes (heaviest logged set per exercise) — the only place this API
 *   can carry it.
 * - alpha08 has no Metadata.manualEntry(...) factory (added later); the
 *   public Metadata constructor with RECORDING_METHOD_MANUAL_ENTRY +
 *   clientRecordId "wt-session-<sessionId>" + clientRecordVersion is
 *   the equivalent, and is what makes re-syncs upsert.
 * - Per-exercise timestamps are not tracked by the app, so the session
 *   window is divided evenly across the segments in session order
 *   (approximation, also documented in the README).
 */
object HealthSync {
    private const val TAG = "HealthSync"
    const val PREF_KEY = "hc_sync"

    /** android.permission.health.WRITE_EXERCISE (HealthPermission.getWritePermission(ExerciseSessionRecord::class)). */
    const val WRITE_PERMISSION = "android.permission.health.WRITE_EXERCISE"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("workout_prefs", Context.MODE_PRIVATE).getBoolean(PREF_KEY, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences("workout_prefs", Context.MODE_PRIVATE).edit().putBoolean(PREF_KEY, on).apply()
    }

    /** HealthConnectClient.SDK_* — SDK_AVAILABLE / SDK_UNAVAILABLE / SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED. */
    fun sdkStatus(context: Context): Int = try {
        HealthConnectClient.getSdkStatus(context.applicationContext)
    } catch (e: Exception) {
        HealthConnectClient.SDK_UNAVAILABLE
    }

    suspend fun hasWritePermission(context: Context): Boolean = try {
        if (sdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) false
        else HealthConnectClient.getOrCreate(context.applicationContext)
            .permissionController.getGrantedPermissions().contains(WRITE_PERMISSION)
    } catch (e: Exception) {
        false
    }

    /** Permission check with the result delivered on a background thread (callers hop to the UI thread). */
    fun checkWritePermission(context: Context, onResult: (Boolean) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            val g = hasWritePermission(app)
            try { onResult(g) } catch (e: Exception) { /* caller gone */ }
        }
    }

    /** Fire-and-forget sync for the workout-finish call sites. */
    fun syncSessionIfEnabled(context: Context, db: DbHelper, sessionId: Long) {
        syncSession(context, db, sessionId) { /* silent by design */ }
    }

    /**
     * Gated sync of one session (pref on + available + permission granted).
     * [onResult] always runs (background thread) with true only when the
     * record was actually written — the Summary screen uses it for its
     * "Synced to Health Connect ✓" line.
     */
    fun syncSession(context: Context, db: DbHelper, sessionId: Long, onResult: (Boolean) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            var ok = false
            try {
                if (isEnabled(app) && hasWritePermission(app)) {
                    val session = db.getSession(sessionId)
                    if (session != null && session.completed) ok = writeSession(app, db, session)
                }
            } catch (e: Exception) {
                Log.w(TAG, "syncSession($sessionId) failed", e)
            }
            try { onResult(ok) } catch (e: Exception) { /* caller gone */ }
        }
    }

    /**
     * Manual backfill (Settings "Sync past workouts" / Home sync button):
     * upserts EVERY completed session. Manual by definition — it does not
     * check the hc_sync pref (the user just tapped it), but it still
     * requires availability + permission. Result: (written, totalCompleted).
     */
    fun syncAllCompleted(context: Context, db: DbHelper, onResult: (written: Int, totalCompleted: Int) -> Unit) {
        val app = context.applicationContext
        scope.launch {
            var written = 0
            var total = 0
            try {
                val completed = db.getSessions().filter { it.completed }
                total = completed.size
                if (hasWritePermission(app)) {
                    for (s in completed) {
                        try { if (writeSession(app, db, s)) written++ } catch (e: Exception) { Log.w(TAG, "backfill ${s.id} failed", e) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "syncAllCompleted failed", e)
            }
            try { onResult(written, total) } catch (e: Exception) { /* caller gone */ }
        }
    }

    /** Builds and inserts the record for one completed session. Caller has already checked permission. */
    private suspend fun writeSession(app: Context, db: DbHelper, session: SessionInfo): Boolean {
        val exs = db.getSessionExercises(session.id)
        data class Seg(val name: String, val type: String, val equipment: String, val reps: Int, val heaviest: Double?)
        val segs = mutableListOf<Seg>()
        for (ex in exs) {
            if (db.effectiveStatus(ex) != "done") continue
            val sets = db.getSets(ex.id)
            val doneSets = sets.filter { it.isDone }
            if (doneSets.isEmpty()) continue
            var reps = 0
            var heaviest: Double? = null
            for (s in doneSets) {
                reps += s.reps.filter { it.isDigit() }.toIntOrNull() ?: 0
                if (!s.isBodyweight) {
                    val w = s.weight.toDoubleOrNull()
                    if (w != null && w > 0) heaviest = maxOf(heaviest ?: 0.0, w)
                }
            }
            segs.add(Seg(ex.name, ex.type, ex.equipment, reps, heaviest))
        }
        if (segs.isEmpty()) return false

        val zone = ZoneId.systemDefault()
        val startMs = if (session.startedAt > 0) session.startedAt
            else try { LocalDate.parse(session.date).atStartOfDay(zone).toInstant().toEpochMilli() } catch (e: Exception) { System.currentTimeMillis() }
        var durSec = when {
            session.elapsedSec > 0 -> session.elapsedSec.toLong()
            session.startedAt > 0 && session.endedAt > session.startedAt -> (session.endedAt - session.startedAt) / 1000
            else -> 45L * 60L
        }
        if (durSec < segs.size) durSec = segs.size.toLong()
        val start = Instant.ofEpochMilli(startMs)
        val end = start.plusSeconds(durSec)
        val slot = durSec / segs.size

        fun buildSegments(forceGeneric: Boolean): List<ExerciseSegment> = segs.mapIndexed { i, s ->
            val segStart = start.plusSeconds(slot * i)
            val segEnd = if (i == segs.lastIndex) end else start.plusSeconds(slot * (i + 1))
            ExerciseSegment(
                startTime = segStart,
                endTime = segEnd,
                segmentType = if (forceGeneric) ExerciseSegment.EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING else segmentTypeFor(s.name, s.type, s.equipment),
                repetitions = s.reps
            )
        }

        val unit = WeekManager.unit(app)
        val parts = db.getSessionParts(session.id)
        val title = if (parts.isNotEmpty()) parts.joinToString(" + ") { BodyParts.name(it) } else session.routineName
        // Notes: "Week N · Workout Tracker" plus the per-exercise heaviest
        // set — ExerciseSegment in connect-client 1.1.0 has no weight
        // field, so this is where the weights live (see class doc).
        val weightNotes = segs.filter { it.heaviest != null }.joinToString(" · ") { s ->
            val w = s.heaviest!!
            "${s.name} ${if (w % 1.0 == 0.0) w.toInt().toString() else w.toString()} $unit"
        }
        val notes = "Week ${session.weekNumber} · Workout Tracker" + if (weightNotes.isNotEmpty()) " · $weightNotes" else ""
        val version = if (session.endedAt > 0) session.endedAt else 1L

        fun buildRecord(segments: List<ExerciseSegment>) = ExerciseSessionRecord(
            startTime = start,
            startZoneOffset = zone.rules.getOffset(start),
            endTime = end,
            endZoneOffset = zone.rules.getOffset(end),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            title = title,
            notes = notes,
            segments = segments,
            metadata = Metadata(
                clientRecordId = "wt-session-${session.id}",
                clientRecordVersion = version,
                recordingMethod = Metadata.RECORDING_METHOD_MANUAL_ENTRY
            )
        )

        val client = HealthConnectClient.getOrCreate(app)
        // First attempt: best-effort segment types. If the provider rejects
        // a segment type for a strength-training session, retry once with
        // every segment as generic weightlifting so the workout still syncs.
        try {
            client.insertRecords(listOf(buildRecord(buildSegments(false))))
            return true
        } catch (e: Exception) {
            Log.w(TAG, "insert with mapped segment types failed for session ${session.id}; retrying generic", e)
        }
        client.insertRecords(listOf(buildRecord(buildSegments(true))))
        return true
    }

    /**
     * Best-effort exercise-name -> segment type. Every constant used here
     * was verified present in the connect-client 1.1.0 AAR (javap).
     * Anything unrecognized is generic weightlifting.
     */
    private fun segmentTypeFor(name: String, type: String, equipment: String): Int {
        val n = name.lowercase()
        fun has(vararg keys: String) = keys.any { n.contains(it) }
        return when {
            type.equals("Stretch", true) -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING
            has("squat") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT
            has("deadlift", "romanian") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT
            has("bench press", "chest press", "floor press") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS
            has("hip thrust", "glute bridge") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_HIP_THRUST
            has("back extension", "hyperextension", "superman") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_BACK_EXTENSION
            has("lat pulldown", "pulldown", "pull-down") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_LAT_PULL_DOWN
            has("pull-up", "pull up", "pullup", "chin-up", "chin up", "chinup") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_PULL_UP
            has("leg press") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_PRESS
            has("leg extension") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_EXTENSION
            has("leg curl", "hamstring curl") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_CURL
            has("leg raise") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_RAISE
            has("lunge") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE
            has("plank") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_PLANK
            has("crunch") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_CRUNCH
            has("sit-up", "sit up", "situp") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_SIT_UP
            has("mountain climber") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_MOUNTAIN_CLIMBER
            has("burpee") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_BURPEE
            has("kettlebell swing", "kb swing") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_KETTLEBELL_SWING
            has("kickback") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_SINGLE_ARM_TRICEPS_EXTENSION
            has("triceps extension", "tricep extension", "overhead extension", "skull") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_DOUBLE_ARM_TRICEPS_EXTENSION
            has("curl") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL
            has("shoulder press", "overhead press", "arnold") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_SHOULDER_PRESS
            has("lateral raise", "side raise") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_LATERAL_RAISE
            has("front raise") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_FRONT_RAISE
            has("row") -> if (equipment.equals("Dumbbell", true)) ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_ROW else ExerciseSegment.EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING
            has("jumping jack") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_JUMPING_JACK
            has("stationary bike", "exercise bike", "cycling") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY
            has("incline walk", "treadmill walk", "walking") -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_WALKING
            else -> ExerciseSegment.EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING
        }
    }
}
