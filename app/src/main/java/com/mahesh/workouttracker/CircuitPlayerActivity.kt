package com.mahesh.workouttracker

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.ceil

/**
 * v3.11 Circuit PLAYER (approved mock screens 4–5): one motion full-screen
 * under the deck's dark band, a draining ring for timed work, rep doses in
 * the ring centre for rep work, dashed rest boxes between motions (amber)
 * and between rounds (green), and an in-place celebration at the end.
 *
 * Follows MovieDeckActivity's session/timer/haptic patterns throughout:
 * Handler loops on SystemClock deadlines, WorkoutTimer fold/ensure on the
 * lifecycle, buzz() on resolves, discard-vs-finish end paths.
 *
 * Data model: the session's rows are appended UP FRONT for every round
 * (AMRAP: one round; further rounds are appended as each round resolves).
 * Each row carries ONE set whose reps string is the step's dose
 * ("40 sec" for timed steps, the reps label otherwise); resolving a step
 * marks that set done (for "done") and stamps the row's status. Current
 * position is always the first pending row, so leaving and resuming
 * lands exactly where the player stopped. The played step order is
 * persisted per session in prefs (circuitSteps_<id>) so a detail-edited
 * order survives resume.
 */
class CircuitPlayerActivity : AppCompatActivity() {

    private enum class Phase { COUNTDOWN, WORK, REST, ROUNDREST, COMPLETE }

    private lateinit var db: DbHelper
    private var sessionId: Long = -1
    private var circuit: CircuitsData.Circuit? = null
    private var playSteps: List<CircuitsData.CStep> = emptyList()
    private var playStepIndices: List<Int> = emptyList()
    private var stepsPerRound: Int = 0
    private var rows: List<SessionExercise> = emptyList()
    private var currentRowIndex: Int = 0
    private var phase = Phase.COUNTDOWN
    private var created = false
    private var isFresh = false

    // Views (root frame persists; content column is rebuilt per phase).
    private lateinit var rootFrame: FrameLayout
    private lateinit var content: LinearLayout
    private var confetti: ConfettiView? = null
    private var bandTimerTv: TextView? = null
    private var ringView: CircuitRingView? = null
    private var ringBigTv: TextView? = null
    private var pauseBtnTv: TextView? = null
    private var restBigTv: TextView? = null
    private var photoLooper: PhotoLooper? = null

    // Timing (deck pattern: Handler loops + SystemClock deadlines).
    private val handler = Handler(Looper.getMainLooper())
    private var intervalTotalMs = 0L
    private var intervalEndsAtMs = 0L
    private var intervalRemainingMs = 0L
    private var intervalPausedByUser = false
    private var intervalRunning = false
    private var intervalActive = false
    private var elapsedRunning = false
    private var pendingNextIndex = -1

    // v3.12: rotation-proof restore scratch — populated by
    // tryRestorePlayerState() from the cp_* prefs just before
    // beginWork() re-enters renderWork(), and consumed (then reset
    // to the -1/false defaults) by renderWork()'s interval tail so
    // a restored WORK interval resumes at its saved remaining time
    // instead of restarting at the step's full interval.
    private var pendingRestoreTotal: Long = -1L
    private var pendingRestoreRemaining: Long = -1L
    private var pendingRestorePaused: Boolean = false
    private var pendingRestoreActive: Boolean = false
    private var pendingRestoreRow: Int = -1

    private val intervalTick = object : Runnable {
        override fun run() {
            if (!intervalRunning) return
            // If the tick consumed the interval (timeout dispatched), a
            // follow-on interval (rest / next step) has already posted
            // its own chain — this chain must not repost.
            val consumed = tickInterval()
            if (!consumed && intervalRunning) handler.postDelayed(this, TICK_MS)
        }
    }
    private val elapsedTick = object : Runnable {
        override fun run() {
            refreshElapsed()
            if (elapsedRunning) handler.postDelayed(this, 1000L)
        }
    }

    // ---------- session resolution ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = DbHelper(this)

        val wanted = intent.getLongExtra("sessionId", -1)
        val requestedCircuitId = intent.getStringExtra("circuitId")
        val stepIndices = intent.getIntArrayExtra("stepIndices")

        var resumed = false

        // 1) Explicit resume (Home in-progress bar): a circuit session id
        //    wins over everything else.
        if (wanted > 0) {
            val s = db.getSession(wanted)
            if (s != null && CircuitsData.isCircuit(s)) {
                if (s.completed) { finish(); return }
                val circ = CircuitsData.circuitIdOf(s)?.let { CircuitsData.circuitById(this, it) }
                if (circ == null) {
                    Toast.makeText(this, "Circuit unavailable", Toast.LENGTH_SHORT).show()
                    finish(); return
                }
                circuit = circ; sessionId = wanted; resumed = true
            }
        }

        if (!resumed) {
            val inProgress = db.inProgressSessions()
            val circuitInProgress = inProgress.firstOrNull { CircuitsData.isCircuit(it) }
            when {
                // 2) A circuit is already running: resume it when it is the
                //    requested one, refuse otherwise.
                circuitInProgress != null -> {
                    val cid = CircuitsData.circuitIdOf(circuitInProgress)
                    if (requestedCircuitId != null && cid == requestedCircuitId) {
                        val circ = cid?.let { CircuitsData.circuitById(this, it) }
                        if (circ == null) {
                            Toast.makeText(this, "Circuit unavailable", Toast.LENGTH_SHORT).show()
                            finish(); return
                        }
                        circuit = circ; sessionId = circuitInProgress.id; resumed = true
                    } else {
                        Toast.makeText(this, "Finish your current circuit first", Toast.LENGTH_LONG).show()
                        finish(); return
                    }
                }
                // 3) Any other session type in progress blocks creation.
                inProgress.isNotEmpty() -> {
                    val blocker = inProgress.first()
                    val msg = if (blocker.routineName == MovieDeck.ROUTINE_NAME)
                        "Finish your current Movements first" else "Finish your current workout first"
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    finish(); return
                }
            }
        }

        if (!resumed) {
            // 4) CREATE: rows for every round are appended BEFORE the
            //    countdown shows, so a resume never has to guess.
            if (requestedCircuitId.isNullOrBlank()) {
                Toast.makeText(this, "Circuit unavailable", Toast.LENGTH_SHORT).show()
                finish(); return
            }
            val circ = CircuitsData.circuitById(this, requestedCircuitId)
            if (circ == null || circ.steps.isEmpty()) {
                Toast.makeText(this, "Circuit unavailable", Toast.LENGTH_SHORT).show()
                finish(); return
            }
            circuit = circ
            val indices = stepIndices?.toList()?.filter { it in circ.steps.indices }
                ?.takeIf { it.isNotEmpty() } ?: circ.steps.indices.toList()
            playStepIndices = indices
            playSteps = indices.map { circ.steps[it] }
            stepsPerRound = playSteps.size

            val wm = WeekManager.reconcile(this, db)
            sessionId = db.createCircuitSession(circ.id, circ.name, wm.weekNumber, wm.weekStart)
            getSharedPreferences("workout_prefs", Context.MODE_PRIVATE).edit()
                .putInt("circuitSpr_$sessionId", playSteps.size)
                .putString("circuitSteps_$sessionId", indices.joinToString(","))
                .apply()

            val bySource = db.getAllExercises().associateBy { it.sourceId }
            if (playSteps.any { bySource[it.entryId] == null }) {
                Toast.makeText(this, "Circuit unavailable", Toast.LENGTH_SHORT).show()
                db.deleteSession(sessionId)
                finish(); return
            }
            val roundsToAppend = if (circ.rounds == -1) 1 else maxOf(circ.rounds, 1)
            repeat(roundsToAppend) {
                for (step in playSteps) {
                    db.appendCircuitExercise(sessionId, bySource.getValue(step.entryId), setRepsFor(step))
                }
            }
            isFresh = true
        } else {
            // Resume: rebuild the played step order from the prefs record
            // (falls back to the circuit's full step list).
            val c = circuit!!
            val saved = getSharedPreferences("workout_prefs", Context.MODE_PRIVATE)
                .getString("circuitSteps_$sessionId", null)
            val indices = saved?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
                ?.filter { it in c.steps.indices }?.takeIf { it.isNotEmpty() }
                ?: c.steps.indices.toList()
            playStepIndices = indices
            playSteps = indices.map { c.steps[it] }
            stepsPerRound = playSteps.size
        }

        // 5) Start (or re-attach to) the session clock.
        db.ensureSessionStarted(sessionId)
        val sess = db.getSession(sessionId)
        if (sess == null || sess.completed) { finish(); return }
        WorkoutTimer.ensureRunning(this, db, sess)

        buildChrome()
        created = true
        refreshRows()

        // v3.12: on a resume, a saved cp_* player state (written by
        // savePlayerState() before the previous instance went away —
        // e.g. on rotation) preempts the plain first-pending resume
        // below, so a WORK/REST interval resumes mid-count with its
        // pause state intact instead of restarting. The COUNTDOWN
        // phase of a FRESH session is never persisted
        // (savePlayerState() clears it), so rotation mid-countdown
        // lands on the standard resume path (beginWork at the first
        // pending row), matching the app's existing resume semantics.
        // A restore that fails validation falls through unchanged.
        val restored = tryRestorePlayerState()
        if (!restored) {
            val firstPending = rows.indexOfFirst { db.effectiveStatus(it) == "pending" }
            when {
                firstPending >= 0 -> {
                    currentRowIndex = firstPending
                    if (isFresh) showCountdown() else beginWork(currentRowIndex)
                }
                circuit!!.rounds == -1 && amrapBoxRemains() -> {
                    appendAmrapRound()
                    refreshRows()
                    val np = rows.indexOfFirst { db.effectiveStatus(it) == "pending" }
                    if (np >= 0) {
                        currentRowIndex = np
                        if (isFresh) showCountdown() else beginWork(np)
                    } else finishCircuit()
                }
                else -> finishCircuit() // fully resolved in a previous visit
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!created) return
        startElapsed()
        photoLooper?.start()
        when (phase) {
            Phase.COUNTDOWN -> { renderCountdown(); postCount() }
            Phase.WORK, Phase.REST, Phase.ROUNDREST -> {
                if (intervalActive && !intervalPausedByUser) {
                    intervalEndsAtMs = SystemClock.uptimeMillis() + intervalRemainingMs
                    startIntervalLoop()
                }
            }
            Phase.COMPLETE -> {}
        }
    }

    override fun onPause() {
        super.onPause()
        stopElapsed()
        photoLooper?.stop()
        if (intervalRunning) {
            intervalRemainingMs = (intervalEndsAtMs - SystemClock.uptimeMillis()).coerceAtLeast(0)
        }
        stopIntervalLoop()
        handler.removeCallbacks(countRunnable)
        countPosted = false
        if (sessionId > 0) {
            val s = db.getSession(sessionId)
            if (s != null && !s.completed) WorkoutTimer.fold(this, db, s)
        }
        // v3.12: persist the player state last — intervalRemainingMs
        // was already captured above before the loop stopped, so
        // savePlayerState()'s non-running branch reads the frozen
        // remainder exactly.
        savePlayerState()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        photoLooper?.stop()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back during the countdown (nothing resolved yet) discards the
        // just-created session; later, back simply leaves — the session
        // stays in progress and Home offers Resume.
        if (phase == Phase.COUNTDOWN) {
            discardSession()
            return
        }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---------- v3.12: rotation-proof player state (cp_* prefs) ----------

    /** Persists the live interval so a destroyed/recreated player
     *  (rotation is the common case) resumes where it stopped.
     *  COUNTDOWN and COMPLETE are never persisted: countdown is a
     *  fresh-session-only prelude, and a completed circuit has
     *  nothing to resume — both clear any stale cp_* record. */
    private fun savePlayerState() {
        if (sessionId <= 0) return
        if (phase == Phase.COMPLETE || phase == Phase.COUNTDOWN) {
            clearPlayerState()
            return
        }
        val rem = when {
            !intervalActive -> 0L
            intervalPausedByUser -> intervalRemainingMs
            intervalRunning -> (intervalEndsAtMs - SystemClock.uptimeMillis()).coerceAtLeast(0)
            else -> intervalRemainingMs
        }
        val phaseStr = when (phase) {
            Phase.WORK -> "WORK"
            Phase.REST -> "REST"
            Phase.ROUNDREST -> "ROUNDREST"
            else -> return
        }
        getSharedPreferences("workout_prefs", Context.MODE_PRIVATE).edit()
            .putString("cp_phase_$sessionId", phaseStr)
            .putInt("cp_row_$sessionId", currentRowIndex)
            .putInt("cp_next_$sessionId", pendingNextIndex)
            // Inactive steps (pure rep work) persist a zero total:
            // intervalTotalMs can still hold a previous timed step's
            // total, and a restored rep step must never arm a clock.
            .putLong("cp_total_$sessionId", if (intervalActive) intervalTotalMs else 0L)
            .putLong("cp_rem_$sessionId", rem)
            .putBoolean("cp_paused_$sessionId", intervalPausedByUser)
            .putBoolean("cp_active_$sessionId", intervalActive)
            .apply()
    }

    private fun clearPlayerState() {
        if (sessionId <= 0) return
        getSharedPreferences("workout_prefs", Context.MODE_PRIVATE).edit()
            .remove("cp_phase_$sessionId")
            .remove("cp_row_$sessionId")
            .remove("cp_next_$sessionId")
            .remove("cp_total_$sessionId")
            .remove("cp_rem_$sessionId")
            .remove("cp_paused_$sessionId")
            .remove("cp_active_$sessionId")
            .apply()
    }

    /** Attempts the cp_* restore described in onCreate. Returns
     *  true when the saved state preempted the normal resume flow
     *  (including when renderRest() finished the circuit on the
     *  AMRAP deadline); false leaves the caller to run the
     *  existing first-pending when-block unchanged. */
    private fun tryRestorePlayerState(): Boolean {
        if (isFresh || sessionId <= 0) return false
        val prefs = getSharedPreferences("workout_prefs", Context.MODE_PRIVATE)
        val phaseStr = prefs.getString("cp_phase_$sessionId", null) ?: return false
        val savedRow = prefs.getInt("cp_row_$sessionId", -1)
        val savedNext = prefs.getInt("cp_next_$sessionId", -1)
        val savedTotal = prefs.getLong("cp_total_$sessionId", -1L)
        val savedRem = prefs.getLong("cp_rem_$sessionId", 0L)
        val savedPaused = prefs.getBoolean("cp_paused_$sessionId", false)
        val savedActive = prefs.getBoolean("cp_active_$sessionId", false)
        return when (phaseStr) {
            "WORK" -> {
                if (savedRow !in rows.indices) return false
                pendingRestoreTotal = savedTotal
                pendingRestoreRemaining = savedRem
                pendingRestorePaused = savedPaused
                pendingRestoreActive = savedActive
                pendingRestoreRow = savedRow
                currentRowIndex = savedRow
                clearPlayerState()
                // beginWork() resets intervalPausedByUser before
                // renderWork(); renderWork()'s tail then applies
                // the pendingRestore* values over that reset.
                beginWork(savedRow)
                true
            }
            "REST", "ROUNDREST" -> restoreRest(
                isRound = phaseStr == "ROUNDREST",
                savedRow = savedRow,
                savedNext = savedNext,
                savedTotal = savedTotal,
                savedRem = savedRem,
                savedPaused = savedPaused,
                savedActive = savedActive
            )
            else -> false
        }
    }

    private fun restoreRest(
        isRound: Boolean,
        savedRow: Int,
        savedNext: Int,
        savedTotal: Long,
        savedRem: Long,
        savedPaused: Boolean,
        savedActive: Boolean
    ): Boolean {
        if (savedNext !in rows.indices) return false
        if (savedRow in rows.indices) currentRowIndex = savedRow
        pendingNextIndex = savedNext
        // phase must be set BEFORE renderRest(): it (and the
        // elapsed refresh inside it) reads phase-dependent state.
        phase = if (isRound) Phase.ROUNDREST else Phase.REST
        clearPlayerState()
        renderRest(isRound)
        // renderRest's elapsed refresh may have hit the AMRAP
        // deadline and finished the circuit instead (phase flipped
        // to COMPLETE) — don't arm a rest interval then, mirroring
        // enterRest()'s guard. The restore still preempted the
        // normal resume flow, so report success either way.
        if (phase != Phase.REST && phase != Phase.ROUNDREST) return true
        intervalTotalMs = savedTotal
        intervalRemainingMs = savedRem
        intervalPausedByUser = savedPaused
        intervalActive = savedActive && savedTotal > 0
        if (!intervalActive) {
            updateIntervalUi()
        } else if (intervalPausedByUser) {
            updateIntervalUi()
        } else {
            intervalEndsAtMs = SystemClock.uptimeMillis() + intervalRemainingMs
            updateIntervalUi()
            startIntervalLoop()
        }
        return true
    }

    // ---------- chrome ----------

    private fun buildChrome() {
        rootFrame = FrameLayout(this).apply { setBackgroundColor(Theme.bg) }
        content = fitRoot()
        content.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        rootFrame.addView(content)
        val cf = ConfettiView(this)
        cf.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        rootFrame.addView(cf)
        confetti = cf
        setContentView(rootFrame)
    }

    /** The deck's dark band: round label left, live elapsed right. */
    private fun bandView(round: Int): LinearLayout {
        val c = circuit!!
        val band = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#16324F")); cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        band.addView(TextView(this).apply {
            text = if (c.rounds == -1) "AMRAP · ROUND $round" else "ROUND $round OF ${c.rounds}"
            textSize = 13.5f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.06f
        })
        band.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bandTimerTv = TextView(this).apply {
            textSize = 14f; setTextColor(Color.parseColor("#FACC15"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setFontFeatureSettings("tnum")
        }
        band.addView(bandTimerTv)
        return band
    }

    private fun controlButton(text: String, bg: Int, fg: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text; textSize = 15f; setTextColor(fg)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setAllCaps(false)
            background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(14).toFloat() }
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    // ---------- countdown (deck precedent) ----------

    private var countStep = 0
    private var countPosted = false
    private val countRunnable = object : Runnable {
        override fun run() {
            countPosted = false
            countStep--
            if (countStep <= 0) {
                beginWork(currentRowIndex)
            } else {
                renderCountdown()
                postCount()
            }
        }
    }

    private fun postCount() {
        if (countPosted) return
        countPosted = true
        handler.postDelayed(countRunnable, COUNT_STEP_MS)
    }

    private fun showCountdown() {
        phase = Phase.COUNTDOWN
        countStep = 3
        renderCountdown()
        postCount()
    }

    private fun renderCountdown() {
        photoLooper?.stop(); photoLooper = null
        bandTimerTv = null
        content.removeAllViews()
        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val circleWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(170), dp(170)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        circleWrap.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Theme.primary) }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        })
        val num = TextView(this).apply {
            text = countStep.toString(); textSize = 96f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        circleWrap.addView(num)
        content.addView(circleWrap)
        num.scaleX = 0.7f; num.scaleY = 0.7f
        num.animate().scaleX(1f).scaleY(1f).setDuration(260)
            .setInterpolator(OvershootInterpolator(1.4f)).start()
        content.addView(makeText("Get ready…", 16f, true).apply {
            gravity = Gravity.CENTER
            (layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(22), 0, 0) }
        })
        content.addView(makeText("First motion is coming up.", 13f, false, Theme.textSecondary).apply {
            gravity = Gravity.CENTER
            (layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(5), 0, 0) }
        })
        val dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(20), 0, 0) }
        }
        for (i in 1..3) {
            dots.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (i <= 3 - countStep + 1) Theme.primary else Color.parseColor("#CBD8E4"))
                }
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { setMargins(dp(3), 0, dp(3), 0) }
            })
        }
        content.addView(dots)
        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
    }

    // ---------- helpers ----------

    private fun refreshRows() {
        rows = db.getSessionExercises(sessionId)
    }

    private fun setRepsFor(step: CircuitsData.CStep): String =
        if (step.timedSec > 0) "${step.timedSec} sec" else step.repsLabel

    private fun isEmom(): Boolean {
        val f = circuit?.format ?: return false
        return f.contains("EMOM", ignoreCase = true) || f.contains("every", ignoreCase = true)
    }

    /** The interval a step runs on: its own timed dose, else the EMOM
     *  minute (120s for "every 2 minutes" formats), else none. */
    private fun stepIntervalSec(step: CircuitsData.CStep): Int = when {
        step.timedSec > 0 -> step.timedSec
        isEmom() -> circuit?.workSec?.takeIf { it > 0 }
            ?: if (circuit?.format?.contains("2 min", ignoreCase = true) == true) 120 else 60
        else -> 0
    }

    private fun boxSec(): Int = (circuit?.timeBoxMin ?: 0) * 60

    private fun amrapBoxRemains(): Boolean {
        val box = boxSec()
        if (box <= 0) return true // no deadline recorded: runs until End
        val s = db.getSession(sessionId) ?: return false
        return WorkoutTimer.liveElapsedSec(this, s) < box
    }

    private fun currentStep(): CircuitsData.CStep? {
        if (playSteps.isEmpty() || stepsPerRound <= 0) return null
        return playSteps.getOrNull(currentRowIndex % stepsPerRound)
    }

    private fun stepDisplayName(step: CircuitsData.CStep, rowIdx: Int? = null): String {
        JsonSeed.entryById(this, step.entryId)?.name?.takeIf { it.isNotBlank() }?.let { return it }
        if (rowIdx != null && rowIdx in rows.indices) return rows[rowIdx].name
        return step.entryId
    }

    private fun appendAmrapRound() {
        val bySource = db.getAllExercises().associateBy { it.sourceId }
        for (step in playSteps) {
            val ex = bySource[step.entryId] ?: continue
            db.appendCircuitExercise(sessionId, ex, setRepsFor(step))
        }
    }

    private fun markRow(rowIdx: Int, status: String) {
        val row = rows.getOrNull(rowIdx) ?: return
        if (status == "done") {
            db.getSets(row.id).firstOrNull()?.let { db.updateSet(it.copy(isDone = true)) }
        }
        db.setSessionExerciseStatus(row.id, status)
    }

    private fun countFullRounds(): Int {
        if (stepsPerRound <= 0) return 0
        return rows.chunked(stepsPerRound).count { chunk ->
            chunk.size == stepsPerRound && chunk.all {
                val st = db.effectiveStatus(it); st == "done" || st == "skipped"
            }
        }
    }

    // ---------- work screen (mock screen 4) ----------

    private fun beginWork(rowIndex: Int) {
        val c = circuit ?: return
        if (c.rounds == -1 && boxSec() > 0) {
            val s = db.getSession(sessionId)
            if (s != null && WorkoutTimer.liveElapsedSec(this, s) >= boxSec()) {
                finishCircuit(); return
            }
        }
        if (rowIndex !in rows.indices) { finishCircuit(); return }
        currentRowIndex = rowIndex
        phase = Phase.WORK
        intervalPausedByUser = false
        renderWork()
        // v3.12: persist after renderWork() so the interval state it
        // armed (or restored) is what gets saved.
        savePlayerState()
    }

    private fun renderWork() {
        val c = circuit ?: return
        val step = currentStep() ?: return
        val row = rows.getOrNull(currentRowIndex) ?: return
        photoLooper?.stop(); photoLooper = null
        content.removeAllViews()
        ringView = null; ringBigTv = null; pauseBtnTv = null; restBigTv = null

        val stepIdx = if (stepsPerRound > 0) currentRowIndex % stepsPerRound else 0
        val round = if (stepsPerRound > 0) currentRowIndex / stepsPerRound + 1 else 1

        content.addView(bandView(round))
        refreshElapsed()
        if (phase != Phase.WORK) return // AMRAP deadline hit mid-render

        // Photo frame (250dp): catalog photo with the deck's two-frame
        // crossfade, or the body-map fallback.
        val entry = JsonSeed.entryById(this, step.entryId)
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(250)).apply { setMargins(0, dp(14), 0, 0) }
            roundedClip(20)
        }
        val photoAsset = entry?.photo?.takeIf { it.isNotBlank() }
        val photoBmp = photoAsset?.let { PhotoLib.bitmap(this, it) }
        if (photoBmp != null && photoAsset != null) {
            frame.setBackgroundColor(Theme.surfaceVariant)
            frame.addView(ImageView(this).apply {
                setImageBitmap(photoBmp); scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
            val photo2 = PhotoLib.secondBitmap(this, photoAsset)
            if (photo2 != null) {
                val iv2 = ImageView(this).apply {
                    setImageBitmap(photo2); scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                }
                frame.addView(iv2)
                val looper = PhotoLooper(iv2); photoLooper = looper; looper.start()
            }
        } else {
            frame.background = roundedBg(Theme.surfaceVariant, 20)
            val muscles = if (entry != null) DbHelper.parseMuscles(entry.targetMuscles, entry.name)
                else DbHelper.parseMuscles(row.targetMuscles, row.name)
            frame.addView(BodyMapView(this, muscles, true).apply {
                layoutParams = FrameLayout.LayoutParams(dp(110), dp(140),
                    Gravity.CENTER_VERTICAL or Gravity.END).apply { setMargins(0, 0, dp(16), 0) }
            })
        }
        // v3.12: form video on the photo frame — the gym card's
        // badge (38dp dark oval, white ▶) added last so it draws on
        // top, plus the whole frame is tappable (the photo
        // ImageViews and BodyMapView are non-clickable, so taps
        // land on the frame). Tap opens the Short when one exists,
        // long-press always opens the full video. Freeze
        // semantics: opening the video leaves this activity, so
        // onPause() captures intervalRemainingMs and onResume()
        // re-arms it from that remainder (a running interval stays
        // running, a user-paused one stays paused) — no extra
        // freeze code is needed here, and the v3.12 cp_* save in
        // onPause() preserves the same remainder across a
        // destroy/recreate while away.
        val short = entry?.shortUrl?.takeIf { it.isNotBlank() }
        val full = entry?.url?.takeIf { it.isNotBlank() } ?: row.youtubeUrl.takeIf { it.isNotBlank() }
        if (full != null) {
            frame.isClickable = true
            frame.setOnClickListener { openUrl(this@CircuitPlayerActivity, short ?: full) }
            frame.setOnLongClickListener {
                openUrl(this@CircuitPlayerActivity, full)
                true
            }
            frame.addView(TextView(this).apply {
                text = "▶"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(0x8C0F172A.toInt())
                }
                isClickable = true
                setOnClickListener { openUrl(this@CircuitPlayerActivity, short ?: full) }
                setOnLongClickListener {
                    openUrl(this@CircuitPlayerActivity, full)
                    true
                }
                layoutParams = FrameLayout.LayoutParams(dp(38), dp(38), Gravity.TOP or Gravity.END)
                    .apply { setMargins(0, dp(10), dp(10), 0) }
            })
        }
        content.addView(frame)

        content.addView(makeText(entry?.name?.takeIf { it.isNotBlank() } ?: row.name, 23f, true).apply {
            gravity = Gravity.CENTER
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            (layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(14), 0, 0) }
        })

        // Dose sub-line: EMOM timed steps carry their dose here (the ring
        // centre is busy with the clock); rep steps carry it in the ring.
        if (isEmom() && step.timedSec > 0 && step.doseLabel.isNotBlank()) {
            content.addView(makeText(step.doseLabel, 13f, false, Theme.textSecondary).apply {
                gravity = Gravity.CENTER
                (layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(4), 0, 0) }
            })
        }

        // Ring: custom view + centred text column (deck-style children,
        // not canvas text).
        val intervalSec = stepIntervalSec(step)
        val timedVisual = step.timedSec > 0
        val ringWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(168), dp(168)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, dp(10), 0, 0)
            }
        }
        val ring = CircuitRingView(this).apply {
            totalMs = if (timedVisual) intervalSec * 1000L else 0L
            remainingMs = if (timedVisual) intervalSec * 1000L else 0L
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        ringView = ring
        ringWrap.addView(ring)
        val centerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val doseText = step.doseLabel.ifBlank { step.repsLabel }
        ringBigTv = TextView(this).apply {
            text = if (timedVisual) WorkoutTimer.formatClock(intervalSec) else doseText
            textSize = if (!timedVisual && doseText.length > 4) 30f else 44f
            setTextColor(Theme.textPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setFontFeatureSettings("tnum")
            gravity = Gravity.CENTER
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        centerCol.addView(ringBigTv)
        centerCol.addView(TextView(this).apply {
            text = if (timedVisual) "WORK" else "REPS"
            textSize = 11f; setTextColor(Theme.textSecondary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.14f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(4), 0, 0) }
        })
        ringWrap.addView(centerCol)
        content.addView(ringWrap)

        // Segments: this round's motions.
        val segs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).apply { setMargins(0, dp(14), 0, 0) }
        }
        val roundStart = currentRowIndex - stepIdx
        for (i in 0 until stepsPerRound) {
            val rIdx = roundStart + i
            val color = when {
                rIdx == currentRowIndex -> Theme.primary
                rIdx in rows.indices -> when (db.effectiveStatus(rows[rIdx])) {
                    "done" -> Theme.success
                    "skipped" -> Theme.textTertiary
                    else -> Theme.surfaceVariant
                }
                else -> Theme.surfaceVariant
            }
            segs.addView(View(this).apply {
                background = roundedBg(color, 3)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    .apply { setMargins(if (i > 0) dp(5) else 0, 0, 0, 0) }
            })
        }
        content.addView(segs)

        // Next line (name bolded; round-aware at the round's last step).
        val nextTv = TextView(this).apply {
            textSize = 13.5f; setTextColor(Theme.textSecondary); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(10), 0, 0) }
        }
        when {
            stepIdx < stepsPerRound - 1 -> {
                val name = stepDisplayName(playSteps[stepIdx + 1], currentRowIndex + 1)
                val full = "Next: $name"
                nextTv.text = SpannableString(full).apply {
                    setSpan(StyleSpan(Typeface.BOLD), 6, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            c.rounds == -1 || round < c.rounds -> {
                val name = stepDisplayName(playSteps[0], roundStart)
                val full = "Next: $name (round ${round + 1})"
                nextTv.text = SpannableString(full).apply {
                    setSpan(StyleSpan(Typeface.BOLD), 6, 6 + name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            else -> nextTv.text = "Last one — finish strong"
        }
        content.addView(nextTv)

        // Controls: Pause/Done · Skip · End.
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { setMargins(0, dp(14), 0, 0) }
        }
        if (timedVisual) {
            pauseBtnTv = controlButton("⏸ Pause", Theme.surface, Theme.primaryDark) { toggleIntervalPause() }
            pauseBtnTv!!.layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            controls.addView(pauseBtnTv)
        } else {
            val doneBtn = controlButton("✓ Done", Theme.success, Color.WHITE) { resolveCurrent("done") }
            doneBtn.layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            controls.addView(doneBtn)
        }
        val skipBtn = controlButton("⏭ Skip", IconTints.BLUE, Theme.primaryDark) { resolveCurrent("skipped") }
        skipBtn.layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(10), 0, 0, 0) }
        controls.addView(skipBtn)
        val endBtn = controlButton("✕ End", Color.parseColor("#FFE0E0"), Color.parseColor("#9B2226")) { onEndTap() }
        endBtn.layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(10), 0, 0, 0) }
        controls.addView(endBtn)
        content.addView(controls)

        // v3.12: a restored WORK row resumes its saved interval
        // (total / remaining / paused) instead of restarting at
        // the step's full interval; every other render keeps the
        // original fresh-interval behaviour.
        val rt = pendingRestoreTotal
        if (rt >= 0 && currentRowIndex == pendingRestoreRow) {
            intervalTotalMs = rt
            intervalRemainingMs = pendingRestoreRemaining
            intervalPausedByUser = pendingRestorePaused
            // The SAVED active flag is the gate — never the totals:
            // a rep step saved after a timed step can carry a stale
            // total, and arming it would instantly time the step out.
            intervalActive = pendingRestoreActive && rt > 0
            pendingRestoreTotal = -1L
            pendingRestoreRemaining = -1L
            pendingRestorePaused = false
            pendingRestoreActive = false
            pendingRestoreRow = -1
            if (intervalActive) {
                if (step.timedSec > 0) {
                    ring.totalMs = rt
                    ring.setRemaining(intervalRemainingMs)
                    ringBigTv?.text = WorkoutTimer.formatClock(((intervalRemainingMs + 999) / 1000).toInt())
                }
                if (intervalPausedByUser) {
                    pauseBtnTv?.text = "▶ Resume"
                    updateIntervalUi()
                } else {
                    intervalEndsAtMs = SystemClock.uptimeMillis() + intervalRemainingMs
                    updateIntervalUi()
                    startIntervalLoop()
                }
            }
        } else if (intervalSec > 0) startInterval(intervalSec) else intervalActive = false
    }

    // ---------- rest screens ----------

    private fun enterRest(isRound: Boolean, sec: Int, pendingNext: Int) {
        pendingNextIndex = pendingNext
        phase = if (isRound) Phase.ROUNDREST else Phase.REST
        renderRest(isRound)
        // renderRest's elapsed refresh may have hit the AMRAP deadline
        // and finished the circuit instead — don't arm a rest interval.
        if (phase != Phase.REST && phase != Phase.ROUNDREST) return
        startInterval(sec)
        // v3.12: persist the freshly armed rest interval.
        savePlayerState()
    }

    private fun renderRest(isRound: Boolean) {
        photoLooper?.stop(); photoLooper = null
        content.removeAllViews()
        ringView = null; ringBigTv = null; pauseBtnTv = null

        val bandRound = when {
            isRound && pendingNextIndex in rows.indices && stepsPerRound > 0 ->
                pendingNextIndex / stepsPerRound + 1
            stepsPerRound > 0 -> currentRowIndex / stepsPerRound + 1
            else -> 1
        }
        content.addView(bandView(bandRound))
        refreshElapsed()

        val strokeColor = if (isRound) Theme.success else Color.parseColor("#D97706")
        val fillColor = if (isRound) Theme.successBg else Color.parseColor("#FFFBEB")
        val labelColor = if (isRound) Theme.success else Color.parseColor("#B45309")
        val nextStep = if (pendingNextIndex in rows.indices && stepsPerRound > 0)
            playSteps.getOrNull(pendingNextIndex % stepsPerRound) else null
        val nextName = nextStep?.let { stepDisplayName(it, pendingNextIndex) } ?: ""

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(fillColor); cornerRadius = dp(16).toFloat()
                setStroke(dp(2), strokeColor, dp(8).toFloat(), dp(6).toFloat())
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(16), 0, 0) }
        }
        box.addView(TextView(this).apply {
            text = if (isRound) "ROUND $bandRound COMING UP" else "REST"
            textSize = 11f; setTextColor(labelColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.14f
        })
        restBigTv = TextView(this).apply {
            textSize = 40f; setTextColor(labelColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setFontFeatureSettings("tnum")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(4), 0, 0) }
        }
        box.addView(restBigTv)
        val lineText = "Catch your breath. Next: $nextName"
        box.addView(TextView(this).apply {
            text = SpannableString(lineText).apply {
                val start = lineText.indexOf(nextName)
                if (nextName.isNotEmpty() && start >= 0) {
                    setSpan(StyleSpan(Typeface.BOLD), start, start + nextName.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            textSize = 13.5f; setTextColor(Theme.textPrimary)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(4), 0, 0) }
        })
        val pillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(10), 0, 0) }
        }
        pillRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        pillRow.addView(TextView(this).apply {
            text = "Skip rest ›"; textSize = 13f; setTextColor(Theme.primary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(999).toFloat()
                setStroke(dp(1), Theme.stroke)
            }
            setPadding(dp(12), dp(7), dp(12), dp(7))
            isClickable = true
            setOnClickListener { onRestDone() }
        })
        box.addView(pillRow)
        content.addView(box)

        content.addView(TextView(this).apply {
            text = "✕ End circuit"; textSize = 14f; setTextColor(Theme.textSecondary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(12), 0, 0) }
            isClickable = true
            setOnClickListener { onEndTap() }
        })
    }

    // ---------- interval engine (deck pattern) ----------

    private fun startInterval(sec: Int) {
        intervalTotalMs = sec * 1000L
        intervalRemainingMs = intervalTotalMs
        intervalPausedByUser = false
        intervalActive = true
        intervalEndsAtMs = SystemClock.uptimeMillis() + intervalRemainingMs
        updateIntervalUi()
        startIntervalLoop()
    }

    private fun startIntervalLoop() {
        if (intervalRunning) return
        intervalRunning = true
        handler.post(intervalTick)
    }

    private fun stopIntervalLoop() {
        intervalRunning = false
        handler.removeCallbacks(intervalTick)
    }

    /** Returns true when the interval expired and its timeout was
     *  dispatched (the caller chain must not repost). */
    private fun tickInterval(): Boolean {
        if (!intervalActive) { stopIntervalLoop(); return false }
        intervalRemainingMs = (intervalEndsAtMs - SystemClock.uptimeMillis()).coerceAtLeast(0)
        if (intervalRemainingMs <= 0L) {
            stopIntervalLoop()
            intervalActive = false
            when (phase) {
                Phase.WORK -> onStepTimeout()
                Phase.REST, Phase.ROUNDREST -> onRestTimeout()
                else -> {}
            }
            return true
        }
        updateIntervalUi()
        return false
    }

    private fun updateIntervalUi() {
        val remainSec = ((intervalRemainingMs + 999) / 1000).toInt()
        when (phase) {
            Phase.WORK -> {
                val step = currentStep() ?: return
                if (step.timedSec > 0) {
                    ringBigTv?.text = WorkoutTimer.formatClock(remainSec)
                    ringView?.setRemaining(intervalRemainingMs)
                }
                // EMOM rep steps: the ring keeps the dose; the minute is
                // tracked silently for the early-done remainder rest.
            }
            Phase.REST, Phase.ROUNDREST -> restBigTv?.text = WorkoutTimer.formatClock(remainSec)
            else -> {}
        }
    }

    private fun toggleIntervalPause() {
        if (phase != Phase.WORK || !intervalActive) return
        if (intervalPausedByUser) {
            intervalPausedByUser = false
            pauseBtnTv?.text = "⏸ Pause"
            intervalEndsAtMs = SystemClock.uptimeMillis() + intervalRemainingMs
            startIntervalLoop()
        } else {
            intervalRemainingMs = (intervalEndsAtMs - SystemClock.uptimeMillis()).coerceAtLeast(0)
            intervalPausedByUser = true
            pauseBtnTv?.text = "▶ Resume"
            stopIntervalLoop()
            updateIntervalUi()
        }
        // v3.12: the pause flip (and its frozen remainder) is
        // exactly what a restore must reproduce.
        savePlayerState()
    }

    private fun onStepTimeout() {
        if (phase != Phase.WORK) return
        buzz()
        resolveCurrent("done", viaTimeout = true)
    }

    private fun onRestTimeout() {
        buzz()
        onRestDone()
    }

    private fun onRestDone() {
        if (phase != Phase.REST && phase != Phase.ROUNDREST) return
        stopIntervalLoop()
        intervalActive = false
        val next = pendingNextIndex
        pendingNextIndex = -1
        if (next in rows.indices) beginWork(next) else finishCircuit()
    }

    // ---------- advance ----------

    private fun resolveCurrent(status: String, viaTimeout: Boolean = false) {
        if (phase != Phase.WORK) return
        val c = circuit ?: return
        val liveRemaining = if (intervalRunning)
            (intervalEndsAtMs - SystemClock.uptimeMillis()).coerceAtLeast(0) else intervalRemainingMs
        stopIntervalLoop()
        intervalActive = false
        photoLooper?.stop(); photoLooper = null

        markRow(currentRowIndex, status)
        refreshRows()

        if (stepsPerRound <= 0) { finishCircuit(); return }
        val stepIdx = currentRowIndex % stepsPerRound
        val round = currentRowIndex / stepsPerRound + 1
        val nextIndex = currentRowIndex + 1
        val inSameRound = nextIndex < rows.size && (nextIndex / stepsPerRound) == (currentRowIndex / stepsPerRound)

        if (status == "skipped") {
            // Skip NEVER triggers a rest — not even a round rest.
            when {
                inSameRound -> beginWork(nextIndex)
                else -> {
                    val more = if (c.rounds == -1) amrapBoxRemains() else round < c.rounds
                    if (!more) { finishCircuit(); return }
                    if (c.rounds == -1) { appendAmrapRound(); refreshRows() }
                    if (nextIndex < rows.size) beginWork(nextIndex) else finishCircuit()
                }
            }
            return
        }

        // done (tap, or interval expiry)
        if (inSameRound) {
            when {
                isEmom() && !viaTimeout && liveRemaining > 0 ->
                    enterRest(false, ((liveRemaining + 999) / 1000).toInt().coerceAtLeast(1), nextIndex)
                isEmom() && viaTimeout -> beginWork(nextIndex)
                c.restSec > 0 -> enterRest(false, c.restSec, nextIndex)
                else -> beginWork(nextIndex)
            }
        } else {
            val more = if (c.rounds == -1) amrapBoxRemains() else round < c.rounds
            if (!more) { finishCircuit(); return }
            if (c.rounds == -1) { appendAmrapRound(); refreshRows() }
            if (nextIndex >= rows.size) { finishCircuit(); return }
            if (c.roundRestSec > 0) enterRest(true, c.roundRestSec, nextIndex) else beginWork(nextIndex)
        }
    }

    // ---------- end / finish / discard ----------

    private fun onEndTap() {
        if (phase == Phase.COMPLETE || phase == Phase.COUNTDOWN) return
        refreshRows()
        val doneCount = rows.count { db.effectiveStatus(it) == "done" }
        if (doneCount == 0) { discardSession(); return }
        // Freeze the interval while the dialog is up so a timed step
        // cannot expire underneath it.
        val wasRunning = intervalRunning
        if (intervalRunning) {
            intervalRemainingMs = (intervalEndsAtMs - SystemClock.uptimeMillis()).coerceAtLeast(0)
            stopIntervalLoop()
        }
        val resumeIfNeeded = {
            if (wasRunning && !intervalPausedByUser && intervalActive &&
                (phase == Phase.WORK || phase == Phase.REST || phase == Phase.ROUNDREST)) {
                intervalEndsAtMs = SystemClock.uptimeMillis() + intervalRemainingMs
                startIntervalLoop()
            }
        }
        AlertDialog.Builder(this)
            .setTitle("End circuit?")
            .setMessage("Finish here and save what's done.")
            .setPositiveButton("End circuit") { _, _ -> finishCircuit() }
            .setNegativeButton("Keep going") { _, _ -> resumeIfNeeded() }
            .setOnCancelListener { resumeIfNeeded() }
            .show()
    }

    private fun finishCircuit() {
        if (phase == Phase.COMPLETE) return
        // v3.12: a finished circuit has nothing to resume.
        clearPlayerState()
        stopIntervalLoop()
        intervalActive = false
        stopElapsed()
        photoLooper?.stop(); photoLooper = null
        handler.removeCallbacks(countRunnable)
        countPosted = false
        refreshRows()
        val fresh = db.getSession(sessionId)
        if (fresh != null) WorkoutTimer.finish(this, db, fresh)
        db.setSessionCompleted(sessionId, true)
        DriveBackup.backupSilently(this, db)
        HealthSync.syncSessionIfEnabled(this, db, sessionId)
        phase = Phase.COMPLETE

        val c = circuit ?: return
        val doneCount = rows.count { db.effectiveStatus(it) == "done" }
        val fullRounds = countFullRounds()
        val full = if (c.rounds == -1) {
            val box = boxSec()
            val elapsed = db.getSession(sessionId)?.elapsedSec ?: 0
            box > 0 && elapsed >= box && fullRounds >= 1
        } else {
            rows.isNotEmpty() && rows.all { db.effectiveStatus(it) != "pending" }
        }
        renderComplete(full, fullRounds, doneCount)
        if (full) confetti?.post { confetti?.burst() }
    }

    private fun discardSession() {
        handler.removeCallbacksAndMessages(null)
        stopIntervalLoop(); stopElapsed()
        photoLooper?.stop()
        WorkoutTimer.clear(this, sessionId)
        // v3.12: drop the saved player state with the session.
        clearPlayerState()
        getSharedPreferences("workout_prefs", Context.MODE_PRIVATE).edit()
            .remove("circuitSpr_$sessionId").remove("circuitSteps_$sessionId").apply()
        db.deleteSession(sessionId)
        Toast.makeText(this, "Circuit discarded", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ---------- complete screen (mock screen 5) ----------

    private fun renderComplete(full: Boolean, fullRounds: Int, doneCount: Int) {
        val c = circuit ?: return
        photoLooper?.stop(); photoLooper = null
        content.removeAllViews()
        bandTimerTv = null; ringView = null; ringBigTv = null; pauseBtnTv = null; restBigTv = null

        content.addView(makeText("🎉", 54f, true).apply {
            gravity = Gravity.CENTER
            (layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(26), 0, 0) }
        })
        content.addView(makeText(if (full) "Circuit complete!" else "Circuit ended", 22f, true).apply {
            gravity = Gravity.CENTER
            (layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(8), 0, 0) }
        })
        content.addView(makeText(
            when {
                full && c.rounds == -1 -> "${c.name} · time's up"
                full -> "${c.name} · all ${c.rounds} rounds"
                else -> c.name
            }, 13f, false, Theme.textSecondary).apply {
            gravity = Gravity.CENTER
            (layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(4), 0, 0) }
        })

        val totalSec = db.getSession(sessionId)?.elapsedSec ?: 0
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(18), 0, 0) }
        }
        fun statCard(value: String, label: String, leftMargin: Int) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.WHITE); cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), Theme.stroke)
                }
                setPadding(dp(6), dp(14), dp(6), dp(14))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(leftMargin, 0, 0, 0) }
            }
            card.addView(TextView(this).apply {
                text = value; textSize = 19f; setTextColor(Theme.textPrimary)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER; setFontFeatureSettings("tnum")
            })
            card.addView(TextView(this).apply {
                text = label; textSize = 11f; setTextColor(Theme.textSecondary)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(2), 0, 0) }
            })
            grid.addView(card)
        }
        statCard(doneCount.toString(), "motions done", 0)
        statCard(WorkoutTimer.formatDuration(totalSec), "total time", dp(10))
        if (c.rounds == -1) statCard(fullRounds.toString(), "full rounds", dp(10))
        else statCard("$fullRounds / ${c.rounds}", "rounds", dp(10))
        content.addView(grid)

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Theme.stroke)
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(14), 0, 0) }
        }
        info.addView(settingsIcon("⚡", IconTints.GREEN))
        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(12), 0, 0, 0) }
        }
        infoCol.addView(makeText("Counts as today's Circuits session", 15f, true).apply {
            (layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, 0, 0, 0) }
        })
        infoCol.addView(makeText("Saved to History · syncs to Health Connect like other workouts",
            12f, false, Theme.textSecondary).apply {
            (layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(1), 0, 0) }
        })
        info.addView(infoCol)
        content.addView(info)

        val doneBtn = makeButton("Done") {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            finish()
        }
        (doneBtn.layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(16), 0, 0) }
        content.addView(doneBtn)

        content.addView(TextView(this).apply {
            text = "↻ Do it again"; textSize = 15.5f; setTextColor(Theme.primaryDark)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(16).toFloat()
                setStroke((1.5f * resources.displayMetrics.density).toInt(), Theme.stroke)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { setMargins(0, dp(10), 0, 0) }
            isClickable = true
            setOnClickListener {
                // Fresh player for the same circuit + step order; the
                // just-completed session is completed, so the creation
                // path makes a brand-new session.
                startActivity(Intent(this@CircuitPlayerActivity, CircuitPlayerActivity::class.java).apply {
                    putExtra("circuitId", c.id)
                    putExtra("stepIndices", playStepIndices.toIntArray())
                })
                finish()
            }
        })
    }

    // ---------- elapsed chip + AMRAP deadline ----------

    private fun startElapsed() {
        if (elapsedRunning) return
        elapsedRunning = true
        handler.post(elapsedTick)
    }

    private fun stopElapsed() {
        elapsedRunning = false
        handler.removeCallbacks(elapsedTick)
    }

    private fun refreshElapsed() {
        if (sessionId <= 0) return
        val s = db.getSession(sessionId) ?: return
        val live = WorkoutTimer.liveElapsedSec(this, s)
        bandTimerTv?.text = "⏱ " + WorkoutTimer.formatClock(live)
        val c = circuit ?: return
        if (c.rounds == -1 && boxSec() > 0 && live >= boxSec() &&
            (phase == Phase.WORK || phase == Phase.REST)) {
            // Time's up: the step on screen counts as done, then finish.
            if (phase == Phase.WORK) {
                stopIntervalLoop()
                intervalActive = false
                markRow(currentRowIndex, "done")
            }
            finishCircuit()
        }
    }

    private fun buzz() {
        try {
            val vib = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            vib.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) { /* haptics are a nicety, never a crash */ }
    }

    companion object {
        private const val COUNT_STEP_MS = 800L
        private const val TICK_MS = 200L
    }
}

/** The circuit player's work ring (modeled on RestRingView): a 10dp
 *  stroke circle that drains with the interval, turning amber (#D97706)
 *  in the final 10 seconds. A totalMs of 0 draws the ring FULL — the rep
 *  step state, where the centre carries the dose instead of a clock.
 *  Centre text is drawn by child TextViews in the activity, not canvas. */
private class CircuitRingView(context: Context) : View(context) {
    var totalMs: Long = 0
    var remainingMs: Long = 0
    private val strokePx = context.dp(10).toFloat()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = strokePx
        color = Theme.surfaceVariant; strokeCap = Paint.Cap.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = strokePx; strokeCap = Paint.Cap.ROUND
    }
    private val arc = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 20f
        arc.set(pad, pad, width - pad, height - pad)
        canvas.drawArc(arc, 0f, 360f, false, trackPaint)
        val frac = if (totalMs > 0) (remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f) else 1f
        val remainSec = ceil(remainingMs / 1000.0).toInt()
        ringPaint.color = when {
            totalMs > 0 && remainSec <= 10 && frac < 1f -> Color.parseColor("#D97706")
            else -> Theme.primary
        }
        if (frac > 0f) canvas.drawArc(arc, -90f, 360f * frac, false, ringPaint)
    }

    fun setRemaining(ms: Long) {
        remainingMs = ms
        invalidate()
    }
}
