package com.mahesh.workouttracker

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** v3 picker: choose today's body parts (multi-select, at least 1), then the
 *  workout is assembled from the per-part JSON-seeded data. Same one-page
 *  2-column grid as Home.
 *
 *  v3.2 five-state card system (approved mockup; NO legend — the treatments
 *  themselves are the code, and they layer instead of competing):
 *  - Selected: solid primary fill, white text, white ✓ badge + "Selected"
 *    pill. Overrides every other treatment.
 *  - Suggested (pairs with EVERY selected part): pale violet fill, DASHED
 *    violet border, "✦ Pairs well" pill — never a check mark.
 *  - Done this week: pale green fill + green border + "✓ Done this week"
 *    pill (Core: "✓ Done · N×"). The Done pill shows whenever the part is
 *    done and not selected — on suggested cards too (next to a small ✦
 *    pill) and on dimmed not-paired cards.
 *  - Not paired (selection non-empty, doesn't pair with all selected):
 *    whole card dimmed to alpha 0.45 + "Not paired" pill; still tappable.
 *  - Plain (nothing selected): default card, v3 Due/Pending pills.
 *  Recomputed on every render (selection change). */
class PickPartsActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private val selected = linkedSetOf<String>()
    private var created = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = DbHelper(this)
        JsonSeed.applyV3IfNeeded(this, db)
        created = true
        render()
    }

    private fun render() {
        val wm = WeekManager.reconcile(this, db)
        val compact = isCompactScreen()
        val root = fitRoot()
        setContentView(root)
        root.addView(topBar("Start Workout", "Pick the body parts for today"))
        val lastTrained = db.lastTrainedDates()
        val counts = db.partCountsInWeek(wm.weekStart)

        // v3.9.1: taller cells (60/68) + the last-trained line at
        // 12sp — at 56/64dp with an 11sp line budgeted at font scale
        // 1.0, the second line clipped at larger font scales.
        val cellH = if (compact) 60 else 68
        for (rowParts in BodyParts.all.chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (part in rowParts) {
                row.addView(partCell(part, lastTrained[part.id], counts[part.id] ?: 0), LinearLayout.LayoutParams(0, dp(cellH), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
            if (rowParts.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, dp(cellH), 1f))
            root.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(cellH)).apply { setMargins(0, dp(2), 0, dp(2)) })
        }
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val n = selected.size
        // v3.7: no assemble/select page any more — Start creates the
        // session IMMEDIATELY with every exercise for the picked parts
        // (Mahesh: "start with all workout and let one select or pass
        // on specific one"). v3.8.2: it opens the FIRST exercise card
        // directly — there is no hub stop on the way in; the hub
        // remains as an overview behind the card's "☰ All exercises".
        val btn = makeButton(if (n == 0) "Pick at least 1 body part" else "Start workout · $n part${if (n == 1) "" else "s"}") {
            if (selected.isEmpty()) return@makeButton
            val ordered = BodyParts.ids.filter { it in selected }
            startSessionWithAll(ordered)
        }
        tightButton(btn, if (compact) 50 else 54, 14, primary = true)
        btn.isEnabled = n > 0
        btn.alpha = if (n > 0) 1f else 0.5f
        root.addView(btn)
    }

    private fun equipRank(equipment: String): Int = when (equipment.lowercase()) {
        "machine" -> 0; "dumbbell" -> 1; "kettlebell" -> 2; "bodyweight" -> 3; else -> 4
    }

    /** v3.7 hub-flow session creation: warm-ups and stretches are the
     *  deduped union across the picked parts (same keying the assemble
     *  page used); the Main list is EVERY exercise for the parts —
     *  primaries and non-primaries — in equipment clusters (Machine →
     *  Dumbbell → Kettlebell → Bodyweight, stable sort keeps JSON order
     *  inside a cluster). Nothing is pre-filtered: passing on an
     *  exercise happens per exercise, from its card (Skip / Pass on
     *  the hub overview). */
    private fun startSessionWithAll(partIds: List<String>) {
        // v3.9.3 exclusivity (defense in depth — the chooser's
        // Workout door is already locked in this state): never CREATE
        // a gym session while a Movie Movements session is in
        // progress. One active workout at a time; back out to Home.
        // v3.11: a circuit session blocks creation too, with its own
        // toast; the blocker is whichever kind is in progress.
        val blocker = db.inProgressSessions().firstOrNull {
            it.routineName == MovieDeck.ROUTINE_NAME || CircuitsData.isCircuit(it)
        }
        if (blocker != null) {
            Toast.makeText(
                this,
                if (CircuitsData.isCircuit(blocker)) "Finish your current circuit first" else "Finish your current Movements first",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }
        val warm = LinkedHashMap<String, Exercise>()
        val stretch = LinkedHashMap<String, Exercise>()
        val mainAll = mutableListOf<Exercise>()
        for (pid in partIds) {
            for (e in db.getExercisesForPart(pid)) {
                val key = e.sourceId.ifBlank { "name:" + e.name.lowercase() }
                when (e.type) {
                    "Warmup" -> warm.putIfAbsent(key, e)
                    "Stretch" -> stretch.putIfAbsent(key, e)
                    else -> mainAll.add(e)
                }
            }
        }
        val items = mutableListOf<Exercise>()
        items.addAll(warm.values)
        items.addAll(mainAll.sortedBy { equipRank(it.equipment) })
        items.addAll(stretch.values)
        if (items.isEmpty()) return
        val wm = WeekManager.reconcile(this, db)
        val label = partIds.joinToString(" + ") { BodyParts.name(it) }
        val sessionId = db.createAssembledSession(label, partIds, items, wm.weekNumber, wm.weekStart)
        // v3.8.2: straight to the first workout card (warm-ups first) —
        // the hub is no longer the start destination.
        startActivity(firstPendingCardIntent(db, sessionId))
        finish()
    }

    private fun partCell(part: BodyParts.Part, last: String?, countThisWeek: Int): View {
        val isSel = part.id in selected
        val done = countThisWeek > 0
        // Suggested = unselected and compatible with EVERY selected part.
        val suggested = !isSel && selected.isNotEmpty() && selected.all { s -> BodyParts.compatible(part.id, s) }
        val notPaired = !isSel && selected.isNotEmpty() && !suggested
        val days = last?.let { daysSince(it) }
        val due = !part.repeatable && !done && (last == null || (days != null && days >= 7))

        val violet = Color.parseColor("#7C3AED")
        val violetFill = Color.parseColor("#F5F0FF")
        val violetPillInk = Color.parseColor("#6D28D9")
        val greenFill = Color.parseColor("#F0FDF4")
        val greenStroke = Color.parseColor("#86EFAC")

        // FrameLayout so the Selected ✓ badge can sit on the card's
        // top-right corner, clear of the name/pill row.
        val frame = FrameLayout(this)
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = when {
                isSel -> stateCardDrawable(Theme.primary, tappable = true)
                suggested -> stateCardDrawable(violetFill, violet, dashed = true, tappable = true)
                done -> stateCardDrawable(greenFill, greenStroke, tappable = true)
                else -> cardDrawable(tappable = true)
            }
            setPadding(dp(10), dp(5), dp(10), dp(4))
            if (notPaired) alpha = 0.45f
            isClickable = true
            setOnClickListener {
                if (isSel) selected.remove(part.id) else selected.add(part.id)
                render()
            }
            // v3.9.1: the part DETAIL page (exercise list + add) was
            // reachable only from the retired Workout page's grid —
            // keep it reachable from here via long-press, without
            // disturbing tap-to-select.
            setOnLongClickListener {
                startActivity(Intent(this@PickPartsActivity, BodyPartDetailActivity::class.java).putExtra("bodyPart", part.id))
                true
            }
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val nameTv = makeText(part.name, 14.5f, true, if (isSel) Color.WHITE else Theme.textPrimary)
        nameTv.maxLines = 1
        (nameTv.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; setMargins(0, 0, dp(4), 0) }
        top.addView(nameTv)
        when {
            isSel -> top.addView(pillView("Selected", "#FFFFFF", Theme.primary))
            done -> {
                top.addView(pillView(if (part.repeatable) "✓ Done · ${countThisWeek}×" else "✓ Done this week", "#DCFCE7", Theme.success))
                // Layering: a done part that also pairs well keeps a small
                // ✦ marker next to its Done pill (mockup).
                if (suggested) {
                    val star = pillView("✦", "#EDE9FE", violetPillInk)
                    top.addView(star, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(4), 0, 0, 0) })
                }
            }
            notPaired -> top.addView(pillView("Not paired", "#E2E8F0", Theme.pendingFg))
            suggested -> top.addView(pillView("✦ Pairs well", "#EDE9FE", violetPillInk))
            part.repeatable -> top.addView(pillView("${countThisWeek}× this week", "#EDF2F7", Theme.textSecondary))
            due -> top.addView(pillView("Due", "#FEF3C7", Theme.warn))
            else -> top.addView(pillView("Pending", "#E2E8F0", Theme.pendingFg))
        }
        cell.addView(top)
        val lastLine = when {
            last == null -> "Not trained yet"
            days != null && days == 0L -> "Last · today"
            days != null && days == 1L -> "Last · yesterday"
            days != null -> "Last · ${DateUtil.display(last)} · ${days}d ago"
            else -> "Last · ${DateUtil.display(last)}"
        }
        // v3.9.1: 12sp muted plain text (was 11sp — below the app's
        // 12sp floor, and the line that clipped inside the fixed-
        // height cells). Never-trained ("Not trained yet") is this
        // same line: plain textSecondary, no pill, full cell width.
        val lastTv = makeText(lastLine, 12f, false, if (isSel) Color.parseColor("#BFDBFE") else Theme.textSecondary)
        lastTv.maxLines = 1
        lastTv.setPadding(0, dp(2), 0, 0)
        (lastTv.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        cell.addView(lastTv)
        frame.addView(cell)
        if (isSel) {
            // White circle with a blue ✓ on the card's top-right corner —
            // the one unmistakable "this is picked" marker.
            val badge = TextView(this).apply {
                text = "✓"; textSize = 12f; setTextColor(Theme.primary); gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(1), Theme.primary) }
                layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(5), dp(5), 0) }
            }
            frame.addView(badge)
        }
        return frame
    }
}
