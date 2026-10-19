package com.mahesh.workouttracker

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
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Identity of a Movie Movements session: its routineName in the DB.
 *  Home (resume detection), DbHelper (creation) and HealthSync (the
 *  synced record's title) all key off this one constant. */
object MovieDeck {
    const val ROUTINE_NAME = "Movie Movements"
}

/**
 * v3.8.1 Movie Movements (approved mock screens 2–3): an ENDLESS deck of
 * gentle living-room movements. No schedule is ever shown — Start runs a
 * 3·2·1 countdown, then one movement at a time is dealt from a shuffled
 * bag of the 18 deck entries (membership = a counterSec of 30–60 in the
 * bundled JSON). Each movement runs its own counter, pinned in a band at
 * the top of the screen; the counter expiring completes the movement,
 * ✓ Done early completes it sooner, Skip deals the next one instantly.
 * The deck reshuffles forever; only ✕ End finishes the session.
 * v3.8.2: on a FRESH session the first 3 deals always have pictures
 * (see prepareFreshBag); a resumed session deals as before.
 *
 * Data model: the session is created EMPTY (DbHelper.createMovieSession).
 * Every deal APPENDS a pending session_exercises row; a completion marks
 * its one set done (reps = the entry's guide number) and credits the
 * movement's body part in session_parts the first time that part is
 * completed. End with ≥1 done goes through the standard finish path
 * (timer finalize, Summary celebrate, Health Connect + Drive call
 * sites); End with 0 done deletes the session outright.
 */
class MovieDeckActivity : AppCompatActivity() {
    private data class DeckItem(val partId: String, val entry: JsonSeed.Entry, val exercise: Exercise)

    private enum class Phase { COUNTDOWN, MOVEMENT, BEAT }

    private lateinit var db: DbHelper
    private var sessionId: Long = -1
    private var phase = Phase.COUNTDOWN
    private var created = false

    private var deck: List<DeckItem> = emptyList()
    private val bag = ArrayDeque<String>()
    private var lastDealtId: String? = null

    private var currentItem: DeckItem? = null
    private var currentSeId: Long = -1
    private var doneCount = 0
    private val creditedParts = mutableSetOf<String>()
    /** Resolved movements in deal order: (short label, "done"/"skipped"). */
    private val resolved = mutableListOf<Pair<String, String>>()

    // Views (top row persistent; content rebuilt per phase/deal).
    private lateinit var root: LinearLayout
    private lateinit var topRow: LinearLayout
    private lateinit var content: LinearLayout
    private var topMicroTv: TextView? = null
    private var counterSecTv: TextView? = null
    private var counterPauseTv: TextView? = null
    private var counterRing: BendRingView? = null
    private var photoFrame: FrameLayout? = null
    private var sessionLineTv: TextView? = null
    private var photoLooper: PhotoLooper? = null
    private var elapsedText: String = "0:00"

    // Timing.
    private val handler = Handler(Looper.getMainLooper())
    private var counterTotalMs = 0L
    private var counterEndsAtMs = 0L
    private var counterRemainingMs = 0L
    private var counterPaused = false
    private var counterRunning = false
    private var elapsedRunning = false
    private var beatPending = false

    private val counterTick = object : Runnable {
        override fun run() {
            if (!counterRunning) return
            tickCounter()
            if (counterRunning) handler.postDelayed(this, COUNTER_TICK_MS)
        }
    }
    private val elapsedTick = object : Runnable {
        override fun run() {
            refreshElapsed()
            if (elapsedRunning) handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = DbHelper(this)

        // Resolve the deck (bundled JSON membership → DB rows). A deck
        // entry whose DB row is somehow missing (e.g. an ancient backup
        // restore) is dropped for this run rather than crashing it.
        val bySource = db.getAllExercises().associateBy { it.sourceId }
        deck = JsonSeed.deckEntries(this).mapNotNull { (partId, entry) ->
            bySource[entry.id]?.let { DeckItem(partId, entry, it) }
        }

        val wanted = intent.getLongExtra("sessionId", -1)
        val existing = db.inProgressSessions().firstOrNull { it.routineName == MovieDeck.ROUTINE_NAME }
        // v3.9.3 exclusivity (defense in depth — the chooser and the
        // At Home page already lock this door): never CREATE a deck
        // session while a gym workout is in progress. Resuming an
        // existing deck session (wanted / existing above) is never
        // blocked. The refusal is a -1 id, caught just below before
        // anything else runs — nothing is created.
        val gymActive = db.inProgressSessions().any { it.routineName != MovieDeck.ROUTINE_NAME }
        sessionId = when {
            wanted > 0 -> wanted
            existing != null -> existing.id
            gymActive -> -1L
            else -> {
                val wm = WeekManager.reconcile(this, db)
                db.createMovieSession(wm.weekNumber, wm.weekStart)
            }
        }
        if (sessionId < 0) {
            Toast.makeText(this, "Finish your current workout first", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val session = db.getSession(sessionId)
        if (session == null || session.completed) { finish(); return }
        db.ensureSessionStarted(sessionId)
        WorkoutTimer.ensureRunning(this, db, db.getSession(sessionId) ?: session)

        buildChrome()
        created = true

        if (deck.isEmpty()) {
            Toast.makeText(this, "Movie deck unavailable", Toast.LENGTH_SHORT).show()
            discardSession()
            return
        }

        // Rebuild run state from whatever is already in this session
        // (resume after leaving / process death).
        val rows = db.getSessionExercises(sessionId)
        creditedParts.addAll(db.getSessionParts(sessionId))
        for (row in rows) {
            when (db.effectiveStatus(row)) {
                "done" -> { doneCount++; resolved.add(shortName(row.name) to "done") }
                "skipped" -> resolved.add(shortName(row.name) to "skipped")
            }
        }
        val pending = rows.firstOrNull { db.effectiveStatus(it) == "pending" }
        if (pending != null) {
            // Re-deal the pending movement with a FRESH counter.
            val item = deck.firstOrNull { it.exercise.id == pending.originExerciseId }
                ?: deck.firstOrNull { it.entry.name.equals(pending.name, true) }
            topRow.visibility = View.VISIBLE
            if (item != null) {
                currentItem = item; currentSeId = pending.id; lastDealtId = item.entry.id
                showMovement()
            } else {
                // The pending row's entry left the deck (catalog change):
                // retire it as skipped and deal on.
                db.setSessionExerciseStatus(pending.id, "skipped")
                resolved.add(shortName(pending.name) to "skipped")
                deal()
            }
        } else if (rows.isNotEmpty()) {
            topRow.visibility = View.VISIBLE
            refillBag()
            deal()
        } else {
            showCountdown()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!created) return
        startElapsed()
        photoLooper?.start()
        if (phase == Phase.COUNTDOWN) {
            renderCountdown()
            handler.postDelayed(countRunnable, COUNT_STEP_MS)
        } else if (phase == Phase.MOVEMENT && !counterPaused && !beatPending) {
            counterEndsAtMs = SystemClock.uptimeMillis() + counterRemainingMs
            startCounterLoop()
        } else if (phase == Phase.BEAT) {
            deal()
        }
    }

    override fun onPause() {
        super.onPause()
        stopElapsed()
        photoLooper?.stop()
        if (phase == Phase.MOVEMENT && counterRunning) {
            counterRemainingMs = (counterEndsAtMs - SystemClock.uptimeMillis()).coerceAtLeast(0)
        }
        stopCounterLoop()
        handler.removeCallbacks(beatRunnable)
        handler.removeCallbacks(countRunnable)
        val s = db.getSession(sessionId)
        if (s != null && !s.completed) WorkoutTimer.fold(this, db, s)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        photoLooper?.stop()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back before the first deal (the countdown) discards the empty
        // session; later, back simply leaves — the session stays in
        // progress and Home's At Home card offers Resume.
        if (phase == Phase.COUNTDOWN && db.getSessionExercises(sessionId).isEmpty()) {
            discardSession()
            return
        }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---------- chrome (persistent top row + swappable content) ----------

    private fun buildChrome() {
        root = fitRoot()
        setContentView(root)
        topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, dp(4), 0, 0)
            visibility = View.GONE
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        titleCol.addView(bendScreenName("Movie Movements").apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        topMicroTv = bendMicro("MOVEMENT ${resolved.size + 1} · ENDLESS")
        titleCol.addView(topMicroTv)
        topRow.addView(titleCol)
        topRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val endTv = TextView(this).apply {
            text = "✕"; textSize = 17f; setTextColor(Theme.textTertiary)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            setOnClickListener { onEndTap() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        topRow.addView(endTv)
        root.addView(topRow)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(content)
    }

    // ---------- countdown (mock screen 2) ----------

    private var countStep = 0
    private val countRunnable = object : Runnable {
        override fun run() {
            countStep--
            if (countStep <= 0) {
                topRow.visibility = View.VISIBLE
                deal()
            } else {
                renderCountdown()
                handler.postDelayed(this, COUNT_STEP_MS)
            }
        }
    }

    /** v3.8.2: on a FRESH session the first 3 movements dealt are
     *  always movements that HAVE pictures — resolved from the actual
     *  catalog data at runtime (PhotoLib.assetForExercise, the same
     *  lookup the movement screen uses — never a hardcoded list; today
     *  the subset is 5: bodyweight-squat, incline-push-up,
     *  single-leg-calf-raise, arm-circles, shoulder-rolls). They are
     *  shuffled among themselves into an opening bag; from deal 4 the
     *  normal full-pool shuffle-bag takes over (refillBag's no-repeat
     *  check guards the 3→4 boundary via lastDealtId, and photo
     *  movements may naturally reappear). If the subset ever drops
     *  below 3, all of it opens the session. Resume never calls this:
     *  the rule applies to fresh starts only. */
    private fun prepareFreshBag() {
        val photoIds = deck.filter { PhotoLib.assetForExercise(this, it.exercise) != null }
            .map { it.entry.id }
        bag.clear()
        bag.addAll(photoIds.shuffled().take(3))
    }

    private fun showCountdown() {
        phase = Phase.COUNTDOWN
        countStep = 3
        prepareFreshBag()
        renderCountdown()
        handler.postDelayed(countRunnable, COUNT_STEP_MS)
    }

    private fun renderCountdown() {
        content.removeAllViews()
        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val circleWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(170), dp(170)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        circleWrap.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Theme.primary) }
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        })
        val num = makeText(countStep.toString(), 96f, true, Color.WHITE).apply {
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        circleWrap.addView(num)
        content.addView(circleWrap)
        num.scaleX = 0.7f; num.scaleY = 0.7f
        num.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(1.4f)).start()
        content.addView(makeText("Get ready…", 16f, true).apply {
            gravity = Gravity.CENTER
            (layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(22), 0, 0) }
        })
        content.addView(makeText("First movement is coming up.\nStand where you can see the TV.", 13f, false, Theme.textSecondary).apply {
            gravity = Gravity.CENTER
            (layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(5), 0, 0) }
        })
        val dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(20), 0, 0) }
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
        content.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#B9C9D8"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(dp(6), 0, dp(6), 0) }
        })
        content.addView(makeText(
            "🎲 The deck: ${deck.size} movements, shuffled — every round reshuffles,\nnever the same one twice in a row. There is no finish line;\nyou stop it whenever you're done.",
            12f, false, Theme.textSecondary).apply {
            gravity = Gravity.CENTER
            (layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(12), 0, 0) }
        })
    }

    // ---------- dealing ----------

    private fun refillBag() {
        val ids = deck.map { it.entry.id }.shuffled().toMutableList()
        // Never the same movement twice in a row, even across a reshuffle.
        if (ids.size > 1 && ids.first() == lastDealtId) {
            val tmp = ids[0]; ids[0] = ids[1]; ids[1] = tmp
        }
        bag.clear(); bag.addAll(ids)
    }

    private fun deal() {
        beatPending = false
        if (bag.isEmpty()) refillBag()
        val id = bag.removeFirst()
        lastDealtId = id
        val item = deck.first { it.entry.id == id }
        currentItem = item
        currentSeId = db.appendMovieExercise(sessionId, item.exercise)
        showMovement()
    }

    private fun showMovement() {
        val item = currentItem ?: return
        phase = Phase.MOVEMENT
        buildMovement()
        startCounter(item.entry.counterSec)
    }

    // ---------- movement screen (mock screen 3) ----------

    private fun buildMovement() {
        val item = currentItem ?: return
        val entry = item.entry
        photoLooper?.stop(); photoLooper = null
        content.removeAllViews()

        topMicroTv?.text = "MOVEMENT ${resolved.size + 1} · ENDLESS"

        // Circular photo (Bend circle) with the counter ring over it.
        val sizeDp = if (isCompactScreen()) 232 else 288
        val frame = bendCircleFrame(sizeDp)
        (frame.layoutParams as LinearLayout.LayoutParams).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, dp(10), 0, 0)
        }
        photoFrame = frame
        val photoAsset = PhotoLib.assetForExercise(this, item.exercise)
        val photoBmp = photoAsset?.let { PhotoLib.bitmap(this, it) }
        val muscles = DbHelper.parseMuscles(item.exercise.targetMuscles, item.exercise.name)
        if (photoBmp != null) {
            frame.addView(ImageView(this).apply {
                setImageBitmap(photoBmp); scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
            val photo2 = photoAsset?.let { PhotoLib.secondBitmap(this, it) }
            if (photo2 != null) {
                val iv2 = ImageView(this).apply {
                    setImageBitmap(photo2); scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                }
                frame.addView(iv2)
                val looper = PhotoLooper(iv2); photoLooper = looper; looper.start()
            }
        } else {
            frame.addView(BodyMapView(this, muscles, true).apply {
                layoutParams = FrameLayout.LayoutParams(dp(110), dp(140), Gravity.CENTER)
            })
        }
        counterRing = BendRingView(this).apply {
            strokeDp = 4f
            amberUnderSec = 10
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        frame.addView(counterRing)
        content.addView(frame)

        // Name, guide, first cue, watch line.
        content.addView(bendName(entry.name).apply {
            (layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(16), 0, 0) }
        })
        val guide = guideLabel(entry.targetReps)
        if (guide.isNotBlank()) {
            content.addView(bendMeta(guide).apply {
                (layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(4), 0, 0) }
            })
        }
        val cues = entry.cues.split(";").map { it.trim() }.filter { it.isNotBlank() }
        if (cues.isNotEmpty()) {
            content.addView(bendCue("\"${cues.first()}\"").apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, 0) }
            })
        }
        if (entry.url.isNotBlank()) {
            val watch = bendWatchLine()
            watch.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(2), 0, 0) }
            watch.isClickable = true
            watch.setOnClickListener { openUrl(this, entry.shortUrl.takeIf { it.isNotBlank() } ?: entry.url) }
            watch.setOnLongClickListener { openUrl(this, entry.url); true }
            content.addView(watch)
        }

        // Huge counter.
        counterSecTv = bendHuge()
        counterSecTv?.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), 0, 0) }
        content.addView(counterSecTv)

        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // Controls: Skip / Done / Pause round controls.
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(18), 0, 0) }
        }
        val simple = SimpleMode.isOn(this)
        val sideDp = if (simple) 64 else 52
        val sideSp = if (simple) 22f else 18f
        val heroDp = if (simple) 80 else 70
        val heroSp = if (simple) 30f else 25f
        val ctlLabelSp = if (simple) 11.5f else 10.5f
        controls.addView(bendControl(bendSoftCircle("⏭", sideDp, sideSp), "Skip", labelSp = ctlLabelSp) { skipCurrent() }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), 0, dp(12), 0) }
        })
        controls.addView(bendControl(bendHeroCircle("✓", Theme.success, heroDp, heroSp), "Done", labelSp = ctlLabelSp) { completeCurrent() }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), 0, dp(12), 0) }
        })
        val pauseCircle = bendSoftCircle("⏸", sideDp, sideSp)
        counterPauseTv = pauseCircle
        controls.addView(bendControl(pauseCircle, "Pause", labelSp = ctlLabelSp) { toggleCounterPause() }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), 0, dp(12), 0) }
        })
        content.addView(controls)

        sessionLineTv = makeText("", 12.5f, false, Theme.textSecondary).apply {
            gravity = Gravity.CENTER
            (layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(14), 0, 0) }
        }
        content.addView(sessionLineTv)
        renderProgress()
    }

    /** Session footline only (r4: no deck chips on this screen). */
    private fun renderProgress() {
        val skipped = resolved.count { it.second == "skipped" }
        sessionLineTv?.text = "$doneCount done · $skipped skipped · $elapsedText so far"
    }

    private fun shortName(name: String): String {
        val base = name.replace(Regex("\\(.*?\\)"), "").trim()
        if (base.length <= 16) return base
        val words = base.split(" ")
        val two = words.take(2).joinToString(" ")
        return if (two.length in 1..16) two else base.substring(0, 16).trim()
    }

    /** "~15 reps" for a plain count, "~60 steps" / "~10 ea" verbatim guides. */
    private fun guideLabel(targetReps: String): String {
        val t = targetReps.trim()
        if (t.contains("sec")) return t
        return "~" + t + if (Regex("^\\d+(-\\d+)?$").matches(t)) " reps" else ""
    }

    private fun guideInt(targetReps: String): Int =
        Regex("\\d+").find(targetReps)?.value?.toIntOrNull() ?: 0

    // ---------- counter engine ----------

    private fun startCounter(sec: Int) {
        counterTotalMs = sec * 1000L
        counterRemainingMs = counterTotalMs
        counterPaused = false
        counterPauseTv?.text = "⏸"
        counterEndsAtMs = SystemClock.uptimeMillis() + counterRemainingMs
        updateCounterUi()
        startCounterLoop()
    }

    private fun startCounterLoop() {
        if (counterRunning) return
        counterRunning = true
        handler.post(counterTick)
    }

    private fun stopCounterLoop() {
        counterRunning = false
        handler.removeCallbacks(counterTick)
    }

    private fun tickCounter() {
        if (phase != Phase.MOVEMENT) { stopCounterLoop(); return }
        counterRemainingMs = (counterEndsAtMs - SystemClock.uptimeMillis()).coerceAtLeast(0)
        if (counterRemainingMs <= 0L) {
            stopCounterLoop()
            completeCurrent()
            return
        }
        updateCounterUi()
    }

    private fun updateCounterUi() {
        val remainSec = ((counterRemainingMs + 999) / 1000).toInt()
        counterSecTv?.text = WorkoutTimer.formatClock(remainSec)
        val frac = if (counterTotalMs > 0) counterRemainingMs.toFloat() / counterTotalMs else 0f
        counterRing?.remainingSec = remainSec
        counterRing?.setFraction(frac)
        val late = counterRemainingMs in 1..10_000
        counterSecTv?.setTextColor(if (late) 0xFFD97706.toInt() else Theme.textPrimary)
    }

    private fun toggleCounterPause() {
        if (phase != Phase.MOVEMENT || beatPending) return
        if (counterPaused) {
            counterPaused = false
            counterPauseTv?.text = "⏸"
            counterEndsAtMs = SystemClock.uptimeMillis() + counterRemainingMs
            startCounterLoop()
        } else {
            counterRemainingMs = (counterEndsAtMs - SystemClock.uptimeMillis()).coerceAtLeast(0)
            counterPaused = true
            counterPauseTv?.text = "▶"
            stopCounterLoop()
            updateCounterUi()
        }
    }

    // ---------- resolve / deal / end ----------

    private val beatRunnable = Runnable {
        beatPending = false
        if (phase == Phase.BEAT) deal()
    }

    /** Done (early tap or counter expiry): log the movement, credit its
     *  part, show the success beat, then deal the next one. */
    private fun completeCurrent() {
        if (phase != Phase.MOVEMENT || currentSeId < 0) return
        val item = currentItem ?: return
        stopCounterLoop()
        phase = Phase.BEAT
        beatPending = true
        val set = db.getSets(currentSeId).firstOrNull()
        if (set != null) db.updateSet(set.copy(reps = guideInt(item.entry.targetReps).toString(), isDone = true))
        db.setSessionExerciseStatus(currentSeId, "done")
        if (item.partId !in creditedParts) {
            db.insertSessionPart(sessionId, item.partId)
            creditedParts.add(item.partId)
        }
        doneCount++
        resolved.add(shortName(item.entry.name) to "done")
        buzz()
        showBeat()
        renderProgress()
        handler.postDelayed(beatRunnable, BEAT_MS)
    }

    private fun showBeat() {
        val frame = photoFrame ?: return
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0x6615803D)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val badge = TextView(this).apply {
            text = "✓"; textSize = 54f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Theme.success) }
            layoutParams = FrameLayout.LayoutParams(dp(92), dp(92), Gravity.CENTER)
        }
        overlay.addView(badge)
        frame.addView(overlay)
        badge.scaleX = 0.5f; badge.scaleY = 0.5f
        badge.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(OvershootInterpolator(1.6f)).start()
        counterSecTv?.text = "✓"
    }

    private fun skipCurrent() {
        if (phase != Phase.MOVEMENT || currentSeId < 0) return
        val item = currentItem ?: return
        stopCounterLoop()
        db.setSessionExerciseStatus(currentSeId, "skipped")
        resolved.add(shortName(item.entry.name) to "skipped")
        deal()
    }

    private fun onEndTap() {
        if (doneCount > 0) finishSession() else discardSession()
    }

    /** The standard finish path, identical to the hub's: timer
     *  finalize, mark completed, Drive + Health Connect call sites,
     *  celebrating Summary. */
    private fun finishSession() {
        handler.removeCallbacksAndMessages(null)
        stopCounterLoop(); stopElapsed()
        val fresh = db.getSession(sessionId)
        if (fresh != null) WorkoutTimer.finish(this, db, fresh)
        db.setSessionCompleted(sessionId, true)
        DriveBackup.backupSilently(this, db)
        HealthSync.syncSessionIfEnabled(this, db, sessionId)
        startActivity(Intent(this, SummaryActivity::class.java).apply {
            putExtra("sessionId", sessionId); putExtra("celebrate", true)
        })
        finish()
    }

    private fun discardSession() {
        handler.removeCallbacksAndMessages(null)
        stopCounterLoop(); stopElapsed()
        WorkoutTimer.clear(this, sessionId)
        db.deleteSession(sessionId)
        Toast.makeText(this, "Movie session discarded", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ---------- elapsed chip ----------

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
        val s = db.getSession(sessionId) ?: return
        elapsedText = WorkoutTimer.formatClock(WorkoutTimer.liveElapsedSec(this, s))
        renderProgress()
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
        private const val COUNTER_TICK_MS = 200L
        private const val BEAT_MS = 1200L
    }
}
