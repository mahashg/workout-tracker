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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

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
    private var elapsedTv: TextView? = null
    private var counterSecTv: TextView? = null
    private var counterLabelTv: TextView? = null
    private var counterPauseTv: TextView? = null
    private var counterFill: View? = null
    private var counterSpacer: View? = null
    private var photoFrame: FrameLayout? = null
    private var sessionLineTv: TextView? = null
    private var chipsWrap: LinearLayout? = null
    private var photoLooper: PhotoLooper? = null

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
        sessionId = when {
            wanted > 0 -> wanted
            existing != null -> existing.id
            else -> {
                val wm = WeekManager.reconcile(this, db)
                db.createMovieSession(wm.weekNumber, wm.weekStart)
            }
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
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40))
            visibility = View.GONE
        }
        topRow.addView(makeText("🎬 Movie Movements", 15f, true).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        topRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        elapsedTv = makeText("", 13f, true, Theme.textSecondary).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        topRow.addView(elapsedTv)
        val endChip = TextView(this).apply {
            text = "✕ End"; textSize = 12f; setTextColor(Color.parseColor("#991B1B"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply { setColor(Color.parseColor("#FEE2E2")); cornerRadius = dp(999).toFloat() }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            setOnClickListener { onEndTap() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(10), 0, 0, 0) }
        }
        topRow.addView(endChip)
        root.addView(topRow)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(content)
    }

    private fun rectBg(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
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

    private fun showCountdown() {
        phase = Phase.COUNTDOWN
        countStep = 3
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

        // Counter band — pinned at the top, always visible.
        val band = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rectBg(Color.parseColor("#16324F"), 14)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), 0, 0) }
        }
        val bandRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        counterSecTv = TextView(this).apply {
            textSize = 30f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setFontFeatureSettings("tnum")
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, 0, 0)
        }
        bandRow.addView(counterSecTv)
        counterLabelTv = TextView(this).apply {
            text = "${entry.name.uppercase(Locale.US)} · COUNTER"
            textSize = 11f; setTextColor(0xD9FFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f; gravity = Gravity.CENTER
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(10), 0, dp(6), 0) }
        }
        bandRow.addView(counterLabelTv)
        counterPauseTv = TextView(this).apply {
            text = "⏸"; textSize = 19f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            isClickable = true
            setOnClickListener { toggleCounterPause() }
        }
        bandRow.addView(counterPauseTv)
        band.addView(bandRow)
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(0x40FFFFFF); cornerRadius = dp(99).toFloat() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(7)).apply { setMargins(0, dp(8), 0, 0) }
        }
        counterFill = View(this).apply {
            background = GradientDrawable().apply { setColor(Color.parseColor("#A3E635")); cornerRadius = dp(99).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        counterSpacer = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0f) }
        track.addView(counterFill); track.addView(counterSpacer)
        band.addView(track)
        content.addView(band)

        // Photo band (flexes; CENTER_CROP; body map when no photo).
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(0, dp(10), 0, 0) }
            roundedClip(18)
        }
        photoFrame = frame
        val photoAsset = PhotoLib.assetForExercise(this, item.exercise)
        val photoBmp = photoAsset?.let { PhotoLib.bitmap(this, it) }
        val muscles = DbHelper.parseMuscles(item.exercise.targetMuscles, item.exercise.name)
        if (photoBmp != null) {
            frame.setBackgroundColor(Theme.surfaceVariant)
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
            frame.addView(View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(96), Gravity.BOTTOM)
            })
        } else {
            frame.background = roundedBg("#EDF2F7", 18)
            frame.addView(BodyMapView(this, muscles, true).apply {
                layoutParams = FrameLayout.LayoutParams(dp(110), dp(140), Gravity.CENTER_VERTICAL or Gravity.END).apply { setMargins(0, 0, dp(16), 0) }
            })
        }
        // Tag pill top-left over the band (both photo and body-map forms).
        frame.addView(TextView(this).apply {
            text = "🏠 HOME · ${BodyParts.name(item.partId).uppercase(Locale.US)}"
            textSize = 11f; setTextColor(Color.parseColor("#92600A"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply { setColor(0xF2FFF1CF.toInt()); cornerRadius = dp(999).toFloat() }
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(dp(10), dp(10), 0, 0) }
        })
        // Name + last-time on the bottom of the band.
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(12))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        val onPhoto = photoBmp != null
        overlay.addView(makeText(entry.name, 24f, true, if (onPhoto) Color.WHITE else Theme.textPrimary).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        })
        overlay.addView(makeText(lastTimeLine(item), 13f, true, if (onPhoto) 0xE6FFFFFF.toInt() else Theme.primary).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(2), 0, 0)
        })
        frame.addView(overlay)
        content.addView(frame)

        // Target card: the rep guide big, the first cues as guidance.
        val target = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rectBg(Theme.surface, 14, Theme.stroke, 1)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(10), 0, 0) }
        }
        target.addView(makeText(guideLabel(entry.targetReps), 24f, true, Theme.primary).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        val cues = entry.cues.split(";").map { it.trim() }.filter { it.isNotBlank() }.take(2)
        target.addView(makeText(
            if (cues.isEmpty()) "" else cues.joinToString(". ") + ".",
            12.5f, false, Theme.textSecondary).apply {
            maxLines = 3
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(12), 0, 0, 0) }
        })
        content.addView(target)

        // Actions: ✓ Done early (green, larger) + Skip →.
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { setMargins(0, dp(10), 0, 0) }
        }
        val doneBtn = makeButton("✓ Done early") { completeCurrent() }
        doneBtn.background = GradientDrawable().apply { setColor(Theme.success); cornerRadius = dp(14).toFloat() }
        doneBtn.layoutParams = LinearLayout.LayoutParams(0, dp(56), 1.4f).apply { setMargins(0, 0, dp(5), 0) }
        actions.addView(doneBtn)
        val skipBtn = makeSecondaryButton("Skip →") { skipCurrent() }
        tightButton(skipBtn, 56, 14, primary = false)
        skipBtn.layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(5), 0, 0, 0) }
        actions.addView(skipBtn)
        content.addView(actions)

        sessionLineTv = makeText("", 12.5f, false, Theme.textSecondary).apply {
            gravity = Gravity.CENTER
            (layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(10), 0, 0) }
        }
        content.addView(sessionLineTv)
        chipsWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), 0, 0) }
        }
        content.addView(chipsWrap)
        renderProgress()
    }

    /** Session line + backwards-looking deck chips (the only "list" —
     *  it never shows what is coming). */
    private fun renderProgress() {
        sessionLineTv?.text = "$doneCount done this session · the deck keeps dealing 🎲"
        val wrap = chipsWrap ?: return
        wrap.removeAllViews()
        data class Chip(val label: String, val kind: String) // done | skipped | now | note
        val chips = mutableListOf<Chip>()
        // During the success beat the current movement is already in
        // `resolved` as done — the "now" chip only exists mid-movement.
        val all = resolved.map { Chip(it.first, it.second) } +
            if (phase == Phase.MOVEMENT) listOf(Chip(shortName(currentItem?.entry?.name ?: ""), "now")) else emptyList()
        val shown = if (all.size > 12) listOf(Chip("+${all.size - 11} earlier", "note")) + all.takeLast(11) else all
        chips.addAll(shown)
        val availDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt() -
            if (isCompactScreen()) 28 else 40
        var row = newChipRow()
        var used = 0
        for (chip in chips) {
            val w = 22 + (chip.label.length * 6.4).toInt()
            if (used > 0 && used + w > availDp) {
                wrap.addView(row)
                row = newChipRow(); used = 0
            }
            row.addView(chipView(chip.label, chip.kind))
            used += w + 6
        }
        wrap.addView(row)
    }

    private fun newChipRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(3), 0, dp(3)) }
    }

    private fun chipView(label: String, kind: String): TextView {
        val (bg, ink, suffix) = when (kind) {
            "done" -> Triple(Color.parseColor("#DCFCE7"), Color.parseColor("#166534"), " ✓")
            "skipped" -> Triple(Color.parseColor("#E8EEF4"), Color.parseColor("#64748B"), " ✕")
            "now" -> Triple(Theme.primary, Color.WHITE, " · now")
            else -> Triple(Color.parseColor("#E8EEF4"), Color.parseColor("#64748B"), "")
        }
        return TextView(this).apply {
            text = label + suffix; textSize = 10.5f; setTextColor(ink)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(999).toFloat() }
            setPadding(dp(9), dp(4), dp(9), dp(4)); maxLines = 1
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), 0, dp(3), 0) }
        }
    }

    private fun shortName(name: String): String {
        val base = name.replace(Regex("\\(.*?\\)"), "").trim()
        if (base.length <= 16) return base
        val words = base.split(" ")
        val two = words.take(2).joinToString(" ")
        return if (two.length in 1..16) two else base.substring(0, 16).trim()
    }

    private fun lastTimeLine(item: DeckItem): String {
        val last = db.lastPerformedForExercise(item.exercise.name, sessionId) ?: return "New movement"
        val reps = last.sets.firstOrNull()?.reps?.filter { it.isDigit() }?.toIntOrNull()
        val day = try {
            val cal = Calendar.getInstance()
            cal.time = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(last.date)!!
            SimpleDateFormat("EEE", Locale.US).format(cal.time)
        } catch (e: Exception) { "" }
        return "Last: ${item.entry.counterSec} sec · ~${reps ?: guideInt(item.entry.targetReps)} reps · $day"
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
        counterFill?.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, frac.coerceIn(0f, 1f))
        counterSpacer?.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (1f - frac).coerceIn(0f, 1f))
        val late = counterRemainingMs in 1..10_000
        (counterFill?.background as? GradientDrawable)?.setColor(
            Color.parseColor(if (late) "#F59E0B" else "#A3E635"))
        counterSecTv?.setTextColor(if (late) Color.parseColor("#FBBF24") else Color.WHITE)
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
        counterLabelTv?.text = "DONE — NEXT IN A MOMENT"
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
        elapsedTv?.text = "⏱ " + WorkoutTimer.formatClock(WorkoutTimer.liveElapsedSec(this, s))
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
