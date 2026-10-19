package com.mahesh.workouttracker

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
 *  Recomputed on every render (selection change).
 *
 *  v3.17: each cell is three lines — the part name ALONE on line 1
 *  (it previously shared a row with the status pill, which starved
 *  it to ~0 width), the pill(s) on line 2, last-trained on line 3;
 *  cell heights are 92 (regular) / 82 (compact). */
class PickPartsActivity : AppCompatActivity() {
    companion object {
        /** v3.18: MainActivity's Simple Mode hero sends the last
         *  workout's part ids under this key (comma-separated) so
         *  "▶ Start again" can skip the picker entirely. */
        const val EXTRA_START_PARTS_CSV = "startPartsCsv"
    }

    private lateinit var db: DbHelper
    private val selected = linkedSetOf<String>()
    private var created = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = DbHelper(this)
        JsonSeed.applyV3IfNeeded(this, db)
        // v3.18 hero shortcut: a non-blank CSV of known part ids
        // starts that workout immediately, through the SAME
        // startSessionWithAll every picker start uses — its
        // exclusivity blocker toasts + finishes safely if another
        // workout is active. Blank / all-unknown CSVs fall through
        // to the normal picker.
        val csv = intent.getStringExtra(EXTRA_START_PARTS_CSV)
        if (!csv.isNullOrBlank()) {
            val ids = csv.split(',').map { it.trim() }.filter { id -> BodyParts.all.any { it.id == id } }
            if (ids.isNotEmpty()) {
                startSessionWithAll(ids)
                return
            }
        }
        created = true
        render()
    }

    private fun render() {
        val simple = SimpleMode.isOn(this)
        val wm = WeekManager.reconcile(this, db)
        val compact = isCompactScreen()
        val root = fitRoot()
        setContentView(root)
        root.addView(topBar("Start Workout", "Pick the body parts for today"))
        val lastTrained = db.lastTrainedDates()
        val counts = db.partCountsInWeek(wm.weekStart)

        // v3.17: three-line cells (name / pills / last-trained) need
        // taller cells: 92 regular / 82 compact (was 68/60 in v3.9.1).
        val cellH = if (compact) 82 else 92
        // v3.12: anatomy logo size per cell (compact / regular).
        val logoDp = if (compact) 44 else 50
        if (simple) {
            // v3.18 Simple Mode: one full-width row per part instead
            // of the grid — a part is picked or it isn't (no pills,
            // no pairing states, no long-press detail). The rows sit
            // in a weight-1 ScrollView: 11 rows at ~80dp overflow
            // fitRoot's fixed height on shorter screens, and the
            // Start button must never be pushed off-screen.
            val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for (part in BodyParts.all) {
                listBox.addView(simplePartRow(part, lastTrained[part.id], counts[part.id] ?: 0))
            }
            val scroll = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                addView(listBox)
            }
            root.addView(scroll)
        } else {
            for (rowParts in BodyParts.all.chunked(2)) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (part in rowParts) {
                    row.addView(partCell(part, lastTrained[part.id], counts[part.id] ?: 0, logoDp), LinearLayout.LayoutParams(0, dp(cellH), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
                }
                if (rowParts.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, dp(cellH), 1f))
                root.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(cellH)).apply { setMargins(0, dp(2), 0, dp(2)) })
            }
        }
        // Full mode keeps its weight-1 spacer; in simple mode the
        // ScrollView above already carries the weight.
        if (!simple) root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
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
     *  page used).
     *  v3.17 main list (per part, concatenated in picked order, no
     *  global re-sort): mains are equipment-only (Bodyweight dropped).
     *  With a prior completed gym session for the part, continuity =
     *  last time's mains, in last time's order, resolved to current
     *  catalog rows — no top-ups. Otherwise fresh = the part's mains
     *  in equipment clusters (equipRank), photo-first inside a cluster. */
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
        val seenMainIds = mutableSetOf<Long>()
        for (pid in partIds) {
            val catalog = db.getExercisesForPart(pid)
            for (e in catalog) {
                val key = e.sourceId.ifBlank { "name:" + e.name.lowercase() }
                when (e.type) {
                    "Warmup" -> warm.putIfAbsent(key, e)
                    "Stretch" -> stretch.putIfAbsent(key, e)
                }
            }
            // This part's catalog mains: never Warmup/Stretch.
            val catalogMains = catalog.filter { !it.type.equals("Warmup", true) && !it.type.equals("Stretch", true) }
            // v3.17 continuity block: last completed gym session for
            // this part, walked in its own order; each entry resolves
            // to a current catalog main (sourceId via its origin row
            // first, exact name as fallback). Bodyweight is dropped.
            val continuity = mutableListOf<Exercise>()
            val prior = db.lastCompletedGymSessionForPart(pid)
            if (prior != null) {
                val seenInBlock = mutableSetOf<Long>()
                for (se in db.getSessionExercises(prior.id)) {
                    if (se.type.equals("Warmup", true) || se.type.equals("Stretch", true)) continue
                    val origin = if (se.originExerciseId > 0) db.getExercise(se.originExerciseId) else null
                    val match = origin?.sourceId?.takeIf { it.isNotBlank() }?.let { sid -> catalogMains.firstOrNull { it.sourceId == sid } }
                        ?: catalogMains.firstOrNull { it.name.equals(se.name, true) }
                    if (match == null) continue
                    if (match.equipment.equals("Bodyweight", true)) continue
                    if (seenInBlock.add(match.id)) continuity.add(match)
                }
            }
            // v3.17 fresh block (no prior session, or continuity came
            // up empty): equipment-only mains, stable-sorted by
            // equipRank clusters with photo-first inside a cluster.
            val block = if (prior == null || continuity.isEmpty()) {
                catalogMains.filter { !it.equipment.equals("Bodyweight", true) }
                    .sortedWith(compareBy<Exercise> { equipRank(it.equipment) }.thenBy { if (PhotoLib.assetForExercise(this, it) != null) 0 else 1 })
            } else continuity
            // Concatenate in partIds order; dedupe across parts by id.
            for (e in block) if (seenMainIds.add(e.id)) mainAll.add(e)
        }
        val items = mutableListOf<Exercise>()
        items.addAll(warm.values)
        items.addAll(mainAll)
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

    /** v3.18 Simple Mode picker row (mock r3, Screen 2): logo disc,
     *  name, last-trained line, tick circle — nothing else. The text
     *  column is the row's ONLY weighted child, sandwiched between two
     *  fixed-size children (46dp disc, 27dp tick), and the name sits
     *  ALONE on its line at MATCH_PARENT inside that column — no pill
     *  or badge shares its row, so the name's width is always the full
     *  column width (row − 28dp padding − 46dp disc − 12dp gap −
     *  27dp tick) and can never be starved the way the pre-v3.17 grid
     *  cells starved it. The last-trained line reuses partCell's exact
     *  wording, plus " · ✓ this week" when the part is done this week.
     *  Deliberately NO long-press (detail is disabled in simple mode),
     *  NO state pills and NO compatibility dimming/highlighting. */
    private fun simplePartRow(part: BodyParts.Part, last: String?, countThisWeek: Int): View {
        val isSel = part.id in selected
        val doneThisWeek = countThisWeek > 0
        val days = last?.let { daysSince(it) }
        val lastLine = when {
            last == null -> "Not trained yet"
            days != null && days == 0L -> "Last · today"
            days != null && days == 1L -> "Last · yesterday"
            days != null -> "Last · ${DateUtil.display(last)} · ${days}d ago"
            else -> "Last · ${DateUtil.display(last)}"
        }
        val subLine = lastLine + if (doneThisWeek) " · ✓ this week" else ""
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(if (isSel) Theme.primary else Theme.surface)
                cornerRadius = dp(18).toFloat()
                if (!isSel) setStroke(dp(1), Theme.stroke)
            }
            elevation = dp(2).toFloat()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(10)) }
            isClickable = true
            setOnClickListener {
                if (isSel) selected.remove(part.id) else selected.add(part.id)
                render()
            }
        }
        // Logo disc (46dp, white oval): the part's anatomy logo
        // bitmap filling the disc, CENTER_CROP and clipped round —
        // the same decode path the grid cells use (PartLogos), with
        // the clip radius at half the disc so the crop is a circle.
        // No per-part emoji exists in the model, so a missing logo
        // falls back to a generic centred glyph.
        val disc = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        }
        val bmp = PartLogos.bitmap(this, part.id)
        if (bmp != null) {
            disc.addView(ImageView(this).apply {
                setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP
                roundedClip(23)
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
        } else {
            disc.addView(TextView(this).apply {
                text = "💪"; textSize = 24f; gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
        }
        row.addView(disc)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(12), 0, 0, 0) }
        }
        val nameTv = makeText(part.name, 16.5f, true, if (isSel) Color.WHITE else Theme.textPrimary)
        nameTv.maxLines = 1
        nameTv.ellipsize = android.text.TextUtils.TruncateAt.END
        (nameTv.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        col.addView(nameTv)
        val subTv = makeText(subLine, 12.5f, false, when {
            isSel -> 0xD9FFFFFF.toInt()
            doneThisWeek -> Theme.success
            else -> Theme.textSecondary
        })
        subTv.maxLines = 1
        subTv.ellipsize = android.text.TextUtils.TruncateAt.END
        (subTv.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(2), 0, 0)
        col.addView(subTv)
        row.addView(col)
        row.addView(TextView(this).apply {
            text = if (isSel) "✓" else ""
            textSize = 15f; gravity = Gravity.CENTER
            setTextColor(Theme.primary)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (isSel) setColor(Color.WHITE) else { setColor(Color.TRANSPARENT); setStroke(dp(2), Theme.stroke) }
            }
            layoutParams = LinearLayout.LayoutParams(dp(27), dp(27))
        })
        return row
    }

    private fun partCell(part: BodyParts.Part, last: String?, countThisWeek: Int, logoDp: Int): View {
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
            // v3.12: HORIZONTAL — anatomy logo first, then the text column.
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = when {
                isSel -> stateCardDrawable(Theme.primary, tappable = true)
                suggested -> stateCardDrawable(violetFill, violet, dashed = true, tappable = true)
                done -> stateCardDrawable(greenFill, greenStroke, tappable = true)
                else -> cardDrawable(tappable = true)
            }
            setPadding(dp(8), dp(5), dp(8), dp(5))
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
        // v3.12: the part's anatomy logo leads the cell (omitted when
        // no logo decodes); the text lines sit in a weight-1 vertical
        // column beside it. v3.17: three lines in that column — name
        // alone, pill row, last-trained (see below).
        PartLogos.imageView(this, part.id, logoDp)?.let { logo ->
            (logo.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(8), 0)
            cell.addView(logo)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        cell.addView(col)
        // v3.17 LINE 1: the name ALONE, a direct child of the column
        // — no pill shares its row (that sharing starved the name to
        // ~0 width). Ellipsize is only a safety net.
        val nameTv = makeText(part.name, 14.5f, true, if (isSel) Color.WHITE else Theme.textPrimary)
        nameTv.maxLines = 1
        nameTv.ellipsize = android.text.TextUtils.TruncateAt.END
        nameTv.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 0) }
        col.addView(nameTv)
        // v3.17 LINE 2: the pill row, holding exactly the pill(s) the
        // state `when` produces (same texts/colors as before).
        val pillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(3), 0, 0) }
        }
        when {
            isSel -> pillRow.addView(pillView("Selected", "#FFFFFF", Theme.primary))
            done -> {
                pillRow.addView(pillView(if (part.repeatable) "✓ Done · ${countThisWeek}×" else "✓ Done this week", "#DCFCE7", Theme.success))
                // Layering: a done part that also pairs well keeps a small
                // ✦ marker next to its Done pill (mockup).
                if (suggested) {
                    val star = pillView("✦", "#EDE9FE", violetPillInk)
                    pillRow.addView(star, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(4), 0, 0, 0) })
                }
            }
            notPaired -> pillRow.addView(pillView("Not paired", "#E2E8F0", Theme.pendingFg))
            suggested -> pillRow.addView(pillView("✦ Pairs well", "#EDE9FE", violetPillInk))
            part.repeatable -> pillRow.addView(pillView("${countThisWeek}× this week", "#EDF2F7", Theme.textSecondary))
            due -> pillRow.addView(pillView("Due", "#FEF3C7", Theme.warn))
            else -> pillRow.addView(pillView("Pending", "#E2E8F0", Theme.pendingFg))
        }
        col.addView(pillRow)
        val lastLine = when {
            last == null -> "Not trained yet"
            days != null && days == 0L -> "Last · today"
            days != null && days == 1L -> "Last · yesterday"
            days != null -> "Last · ${DateUtil.display(last)} · ${days}d ago"
            else -> "Last · ${DateUtil.display(last)}"
        }
        // v3.17 LINE 3 (v3.9.1: 12sp muted plain text — below-12sp
        // clipped inside the old fixed-height cells). Never-trained
        // ("Not trained yet") is this same line: plain textSecondary,
        // no pill, full cell width.
        val lastTv = makeText(lastLine, 12f, false, if (isSel) Color.parseColor("#BFDBFE") else Theme.textSecondary)
        lastTv.maxLines = 1
        lastTv.setPadding(0, dp(2), 0, 0)
        lastTv.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(3), 0, 0) }
        col.addView(lastTv)
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
