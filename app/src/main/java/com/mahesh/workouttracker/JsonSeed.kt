package com.mahesh.workouttracker

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONObject

/**
 * v3 data layer. Model lives in code ([BodyParts]); the workout DATA lives in
 * assets/data/<part-id>.json — one JSON object per body part with warmups,
 * exercises and stretches. On first v3 launch the JSONs seed the DB
 * (exercises.bodyPart / exercises.sourceId); afterwards the DB is the working
 * copy, so in-app add/edit (ExerciseEditActivity) keeps working per part.
 */
object JsonSeed {
    data class Entry(
        val id: String, val name: String, val equipment: String, val sets: Int,
        val targetReps: String, val targetMuscles: String, val precautions: String,
        val cues: String, val image: String, val url: String, val primary: Boolean,
        val defaultWeight: String, val variation: String, val easier: String,
        val photo: String, val type: String, // "Warmup" | "Main" | "Stretch"
        /** v3.5: pre-researched Health Connect segment type for this entry
         *  (an EXERCISE_SEGMENT_TYPE_* constant NAME from the bundled
         *  config, "" when absent). HealthSync resolves segments from this
         *  instead of guessing from the exercise name. */
        val hcType: String = "",
        /** v3.10: the entry's YouTube Short (the quick-glance reel),
         *  "" when no Short was verified for it. The exercise card's ▶
         *  plays this first and falls back to [url]; the long video
         *  stays one long-press away. Runtime-read like [hcType] —
         *  never a DB column. */
        val shortUrl: String = "",
        /** v3.8.1: home-mode markers. [home] flags a zero-equipment
         *  living-room movement (the deferred Home Workout build reads
         *  it); [counterSec] (30–60, 0 = none) marks membership in the
         *  Movie Movements deck and carries the movement's counter. */
        val home: Boolean = false,
        val counterSec: Int = 0
    )

    data class PartData(val partId: String, val warmups: List<Entry>, val exercises: List<Entry>, val stretches: List<Entry>) {
        val all: List<Entry> get() = warmups + exercises + stretches
    }

    @Volatile private var cache: List<PartData>? = null

    fun loadAll(context: Context): List<PartData> {
        cache?.let { return it }
        val out = mutableListOf<PartData>()
        for (partId in BodyParts.ids) {
            try {
                val text = context.assets.open("data/$partId.json").bufferedReader().use { it.readText() }
                val root = JSONObject(text)
                fun entries(key: String, type: String): List<Entry> {
                    val arr = root.optJSONArray(key) ?: return emptyList()
                    val list = mutableListOf<Entry>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(Entry(
                            id = o.optString("id"), name = o.optString("name"),
                            equipment = o.optString("equipment"), sets = o.optInt("sets", 3),
                            targetReps = o.optString("targetReps"), targetMuscles = o.optString("targetMuscles"),
                            precautions = o.optString("precautions"), cues = o.optString("cues"),
                            image = o.optString("image"), url = o.optString("url"),
                            primary = o.optBoolean("primary", false),
                            defaultWeight = o.optString("defaultWeight"), variation = o.optString("variation"),
                            easier = o.optString("easier"), photo = o.optString("photo"), type = type,
                            hcType = o.optString("hcType"),
                            shortUrl = o.optString("shortUrl"),
                            home = o.optBoolean("home", false), counterSec = o.optInt("counterSec", 0)
                        ))
                    }
                    return list
                }
                out.add(PartData(partId, entries("warmups", "Warmup"), entries("exercises", "Main"), entries("stretches", "Stretch")))
            } catch (e: Exception) { /* a missing/corrupt part file must never crash the app */ }
        }
        cache = out
        return out
    }

    fun entryById(context: Context, id: String): Entry? =
        if (id.isBlank()) null else loadAll(context).firstNotNullOfOrNull { pd -> pd.all.firstOrNull { it.id == id } }

    fun entryByName(context: Context, name: String): Entry? {
        val key = name.trim().lowercase()
        return loadAll(context).firstNotNullOfOrNull { pd -> pd.all.firstOrNull { it.name.trim().lowercase() == key } }
    }

    fun applyV3IfNeeded(context: Context, db: DbHelper) {
        val prefs = context.getSharedPreferences("workout_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("v3SeedApplied", false)) {
            applyV3(context, db)
            prefs.edit().putBoolean("v3SeedApplied", true).apply()
        }
        applyV36AdditionsIfNeeded(context, db, prefs)
        applyV381AdditionsIfNeeded(context, db, prefs)
        applyV310CatalogIfNeeded(context, db, prefs)
    }

    /** v3.10: one-time refresh for the enhanced catalog (318 bundled
     *  entries — the Instagram/Nerd Fitness merge + media pass). Runs
     *  the v3.8.3 [restorePreloaded] UPSERT against the CURRENT bundled
     *  JSONs: entries an existing install has never seen are inserted
     *  by sourceId, and canonical rows get their canonical fields
     *  refreshed (this is also how corrected urls land; shortUrl,
     *  hcType, home, counterSec and photo stay runtime-read from the
     *  JSON, so they are current the moment the new APK is installed).
     *  User-added rows (sourceId="") and all history are untouched,
     *  and the upsert only writes rows that actually differ — a second
     *  run would report 0 inserted / 0 refreshed. Gated by its own
     *  one-time pref; on a fresh install the v3 seed has just inserted
     *  the same content, so the upsert is a no-op there. */
    private fun applyV310CatalogIfNeeded(context: Context, db: DbHelper, prefs: SharedPreferences) {
        if (prefs.getBoolean("v310CatalogApplied", false)) return
        restorePreloaded(context, db)
        prefs.edit().putBoolean("v310CatalogApplied", true).apply()
    }

    /** v3.10: the Short for a session exercise, resolved from the
     *  bundled JSON (origin exercise's sourceId, else a name match —
     *  the same resolution [PhotoLib.assetForSessionExercise] uses).
     *  Null when the entry has no verified Short; callers fall back
     *  to the exercise's own url. */
    fun shortUrlForSessionExercise(context: Context, db: DbHelper, se: SessionExercise): String? {
        if (se.originExerciseId > 0) {
            db.getExercise(se.originExerciseId)?.let { e ->
                val entry = entryById(context, e.sourceId) ?: entryByName(context, e.name)
                entry?.shortUrl?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return entryByName(context, se.name)?.shortUrl?.takeIf { it.isNotBlank() }
    }

    /** v3.8.1: one-time additive step for the 15 Movie deck movements,
     *  same contract as the v3.6 step (its own gate pref, insert-missing
     *  by sourceId, never updates/deletes, history untouched). Note the
     *  step inserts NEW entries only — the 3 reused deck entries
     *  (Bodyweight Squat, Arm Circles, Single-Leg Calf Raise) already
     *  exist in every install's DB; their JSON-side home/counterSec
     *  fields are read from the bundled JSON at runtime (see
     *  [deckEntries]), not from the DB, so they need no DB change. */
    private fun applyV381AdditionsIfNeeded(context: Context, db: DbHelper, prefs: SharedPreferences) {
        if (prefs.getBoolean("v381CatalogApplied", false)) return
        insertMissingEntries(context, db)
        prefs.edit().putBoolean("v381CatalogApplied", true).apply()
    }

    /** The Movie Movements deck (v3.8.1): every bundled entry carrying a
     *  counter (30–60s), in catalog order (part order, then JSON order
     *  within the part). Membership lives in the JSON so it can never
     *  drift from the entries' own data. */
    fun deckEntries(context: Context): List<Pair<String, Entry>> =
        loadAll(context).flatMap { pd -> pd.all.filter { it.counterSec in 30..60 }.map { pd.partId to it } }

    /** v3.6: one-time ADDITIVE catalog step. Installs seeded before v3.6
     *  never re-run [applyV3], so catalog entries added to the JSONs later
     *  (the 8 gym machines) would never reach them. This inserts any
     *  bundled entry whose sourceId is missing from the exercises table —
     *  checked per entry, so nothing is ever duplicated or overwritten,
     *  and user-added rows (sourceId="") and all history are untouched.
     *  Gated by its own one-time pref; on a fresh install [applyV3] has
     *  just inserted everything, so this finds nothing to add. The v3
     *  catalog UI offers no delete for catalog rows, so on an upgraded
     *  install a missing sourceId can only mean "added to the bundled
     *  catalog after this install was seeded". */
    private fun applyV36AdditionsIfNeeded(context: Context, db: DbHelper, prefs: SharedPreferences) {
        if (prefs.getBoolean("v36CatalogApplied", false)) return
        insertMissingEntries(context, db)
        prefs.edit().putBoolean("v36CatalogApplied", true).apply()
    }

    /** Inserts every bundled catalog entry whose id is not already present
     *  as an exercises.sourceId. Insert-only: existing rows are never
     *  modified. Shared by the v3 seed (step 3) and the v3.6 additive step. */
    private fun insertMissingEntries(context: Context, db: DbHelper) {
        val present = db.getAllExercises().map { it.sourceId }.toSet()
        for (pd in loadAll(context)) {
            var order = 0
            for (entry in pd.all) {
                val myOrder = order++
                if (entry.id in present) continue
                db.insertExercise(Exercise(
                    0, -1, entry.name, entry.type, entry.equipment, entry.sets,
                    entry.targetReps, entry.defaultWeight, entry.variation, entry.url,
                    myOrder, entry.targetMuscles, entry.cues, entry.precautions, pd.partId, entry.id
                ))
            }
        }
    }

    /** Settings "Restore Preloaded" (v3.8.3, rewritten): an unconditional
     *  UPSERT by sourceId over EVERY bundled JSON entry. A bundled entry
     *  whose sourceId is missing from the exercises table is inserted; an
     *  existing canonical row has its canonical fields refreshed from the
     *  JSON (name, type, equipment, sets, targetReps, defaultWeight,
     *  variation, url, targetMuscles, cues, precautions, bodyPart). The
     *  remaining entry fields (primary, easier, hcType, home, counterSec,
     *  photo, image) are not DB columns at all — they are read from the
     *  bundled JSON at runtime (HealthSync, deckEntries, PhotoLib), so
     *  they are always current by construction. The row's id, routineId
     *  and sortOrder are preserved (a user's Library reorder survives).
     *  Canonical DUPLICATE rows sharing one sourceId are dropped beyond
     *  the first (same safety as applyV3's dedupe; session history stores
     *  its own copies, so nothing is lost). User-added rows (sourceId="")
     *  and all session/set/history data are NEVER touched. Idempotent:
     *  a row is only written (and counted) when a field actually differs,
     *  so a second run reports 0 inserted / 0 refreshed.
     *  Returns (inserted, refreshed) so Settings can report real numbers.
     *
     *  Why the old implementation "didn't work": the Settings handler
     *  first ran the legacy Seed.restore(), which deleted EVERY exercise
     *  row — including user-added rows the dialog promised to keep — and
     *  re-seeded the retired v2 5-routine catalog whose rows carry
     *  sourceId="". restoreDefaults then deleted rows WHERE sourceId != ''
     *  (none existed at that point — a no-op) and rebuilt the catalog only
     *  through applyV3's name-matching adoption, whose contract is
     *  "nothing is renamed or re-texted — only bodyPart/sourceId are
     *  filled in". So canonical content was never actually refreshed from
     *  the current JSON: legacy-named rows kept v2-era sets/reps/weights,
     *  rows whose names didn't exactly match a JSON entry were never
     *  adopted, and the 5 retired routines were resurrected. */
    fun restorePreloaded(context: Context, db: DbHelper): Pair<Int, Int> {
        var inserted = 0
        var refreshed = 0
        val rowsBySourceId = db.getAllExercises().filter { it.sourceId.isNotBlank() }.groupBy { it.sourceId }
        for (pd in loadAll(context)) {
            var order = 0
            for (entry in pd.all) {
                val myOrder = order++
                val rows = rowsBySourceId[entry.id].orEmpty()
                if (rows.isEmpty()) {
                    db.insertExercise(Exercise(
                        0, -1, entry.name, entry.type, entry.equipment, entry.sets,
                        entry.targetReps, entry.defaultWeight, entry.variation, entry.url,
                        myOrder, entry.targetMuscles, entry.cues, entry.precautions, pd.partId, entry.id
                    ))
                    inserted++
                } else {
                    val row = rows.first()
                    for (extra in rows.drop(1)) db.deleteExercise(extra.id)
                    val updated = row.copy(
                        name = entry.name, type = entry.type, equipment = entry.equipment,
                        defaultSets = entry.sets, targetReps = entry.targetReps,
                        defaultWeight = entry.defaultWeight, variation = entry.variation,
                        youtubeUrl = entry.url, targetMuscles = entry.targetMuscles,
                        cues = entry.cues, postureCheck = entry.precautions, bodyPart = pd.partId
                    )
                    if (updated != row) { db.updateExercise(updated); refreshed++ }
                }
            }
        }
        return inserted to refreshed
    }

    private fun applyV3(context: Context, db: DbHelper) {
        val parts = loadAll(context)
        val entryPart = mutableMapOf<String, String>()
        val byNameType = mutableMapOf<Pair<String, String>, Entry>()
        val byName = mutableMapOf<String, Entry>()
        for (pd in parts) for (e in pd.all) {
            entryPart[e.id] = pd.partId
            byNameType.putIfAbsent(e.name.trim().lowercase() to e.type, e)
            byName.putIfAbsent(e.name.trim().lowercase(), e)
        }
        // 1) Backfill/adopt existing rows: match a JSON entry by name (+type),
        //    else derive the part from target muscles. Nothing is renamed or
        //    re-texted — only bodyPart/sourceId are filled in.
        for (e in db.getAllExercises()) {
            if (e.sourceId.isNotBlank() && e.bodyPart.isNotBlank()) continue
            val match = byNameType[e.name.trim().lowercase() to e.type] ?: byName[e.name.trim().lowercase()]
            var bp = e.bodyPart
            var sid = e.sourceId
            if (match != null) {
                sid = match.id
                bp = entryPart[match.id] ?: bp
            }
            if (bp.isBlank()) {
                bp = DbHelper.parseMuscles(e.targetMuscles, e.name).firstOrNull { BodyParts.byId(it) != null } ?: ""
            }
            if (bp != e.bodyPart || sid != e.sourceId) db.updateExercise(e.copy(bodyPart = bp, sourceId = sid))
        }
        // 2) Dedupe canonical rows that share a sourceId (the legacy 5-routine
        //    seed repeated warm-ups/stretches across days, e.g. Arm Circles x3).
        //    Session history stores its own copies, so this loses nothing.
        val seen = mutableSetOf<String>()
        for (e in db.getAllExercises()) {
            if (e.sourceId.isBlank()) continue
            if (!seen.add(e.sourceId)) db.deleteExercise(e.id)
        }
        // 3) Insert the JSON entries that are not in the DB yet.
        insertMissingEntries(context, db)
        // 4) Backfill session_parts for pre-v3 sessions (their parts are
        //    derived from their exercises) so Home's last-trained/done state
        //    reaches back into existing history.
        val withParts = db.sessionsWithParts()
        for (s in db.getSessions()) {
            if (s.id in withParts) continue
            val ps = db.getSessionExercises(s.id).map { db.sessionExercisePart(it) }.filter { it.isNotBlank() }.distinct()
            for (p in ps) db.insertSessionPart(s.id, p)
        }
    }
}

/** Resolves exercise photos (assets/exercise-images/<id>.jpg, from the
 *  free-exercise-db set — see research-exercise-images.md) and decodes them
 *  sampled + cached. No photo -> null -> callers fall back to BodyMap. */
object PhotoLib {
    private val bitmapCache = LruCache<String, Bitmap>(32)

    fun assetForExercise(context: Context, e: Exercise): String? {
        val entry = JsonSeed.entryById(context, e.sourceId) ?: JsonSeed.entryByName(context, e.name)
        return entry?.photo?.takeIf { it.isNotBlank() }
    }

    fun assetForSessionExercise(context: Context, db: DbHelper, se: SessionExercise): String? {
        if (se.originExerciseId > 0) {
            db.getExercise(se.originExerciseId)?.let { return assetForExercise(context, it) }
        }
        return JsonSeed.entryByName(context, se.name)?.photo?.takeIf { it.isNotBlank() }
    }

    /** v3.1 animation frame 2: "<id>-2.jpg" next to the bundled photo (the
     *  dataset entry's second movement position). Null when the entry has
     *  no second frame — callers then show the static photo. */
    fun secondBitmap(context: Context, firstAssetPath: String): Bitmap? =
        bitmap(context, firstAssetPath.removeSuffix(".jpg") + "-2.jpg")

    fun bitmap(context: Context, assetPath: String): Bitmap? {
        bitmapCache.get(assetPath)?.let { return it }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 720) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, opts) }
            if (bmp != null) bitmapCache.put(assetPath, bmp)
            bmp
        } catch (e: Exception) { null }
    }
}
