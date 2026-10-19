package com.mahesh.workouttracker

import android.content.Context
import android.content.Intent
import android.graphics.Color
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
    private var ringView: BendRingView? = null
    private var ringBigTv: TextView? = null
    private var pauseBtnTv: TextView? = null
    private var pauseLblTv: TextView? = null
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

    /** v3.13 Bend top row: hairline progress over a quiet name /
     *  micro / elapsed / close row. [bandTimerTv] is (re)created
     *  here so refreshElapsed() keeps writing "⏱ " + clock into it. */
    private fun topRow(round: Int, stepIdx: Int): LinearLayout {
        val c = circuit
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val frac = if (rows.isEmpty()) 0f else
            rows.count { db.effectiveStatus(it) != "pending" }.toFloat() / rows.size
        col.addView(BendHairlineView(this).apply {
            fraction = frac
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3))
        })
        val rowL = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(14), 0, 0) }
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(bendScreenName(c?.name ?: "").apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, 0, 0)
        })
        val microText = when {
            c == null -> ""
            c.rounds == -1 -> "AMRAP · ROUND $round · MOTION ${stepIdx + 1} OF $stepsPerRound" +
                (if ((c.timeBoxMin ?: 0) > 0) " · ${c.timeBoxMin} MIN" else "")
            c.rounds > 0 -> "ROUND $round OF ${c.rounds} · MOTION ${stepIdx + 1} OF $stepsPerRound"
            else -> ""
        }
        if (microText.isNotEmpty()) {
            left.addView(bendMicro(microText).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(2), 0, 0) }
            })
        }
        rowL.addView(left)
        bandTimerTv = TextView(this).apply {
            textSize = 14f; setTextColor(Theme.textSecondary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setFontFeatureSettings("tnum")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        rowL.addView(bandTimerTv)
        rowL.addView(TextView(this).apply {
            text = "✕"; textSize = 17f; setTextColor(Theme.textTertiary)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(4), 0, 0, 0) }
            isClickable = true
            setOnClickListener { onEndTap() }
        })
        col.addView(rowL)
        return col
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
        ringView = null; ringBigTv = null; pauseBtnTv = null; pauseLblTv = null; restBigTv = null

        val stepIdx = if (stepsPerRound > 0) currentRowIndex % stepsPerRound else 0
        val round = if (stepsPerRound > 0) currentRowIndex / stepsPerRound + 1 else 1

        content.addView(topRow(round, stepIdx))
        refreshElapsed()
        if (phase != Phase.WORK) return // AMRAP deadline hit mid-render

        // v3.13: Bend circle (S×S) — catalog photo with the deck's
        // two-frame crossfade inside a circular frame, or the
        // body-map fallback centred; a thin Bend ring drains on top.
        val entry = JsonSeed.entryById(this, step.entryId)
        val intervalSec = stepIntervalSec(step)
        val timedVisual = step.timedSec > 0
        val s = if (isCompactScreen()) 240 else 288
        val frame = bendCircleFrame(s).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.apply {
                gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, dp(16), 0, 0)
            }
        }
        val photoAsset = entry?.photo?.takeIf { it.isNotBlank() }
        val photoBmp = photoAsset?.let { PhotoLib.bitmap(this, it) }
        if (photoBmp != null && photoAsset != null) {
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
            val muscles = if (entry != null) DbHelper.parseMuscles(entry.targetMuscles, entry.name)
                else DbHelper.parseMuscles(row.targetMuscles, row.name)
            frame.addView(BodyMapView(this, muscles, true).apply {
                layoutParams = FrameLayout.LayoutParams(dp(110), dp(140), Gravity.CENTER)
            })
        }
        val ring = BendRingView(this).apply {
            strokeDp = 4f
            fraction = 1f
            amberUnderSec = if (timedVisual) 10 else -1
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        ringView = ring
        frame.addView(ring)
        // v3.12: form video on the photo frame — no badge in v3.13;
        // the whole circle (and the watch line below) is tappable.
        // Tap opens the Short when one exists, long-press always
        // opens the full video. Freeze semantics: opening the video
        // leaves this activity, so onPause() captures
        // intervalRemainingMs and onResume() re-arms it from that
        // remainder (a running interval stays running, a user-paused
        // one stays paused) — no extra freeze code is needed here,
        // and the v3.12 cp_* save in onPause() preserves the same
        // remainder across a destroy/recreate while away.
        val short = entry?.shortUrl?.takeIf { it.isNotBlank() }
        val full = entry?.url?.takeIf { it.isNotBlank() } ?: row.youtubeUrl.takeIf { it.isNotBlank() }
        if (full != null) {
            frame.isClickable = true
            frame.setOnClickListener { openUrl(this@CircuitPlayerActivity, short ?: full) }
            frame.setOnLongClickListener {
                openUrl(this@CircuitPlayerActivity, full)
                true
            }
        }
        content.addView(frame)

        content.addView(bendName(entry?.name?.takeIf { it.isNotBlank() } ?: row.name).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, dp(16), 0, 0)
        })

        // Meta: timed steps carry their dose here (EMOM doses land
        // here too); rep steps get the quiet "REPS" micro instead.
        if (timedVisual) {
            content.addView(bendMeta(step.doseLabel.ifBlank { "${stepIntervalSec(step)} seconds" }).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, dp(4), 0, 0)
            })
        } else {
            content.addView(bendMicro("REPS").apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(4), 0, 0) }
            })
        }

        if (full != null) {
            content.addView(bendWatchLine().apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(2), 0, 0) }
                isClickable = true
                setOnClickListener { openUrl(this@CircuitPlayerActivity, short ?: full) }
                setOnLongClickListener {
                    openUrl(this@CircuitPlayerActivity, full)
                    true
                }
            })
        }

        val doseText = step.doseLabel.ifBlank { step.repsLabel }
        ringBigTv = bendHuge(if (timedVisual) WorkoutTimer.formatClock(intervalSec) else doseText).apply {
            if (!timedVisual) textSize = if (doseText.length > 4) 30f else 44f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(6), 0, 0) }
        }
        content.addView(ringBigTv)

        // Next line (name bolded; round-aware at the round's last step).
        val nextTv = TextView(this).apply {
            textSize = 13f; setTextColor(Theme.textSecondary); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(6), 0, 0) }
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
                val name = stepDisplayName(playSteps[0], currentRowIndex - stepIdx)
                val full = "Next: $name (round ${round + 1})"
                nextTv.text = SpannableString(full).apply {
                    setSpan(StyleSpan(Typeface.BOLD), 6, 6 + name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            else -> nextTv.text = "Last one — finish strong"
        }
        content.addView(nextTv)

        // Controls sit at the bottom when the column is short.
        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(18), 0, 0) }
        }
        val simple = SimpleMode.isOn(this)
        val sideDp = if (simple) 64 else 52
        val sideSp = if (simple) 22f else 18f
        val heroDp = if (simple) 80 else 70
        val heroSp = if (simple) 30f else 25f
        val ctlLabelSp = if (simple) 11.5f else 10.5f
        controls.addView(bendControl(bendSoftCircle("✕", sideDp, sideSp), "End", labelSp = ctlLabelSp) { onEndTap() })
        // Hero: manual column so pauseBtnTv / pauseLblTv can be
        // updated in place by toggleIntervalPause() and the restore.
        val heroCircle: TextView
        val heroLabel: TextView
        val heroCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(30), 0, 0, 0) }
        }
        if (timedVisual) {
            heroCircle = bendHeroCircle(if (intervalPausedByUser) "▶" else "⏸", Theme.primary, heroDp, heroSp)
            heroLabel = TextView(this).apply {
                text = if (intervalPausedByUser) "Resume" else "Pause"
                textSize = ctlLabelSp; setTextColor(Theme.textTertiary)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(6), 0, 0) }
            }
            heroCol.setOnClickListener { toggleIntervalPause() }
        } else {
            heroCircle = bendHeroCircle("✓", Theme.success, heroDp, heroSp)
            heroLabel = TextView(this).apply {
                text = "Done"
                textSize = ctlLabelSp; setTextColor(Theme.textTertiary)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(6), 0, 0) }
            }
            heroCol.setOnClickListener { resolveCurrent("done") }
        }
        heroCircle.isClickable = false
        heroCol.addView(heroCircle)
        heroCol.addView(heroLabel)
        if (timedVisual) {
            pauseBtnTv = heroCircle
            pauseLblTv = heroLabel
        }
        controls.addView(heroCol)
        controls.addView(bendControl(bendSoftCircle("⏭", sideDp, sideSp), "Skip", labelSp = ctlLabelSp) { resolveCurrent("skipped") }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(30), 0, 0, 0) }
        })
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
                    ring.remainingSec = ((intervalRemainingMs + 999) / 1000).toInt()
                    ring.setFraction(if (rt > 0) intervalRemainingMs.toFloat() / rt else 1f)
                    ringBigTv?.text = WorkoutTimer.formatClock(((intervalRemainingMs + 999) / 1000).toInt())
                }
                if (intervalPausedByUser) {
                    pauseBtnTv?.text = "▶"
                    pauseLblTv?.text = "Resume"
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
        ringView = null; ringBigTv = null; pauseBtnTv = null; pauseLblTv = null

        val bandRound = when {
            isRound && pendingNextIndex in rows.indices && stepsPerRound > 0 ->
                pendingNextIndex / stepsPerRound + 1
            stepsPerRound > 0 -> currentRowIndex / stepsPerRound + 1
            else -> 1
        }
        val nextStepIdx = if (pendingNextIndex in rows.indices && stepsPerRound > 0)
            pendingNextIndex % stepsPerRound else 0
        val nextStep = if (pendingNextIndex in rows.indices && stepsPerRound > 0)
            playSteps.getOrNull(pendingNextIndex % stepsPerRound) else null
        val nextName = nextStep?.let { stepDisplayName(it, pendingNextIndex) } ?: ""

        content.addView(topRow(bandRound, nextStepIdx))
        refreshElapsed()
        if (phase != Phase.REST && phase != Phase.ROUNDREST) return // AMRAP deadline hit mid-render

        // v3.13: calm rest — the NEXT step's photo in the Bend
        // circle, ring in primary (round rest: success), quiet
        // name / meta, and the huge countdown.
        val nextEntry = nextStep?.entryId?.let { JsonSeed.entryById(this, it) }
        val s = if (isCompactScreen()) 240 else 288
        val frame = bendCircleFrame(s).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.apply {
                gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, dp(16), 0, 0)
            }
        }
        if (nextEntry != null) {
            val photoAsset = nextEntry.photo?.takeIf { it.isNotBlank() }
            val photoBmp = photoAsset?.let { PhotoLib.bitmap(this, it) }
            if (photoBmp != null && photoAsset != null) {
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
                val muscles = DbHelper.parseMuscles(nextEntry.targetMuscles, nextEntry.name)
                frame.addView(BodyMapView(this, muscles, true).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(110), dp(140), Gravity.CENTER)
                })
            }
        }
        val ring = BendRingView(this).apply {
            strokeDp = 4f
            fraction = 1f
            arcColor = if (isRound) Theme.success else Theme.primary
            amberUnderSec = 10
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        ringView = ring
        frame.addView(ring)
        content.addView(frame)

        content.addView(bendName(nextName.ifBlank { "Rest" }).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, dp(16), 0, 0)
        })
        content.addView(bendMeta(
            if (isRound) "Round $bandRound coming up · catch your breath"
            else "Catch your breath · next up").apply {
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, dp(4), 0, 0)
        })
        restBigTv = bendHuge("").apply {
            if (intervalTotalMs > 0) {
                text = WorkoutTimer.formatClock(((intervalRemainingMs + 999) / 1000).toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(6), 0, 0) }
        }
        content.addView(restBigTv)

        content.addView(bendControl(bendSoftPill("Skip rest"), "Go now") { onRestDone() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, dp(20), 0, 0) }
        })
        content.addView(bendTextAction("✕ End circuit") { onEndTap() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(10), 0, 0) }
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
                    ringView?.remainingSec = remainSec
                    ringView?.setFraction(if (intervalTotalMs > 0)
                        intervalRemainingMs.toFloat() / intervalTotalMs else 1f)
                }
                // EMOM rep steps: the ring keeps the dose; the minute is
                // tracked silently for the early-done remainder rest.
            }
            Phase.REST, Phase.ROUNDREST -> {
                restBigTv?.text = WorkoutTimer.formatClock(remainSec)
                ringView?.remainingSec = remainSec
                ringView?.setFraction(if (intervalTotalMs > 0)
                    intervalRemainingMs.toFloat() / intervalTotalMs else 1f)
            }
            else -> {}
        }
    }

    private fun toggleIntervalPause() {
        if (phase != Phase.WORK || !intervalActive) return
        if (intervalPausedByUser) {
            intervalPausedByUser = false
            pauseBtnTv?.text = "⏸"
            pauseLblTv?.text = "Pause"
            intervalEndsAtMs = SystemClock.uptimeMillis() + intervalRemainingMs
            startIntervalLoop()
        } else {
            intervalRemainingMs = (intervalEndsAtMs - SystemClock.uptimeMillis()).coerceAtLeast(0)
            intervalPausedByUser = true
            pauseBtnTv?.text = "▶"
            pauseLblTv?.text = "Resume"
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
        bandTimerTv = null; ringView = null; ringBigTv = null; pauseBtnTv = null; pauseLblTv = null; restBigTv = null

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
                    setColor(Palettes.current.sheetRow); cornerRadius = dp(16).toFloat()
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
                setColor(Palettes.current.sheetRow); cornerRadius = dp(16).toFloat()
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

        content.addView(TextView(this).apply {
            text = "Done"; textSize = 15.5f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Theme.primary); cornerRadius = dp(999).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { setMargins(0, dp(16), 0, 0) }
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@CircuitPlayerActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
                finish()
            }
        })

        content.addView(TextView(this).apply {
            text = "↻ Do it again"; textSize = 15.5f; setTextColor(Theme.primaryDark)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Palettes.current.sheetRow); cornerRadius = dp(999).toFloat()
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

