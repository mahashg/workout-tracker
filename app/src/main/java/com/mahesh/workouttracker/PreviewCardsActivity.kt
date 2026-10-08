package com.mahesh.workouttracker

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs

/**
 * Read-only preview of a routine's exercise cards (v2.4, Mahesh request).
 *
 * STRICTLY READ-ONLY: this activity never creates a session and never writes
 * to the database or the timer preferences. It builds its deck purely from
 * template exercises (db.getExercises) plus read-only last-performed lookups.
 * Logging is only possible after tapping Start Workout, which hands off to
 * the normal session flow (WorkoutSectionsActivity).
 */
class PreviewCardsActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var routineId: Long = -1
    private var sectionType: String? = null
    private var deck: List<Exercise> = emptyList()
    private var index: Int = 0
    private val howToExpanded = mutableSetOf<Long>()
    private var howToDefaultApplied = false

    // --- Swipe-to-browse state (same drag feel as card mode, browse only) ---
    private var cardWrap: FrameLayout? = null
    private var cardSurface: LinearLayout? = null
    private var tintOverlay: View? = null
    private var nextLabel: TextView? = null
    private var prevLabel: TextView? = null
    private var peekNear: View? = null
    private var peekFar: View? = null
    private var draggingCard = false
    private var cardAnimating = false
    private var thresholdHapticDone = false
    private var animateEntryOnNextRender = false
    private var downX = 0f; private var downY = 0f

    /** Strict commit distance for horizontal browse swipes: a light nudge never commits. */
    private fun commitThresholdX(v: View): Float = maxOf(0.36f * v.width, dp(130).toFloat())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); db = DbHelper(this)
        routineId = intent.getLongExtra("routineId", -1)
        sectionType = intent.getStringExtra("sectionType")
        if (routineId < 0) { finish(); return }
        loadDeck()
        render()
    }

    private fun loadDeck() {
        val all = db.getExercises(routineId) // template exercises only - never session rows
        val st = sectionType
        deck = if (st.isNullOrBlank()) {
            // All exercises: warm-up, then main, then cool-down; template order inside each.
            val rank = mapOf("warmup" to 0, "main" to 1, "stretch" to 2)
            all.sortedWith(compareBy({ rank[it.type.lowercase()] ?: 1 }, { it.sortOrder }, { it.id }))
        } else {
            all.filter { it.type.equals(st, true) }
        }
        if (index !in deck.indices) index = 0
    }

    private fun current(): Exercise? = deck.getOrNull(index)

    private fun planSets(ex: Exercise): Int =
        if (ex.type.equals("Warmup", true) || ex.type.equals("Stretch", true)) 1 else maxOf(1, ex.defaultSets)

    private fun doItLine(ex: Exercise): String {
        val n = planSets(ex)
        val reps = ex.targetReps.trim()
        return if (reps.contains("min") || reps.contains("sec") || reps.contains("/")) "Do $n set${if (n > 1) "s" else ""} of $reps"
        else "Do $n sets of $reps"
    }

    private fun startButtonLabel(): String {
        val routine = db.getRoutine(routineId) ?: return "Start Workout"
        val state = WeekManager.reconcile(this, db)
        val sessionsWeek = db.sessionsInWeek(state.weekStart)
        val inProg = sessionsWeek.firstOrNull { it.routineId == routine.id && !it.completed } ?: db.inProgressSessions().firstOrNull { it.routineId == routine.id }
        val completed = sessionsWeek.firstOrNull { it.routineId == routine.id && it.completed }
        return when { inProg != null -> "Resume Workout"; completed != null -> "Start Again (new session)"; else -> "Start Workout" }
    }

    /** The ONLY write path reachable from preview - the exact start flow WorkoutDetailActivity uses. */
    private fun startWorkout() {
        val routine = db.getRoutine(routineId) ?: return
        val state = WeekManager.reconcile(this, db)
        val sessionsWeek = db.sessionsInWeek(state.weekStart)
        val inProg = sessionsWeek.firstOrNull { it.routineId == routine.id && !it.completed } ?: db.inProgressSessions().firstOrNull { it.routineId == routine.id }
        val sid = if (inProg != null) inProg.id else db.createSession(routine, state.weekNumber, state.weekStart)
        startActivity(Intent(this, WorkoutSectionsActivity::class.java).apply { putExtra("sessionId", sid) })
        finish()
    }

    private fun render() {
        val routine = db.getRoutine(routineId) ?: run { finish(); return }
        val root = fitRoot(); setContentView(root)
        val st = sectionType
        root.addView(topBar(routine.name, if (st.isNullOrBlank()) "Preview cards" else "${sectionLabel(st)} preview"))

        // Unmistakable preview-mode banner (no session is running here).
        val banner = cardLayout("#2563EB")
        banner.setPadding(dp(12), dp(6), dp(12), dp(6))
        val bRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bRow.addView(iconView(R.drawable.ic_eye, 22, Theme.primary))
        bRow.addView(makeText("  Preview — Start Workout to log", 15f, true, Theme.primary))
        banner.addView(bRow)
        banner.addView(caption("Browsing only — nothing is recorded until you start the workout."))
        root.addView(banner)

        if (deck.isEmpty()) {
            root.addView(makeText("No exercises yet — add some in Routines.", 16f, false, Theme.textSecondary))
            root.addView(primaryButtonWithIcon(startButtonLabel(), R.drawable.ic_play) { startWorkout() })
            root.addView(makeSecondaryButton("Back") { finish() })
            return
        }
        val ex = current()!!

        // Progress header
        val top = cardLayout(when (ex.type.lowercase()) { "warmup" -> "#D97706"; "stretch" -> "#0D9488"; else -> "#2563EB" })
        top.setPadding(dp(12), dp(6), dp(12), dp(6))
        top.addView(makeText("${sectionLabel(ex.type)}  •  Card ${index + 1} of ${deck.size}", 14f, true))
        top.addView(hProgress(deck.size, index, sectionColor(ex.type)))
        root.addView(top)

        // ---- Card deck: ONE moving card over two static peek cards (same feel as card mode) ----
        val deckBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(0, dp(8), 0, dp(6)) }
            clipChildren = false; clipToPadding = false
        }
        peekFar = View(this).apply {
            background = deckCardDrawable(); alpha = 0.45f; scaleX = 0.92f; scaleY = 0.92f; translationY = dp(22).toFloat()
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        peekNear = View(this).apply {
            background = deckCardDrawable(); alpha = 0.7f; scaleX = 0.96f; scaleY = 0.96f; translationY = dp(12).toFloat()
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        deckBox.addView(peekFar); deckBox.addView(peekNear)

        val wrap = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            clipChildren = false; clipToPadding = false
        }
        val dragCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = deckCardDrawable()
            elevation = dp(8).toFloat()
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        cardSurface = dragCard
        dragCard.addView(View(this).apply { setBackgroundColor(sectionColor(ex.type)); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply { setMargins(0, 0, 0, dp(12)) } })

        val muscles = DbHelper.parseMuscles(ex.targetMuscles, ex.name)
        val essRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        essRow.addView(BodyMapView(this, muscles, true).apply { layoutParams = LinearLayout.LayoutParams(dp(76), dp(94)) })
        val essInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(10), 0, 0, 0) }
        essInfo.addView(equipmentBadge(ex.equipment))
        essInfo.addView(makeText(ex.name, 20f, true))
        if (ex.variation.isNotBlank()) essInfo.addView(makeText(ex.variation, 12f, false, Theme.textSecondary))
        essInfo.addView(makeText("Do it: ${doItLine(ex)}", 14f, true, Color.parseColor("#92400E")))
        // Plan dots: neutral/hollow (no progress in preview)
        essInfo.addView(makeText((1..planSets(ex)).joinToString("  ") { "○" }, 18f, true, Theme.textSecondary))
        essInfo.addView(makeText("Planned sets — preview only", 12f, false, Theme.textSecondary))
        essRow.addView(essInfo)
        dragCard.addView(essRow)

        // Last done (read-only lookup across past sessions)
        val unit = WeekManager.unit(this)
        val last = db.lastPerformedForExercise(ex.name, -1)
        if (last != null) {
            val summary = last.sets.joinToString(", ") { s -> if (s.isBodyweight) "BW x${s.reps}" else "${s.weight} $unit x ${s.reps}" }
            val effortBit = if (last.effort.isNotBlank()) " • ${last.effort} — ${Beginner.effortSuggestion(last.effort, unit)}" else ""
            dragCard.addView(makeText("Last done: ${DateUtil.display(last.date)} • $summary$effortBit", 12f, false, Theme.textSecondary))
        } else dragCard.addView(makeText("First time — no previous record. Start light.", 12f, false, Theme.textSecondary))

        // "Show me how" - same collapsible educational content as session cards.
        if (!howToDefaultApplied) { howToDefaultApplied = true; howToExpanded.add(ex.id) }
        val howExpanded = howToExpanded.contains(ex.id)
        val howToggle = makeSecondaryButton(if (howExpanded) "Hide how-to  ▴" else "Show me how  ▾") {
            if (howToExpanded.contains(ex.id)) howToExpanded.remove(ex.id) else howToExpanded.add(ex.id)
            render()
        }
        (howToggle.layoutParams as LinearLayout.LayoutParams).height = dp(48)
        dragCard.addView(howToggle)
        if (howExpanded) {
            // Compact how-to: fits INSIDE the fixed-height card (no page scroll).
            // Posture bullets capped at 4, cues at 3; any remainder fades to "…".
            val compact = isCompactScreen()
            val how = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), 0) }
            if (ex.youtubeUrl.isNotBlank()) {
                val vid = primaryButtonWithIcon("Watch Form Video", R.drawable.ic_play) { openUrl(this, ex.youtubeUrl) }
                (vid.layoutParams as LinearLayout.LayoutParams).height = dp(48)
                how.addView(vid)
            }
            how.addView(makeText("What you'll feel: ${musclesLabel(muscles)}", 12f, false, Color.parseColor("#0369A1")))
            var truncated = false
            if (Beginner.beginnerMode(this)) {
                val posture = ex.postureCheck.ifBlank { DbHelper.postureForName(ex.name, ex.type) }
                if (posture.isNotBlank()) {
                    val items = posture.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                    how.addView(makeText("Check your posture:", 13f, true, Color.parseColor("#15803D")))
                    for (c in items.take(4)) how.addView(makeText("☐  $c", 12f, false).apply { setLineSpacing(0f, 0.95f) })
                    if (items.size > 4) truncated = true
                }
                val cues = ex.cues.ifBlank { DbHelper.cuesForName(ex.name, ex.equipment, ex.type) }
                if (cues.isNotBlank()) {
                    val items = cues.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                    val cap = if (compact) 2 else 3
                    how.addView(makeText("Form cues:", 12f, true, Color.parseColor("#0369A1")))
                    for (c in items.take(cap)) how.addView(makeText("•  $c", 12f, false, Theme.textSecondary).apply { setLineSpacing(0f, 0.95f) })
                    if (items.size > cap) truncated = true
                }
                if (ex.equipment == "Machine" || ex.equipment == "Dumbbell" || ex.equipment == "Kettlebell") how.addView(makeText("Start light: use the lightest weight that feels easy first.", 11f, false, Color.parseColor("#92400E")))
            } else {
                val cues = ex.cues.ifBlank { DbHelper.cuesForName(ex.name, ex.equipment, ex.type) }
                if (cues.isNotBlank()) {
                    val items = cues.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                    val cap = if (compact) 2 else 3
                    how.addView(makeText("Form cues:", 12f, true, Color.parseColor("#0369A1")))
                    for (c in items.take(cap)) how.addView(makeText("•  $c", 12f, false, Theme.textSecondary).apply { setLineSpacing(0f, 0.95f) })
                    if (items.size > cap) truncated = true
                }
            }
            if (truncated) how.addView(makeText("…", 12f, true, Theme.textTertiary))
            dragCard.addView(how)
        }
        dragCard.addView(makeText("Swipe:  ‹ Prev  •  Next ›   (or use buttons below)", 11f, false, Theme.textSecondary))

        wrap.addView(dragCard)
        val tint = View(this).apply {
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20).toFloat() }
            alpha = 0f; isClickable = false
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        tintOverlay = tint; wrap.addView(tint)
        fun dirLabel(text: String, color: String, gravity: Int, rot: Float): TextView {
            return TextView(this).apply {
                this.text = text; textSize = 26f; setTextColor(Color.parseColor(color)); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                setPadding(dp(12), dp(6), dp(12), dp(6)); alpha = 0f; rotation = rot; isClickable = false
                background = GradientDrawable().apply { setColor(0xE6F7F9FB.toInt()); cornerRadius = dp(10).toFloat(); setStroke(dp(2), Color.parseColor(color)) }
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, gravity).apply { setMargins(dp(14), dp(14), dp(14), dp(14)) }
            }
        }
        // Browse-only labels: left swipe (card moves left) -> NEXT (top-right), right swipe -> PREV (top-left).
        prevLabel = dirLabel("‹ PREV", "#2563EB", Gravity.TOP or Gravity.START, 10f)
        nextLabel = dirLabel("NEXT ›", "#2563EB", Gravity.TOP or Gravity.END, -10f)
        wrap.addView(prevLabel); wrap.addView(nextLabel)
        cardWrap = wrap
        deckBox.addView(wrap)
        root.addView(deckBox)

        // Drag handling: only reacts past touch slop, so taps (e.g. Watch Form Video) never swipe.
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        wrap.setOnTouchListener { v, ev ->
            if (cardAnimating) return@setOnTouchListener true
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; draggingCard = false; thresholdHapticDone = false
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.pivotX = v.width / 2f; v.pivotY = v.height.toFloat()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (!draggingCard && kotlin.math.hypot(dx, dy) > slop) draggingCard = true
                    if (draggingCard) {
                        v.translationX = dx; v.translationY = dy
                        v.rotation = (dx / v.width.coerceAtLeast(1) * 7f).coerceIn(-8f, 8f)
                        updateDragFeedback(v, dx, commitThresholdX(v))
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    val wasDragging = draggingCard; draggingCard = false
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    if (wasDragging) {
                        val tx = commitThresholdX(v)
                        when {
                            // Same strict commit rule as card mode: distance only, dominant axis must win clearly.
                            abs(dx) >= tx && abs(dx) >= 1.3f * abs(dy) -> if (dx < 0) animateExitThen(v, -1) { step(1) } else animateExitThen(v, 1) { step(-1) }
                            else -> snapCardBack(v)
                        }
                    }
                    true
                }
                else -> false
            }
        }
        if (animateEntryOnNextRender) {
            animateEntryOnNextRender = false
            wrap.alpha = 0f; wrap.scaleX = 0.96f; wrap.scaleY = 0.96f; wrap.translationY = dp(18).toFloat()
            wrap.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        }

        // Browse buttons (every gesture has a button).
        val wrapRef = wrap
        val navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val prevBtn = makeSecondaryButton("‹ Previous") { if (index > 0) animateExitThen(wrapRef, 1) { step(-1) } }
        (prevBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48) }
        val nextBtn = makeSecondaryButton("Next ›") { if (index < deck.size - 1) animateExitThen(wrapRef, -1) { step(1) } }
        (nextBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48) }
        navRow.addView(prevBtn); navRow.addView(nextBtn)
        root.addView(navRow)
        deck.getOrNull(index + 1)?.let { root.addView(makeText("Next up: ${it.name}", 12f, false, Color.parseColor("#0369A1"))) }
        deck.getOrNull(index - 1)?.let { root.addView(makeText("Previous: ${it.name}", 12f, false, Theme.textSecondary)) }

        // Start from preview - the only place a session can be created from here.
        val startBtn = primaryButtonWithIcon(startButtonLabel(), R.drawable.ic_play) { startWorkout() }
        (startBtn.layoutParams as LinearLayout.LayoutParams).height = dp(48)
        root.addView(startBtn)
        val backBtn = makeSecondaryButton("Back") { finish() }
        (backBtn.layoutParams as LinearLayout.LayoutParams).height = dp(48)
        root.addView(backBtn)
    }

    private fun step(dir: Int) {
        val nxt = (index + dir).coerceIn(0, (deck.size - 1).coerceAtLeast(0))
        if (nxt == index) { render(); return }
        index = nxt
        render()
    }

    // ---- Drag feedback + animations (browse wording of the card-mode machinery) ----

    private fun updateDragFeedback(v: View, dx: Float, tx: Float) {
        val progress = (if (tx > 0) abs(dx) / tx else 0f).coerceIn(0f, 1f)
        nextLabel?.alpha = if (dx < 0) progress else 0f
        prevLabel?.alpha = if (dx > 0) progress else 0f
        val active = if (dx < 0) nextLabel else prevLabel
        active?.let { val s = 0.92f + 0.13f * progress; it.scaleX = s; it.scaleY = s }
        cardSurface?.background = deckCardDrawable(if (progress > 0.02f) Color.parseColor("#2563EB") else null)
        tintOverlay?.let { (it.background as? GradientDrawable)?.setColor(Color.parseColor("#2563EB")); it.alpha = 0.13f * progress }
        peekNear?.let { val s = 0.96f + 0.03f * progress; it.scaleX = s; it.scaleY = s; it.translationY = dp(12).toFloat() - dp(5) * progress; it.alpha = 0.7f + 0.3f * progress }
        if (progress >= 1f && !thresholdHapticDone) {
            thresholdHapticDone = true
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    private fun snapCardBack(v: View) {
        v.animate().translationX(0f).translationY(0f).rotation(0f)
            .setDuration(220).setInterpolator(OvershootInterpolator(0.8f)).start()
        fadeDragFeedback()
    }

    private fun fadeDragFeedback() {
        nextLabel?.animate()?.alpha(0f)?.setDuration(140)?.start()
        prevLabel?.animate()?.alpha(0f)?.setDuration(140)?.start()
        tintOverlay?.animate()?.alpha(0f)?.setDuration(140)?.start()
        peekNear?.animate()?.scaleX(0.96f)?.scaleY(0.96f)?.translationY(dp(12).toFloat())?.alpha(0.7f)?.setDuration(180)?.start()
        cardSurface?.background = deckCardDrawable()
    }

    /** Commit exit: card flies off in its swipe direction, THEN the neighbour card is shown. */
    private fun animateExitThen(v: View, dir: Int, after: () -> Unit) {
        if (cardAnimating) return
        // At the ends of the deck there is nowhere to go: just settle back.
        if ((dir < 0 && index >= deck.size - 1) || (dir > 0 && index <= 0)) { snapCardBack(v); return }
        cardAnimating = true
        val targetX = dir * (v.width + dp(80)).toFloat()
        v.animate().translationX(targetX).rotation(dir * 8f).alpha(0f)
            .setDuration(260).setInterpolator(AccelerateInterpolator(1.1f))
            .withEndAction {
                cardAnimating = false
                animateEntryOnNextRender = true
                after()
            }.start()
        peekNear?.animate()?.scaleX(1f)?.scaleY(1f)?.translationY(0f)?.alpha(1f)?.setDuration(240)?.start()
    }
}
