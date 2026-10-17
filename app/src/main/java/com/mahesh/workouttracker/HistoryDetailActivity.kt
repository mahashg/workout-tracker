package com.mahesh.workouttracker

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * History session detail (v3.14 behavior contract, Mahesh's rules):
 * tapping a COMPLETED workout in History opens this READ-ONLY view —
 *  1. DONE EXERCISES ONLY: the list is the session's exercises with
 *     effectiveStatus == "done" (the app's own done rule — all sets
 *     logged, or marked done with none), in session order. Passed,
 *     skipped and never-started exercises do not appear, and the
 *     header counts describe the same done set.
 *  2. SCROLLABLE: the rows live in a ScrollView whose child wraps its
 *     content; every row wraps its content too.
 *  3. DISCARDABLE, BUT RARE: Discard is deliberately NOT a visible
 *     control in the body or footer (Mahesh: "It should be a rare
 *     thing") — it lives only inside the ⋯ overflow menu in the top
 *     bar, as its single item "Discard workout". Choosing it asks for
 *     confirmation first, then deletes through the same path
 *     SessionActivity's discard always used (WorkoutTimer.clear +
 *     DbHelper.deleteSession). App-side record only — no Health
 *     Connect deletion is attempted.
 *  4. READ-ONLY: there are deliberately NO editing affordances — no
 *     set chips, no steppers, no add/remove, no reorder, no swipe or
 *     long-press actions.
 * In-progress sessions keep routing to the live SessionActivity, as do
 * the Summary / Sections "View as list" buttons — this screen serves
 * History's completed workouts only (gym, Movie Movements, circuits).
 *
 * v3.16 VISUAL REPAIR (behavior above is unchanged, byte for byte in
 * the filter / discard / empty-state paths). Mahesh's screenshot of
 * v3.14 showed giant ~340dp near-empty row cards, the exercise name
 * invisible, a lone green ✓ floating mid-left, and the meta + sets
 * lines pinned at the card's bottom. ROOT CAUSE: doneRow's horizontal
 * top row paired the name TextView (width 0 + weight 1) with a ✓
 * TextView created by makeText(), which assigns MATCH_PARENT width.
 * In a horizontal LinearLayout the MATCH_PARENT child is measured at
 * the full row width first, starving the weighted name to ~0px; at
 * ~0 width the name wrapped character-by-character, inflating the
 * row's height to hundreds of dp while its glyphs clipped away, and
 * the row's CENTER_VERTICAL gravity parked the ✓ mid-card. The
 * header was the legacy summary card (tiny body map, parts text
 * repeated and truncated). v3.16 rebuilds both against the approved
 * mock (detail-pages-mock-r2, Screen 1): header = session-kind logo
 * in the pastel squircle (the History list row's settingsIcon
 * treatment) + session name on up to two lines + date/time line,
 * then three summary cells (done count / total logged sets /
 * duration — the duration cell is omitted when no duration was
 * recorded); rows = compact content-sized cards with a 44dp
 * circular photo thumb (PhotoLib; body-map fallback for photo-less
 * entries, never a wrong-movement photo), the name in bold, the
 * part/section/equipment line, and the logged sets via the shared
 * setText format. The floating ✓ is gone — done is the page's
 * premise and the summary's count. In the new row the only weighted
 * child of the horizontal row is the text column; every sibling is
 * a deliberate fixed size, so the starvation trap cannot recur.
 */
class HistoryDetailActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var sessionId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = DbHelper(this)
        sessionId = intent.getLongExtra("sessionId", -1)
        if (sessionId < 0) { finish(); return }
        render()
    }

    private fun sectionOf(type: String): String = when (type.lowercase()) {
        "warmup" -> "Warm Up"; "stretch" -> "Cool Down"; else -> "Exercise"
    }

    private fun render() {
        val session = db.getSession(sessionId) ?: run { finish(); return }
        val root = fitRoot(); setContentView(root)
        // Top bar + the ⋯ overflow at its right edge — the ONLY home of
        // the Discard action (spec: rare, not a common-looking option).
        val bar = topBar(session.routineName, "${DateUtil.display(session.date)} • Week ${session.weekNumber}")
        bar.addView(TextView(this).apply {
            text = "⋯"; textSize = 20f; setTextColor(Theme.textSecondary)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            isClickable = true
            setOnClickListener { view -> showOverflow(view) }
        })
        root.addView(bar)

        val done = db.getSessionExercises(sessionId).filter { db.effectiveStatus(it) == "done" }
        val isCircuit = CircuitsData.isCircuit(session)
        val isMovie = session.routineName == MovieDeck.ROUTINE_NAME
        // Logged (done) sets per done exercise, computed once: the
        // header's SETS total and each row's sets line read the same
        // lists, in the same order.
        val doneSets = done.map { ex -> db.getSets(ex.id).filter { it.isDone } }
        val totalSets = doneSets.sumOf { it.size }

        // ---- Header (mock Screen 1): kind logo in the pastel
        // squircle + session name (two lines before ellipsizing) +
        // date/time line, then the three summary cells. All
        // wrap-content; the only weighted children are the name
        // column and the equal summary cells, by design. ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val hRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        // Same kind detection + squircle as the History list rows.
        val (logoEmoji, logoTint) = when {
            isCircuit -> "⚡" to IconTints.GREEN
            isMovie -> "🏠" to IconTints.PINK
            else -> "🏋️" to IconTints.BLUE
        }
        val logo = settingsIcon(logoEmoji, logoTint, 46, 22f)
        (logo.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(12), 0)
        hRow.addView(logo)
        val hInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val hTitle = makeText(session.routineName, 19f, true)
        hTitle.maxLines = 2; hTitle.ellipsize = android.text.TextUtils.TruncateAt.END
        (hTitle.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        hInfo.addView(hTitle)
        val dateText = DateUtil.display(session.date) + if (session.startedAt > 0) {
            " · " + SimpleDateFormat("h:mm a", Locale.US).format(Date(session.startedAt))
        } else ""
        val hDate = makeText(dateText, 12.5f, false, Theme.textSecondary)
        (hDate.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(1), 0, 0)
        hInfo.addView(hDate)
        hRow.addView(hInfo)
        header.addView(hRow)

        val doneWord = when { isCircuit -> "motions"; isMovie -> "movements"; else -> "exercises" }
        val cells = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(14), 0, 0) }
        }
        cells.addView(summaryCell(done.size.toString(), "${doneWord.uppercase(Locale.US)} DONE", first = true))
        cells.addView(summaryCell(totalSets.toString(), "SETS", first = false))
        // Duration cell only when a duration was actually recorded —
        // no placeholder cell for sessions that have none.
        if (session.startedAt > 0) {
            cells.addView(summaryCell(WorkoutTimer.formatDuration(session.elapsedSec), "DURATION", first = false))
        }
        header.addView(cells)
        root.addView(header)

        // ---- Done-only list, fully scrollable: ScrollView (flex
        // weight) around a wrap-content column of wrap-content rows.
        // The last row is always reachable; nothing is compressed. ----
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(box)
        }
        root.addView(scroll)

        if (done.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "No completed exercises in this session."
                textSize = 13.5f; setTextColor(Theme.textSecondary)
                gravity = Gravity.CENTER
                setPadding(0, dp(18), 0, dp(18))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        } else {
            for ((i, ex) in done.withIndex()) box.addView(doneRow(ex, doneSets[i]))
        }

        // No footer actions: Discard lives in the top bar's ⋯ overflow
        // only — discarding a workout is a rare act, not a common one.
    }

    /** One of the mock's three summary cells: big value over a
     *  letterspaced micro label, on a soft white rounded cell. Cells
     *  share the row equally (width 0 + weight 1 each — a deliberate
     *  equal distribution, with wrap-content height). */
    private fun summaryCell(value: String, label: String, first: Boolean): LinearLayout {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply { setColor(Theme.surface); cornerRadius = dp(14).toFloat() }
            setPadding(dp(6), dp(10), dp(6), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(if (first) 0 else dp(8), 0, 0, 0) }
        }
        val v = makeText(value, 16f, true)
        v.gravity = Gravity.CENTER
        (v.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        cell.addView(v)
        val k = makeText(label, 10.5f, true, Theme.textSecondary)
        k.gravity = Gravity.CENTER; k.letterSpacing = 0.06f
        (k.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(1), 0, 0)
        cell.addView(k)
        return cell
    }

    /** The top bar's overflow menu: a single item, "Discard workout",
     *  which routes to the same confirmation as before. */
    private fun showOverflow(anchor: android.view.View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add("Discard workout")
        popup.setOnMenuItemClickListener {
            confirmDiscard()
            true
        }
        popup.show()
    }

    /** One done exercise (mock Screen 1 row): a compact, content-sized
     *  card — 44dp circular photo thumb (the entry's own photo via
     *  PhotoLib; body-map fallback when there is none), the exercise
     *  name in bold, its part / section / equipment line, and its
     *  logged sets in the shared setText format ("50×12", "BW×12",
     *  timed doses like "40 sec" pass through). Only done sets are
     *  listed — an exercise is here because it was done; any
     *  never-logged set rows are not performance.
     *  Geometry: the horizontal row's ONLY weighted child is the text
     *  column (width 0 + weight 1, wrap-content height); the thumb is
     *  a deliberate fixed 44dp. No MATCH_PARENT-width sibling can
     *  starve the name (the v3.14 root cause). */
    private fun doneRow(ex: SessionExercise, sets: List<SessionSet>): LinearLayout {
        val card = cardLayout()
        card.setPadding(dp(13), dp(11), dp(13), dp(11))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val thumb = bendCircleFrame(44)
        (thumb.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, dp(12), 0)
        val photoAsset = PhotoLib.assetForSessionExercise(this, db, ex)
        val photoBmp = photoAsset?.let { PhotoLib.bitmap(this, it) }
        if (photoBmp != null) {
            thumb.addView(ImageView(this).apply {
                setImageBitmap(photoBmp); scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
        } else {
            thumb.addView(BodyMapView(this, DbHelper.parseMuscles(ex.targetMuscles, ex.name), true).apply {
                layoutParams = FrameLayout.LayoutParams(dp(24), dp(30), Gravity.CENTER)
            })
        }
        row.addView(thumb)
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val name = makeText(ex.name, 15f, true)
        (name.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        info.addView(name)
        val partId = db.sessionExercisePart(ex)
        val partName = if (partId.isNotBlank()) BodyParts.name(partId) else ""
        val ctx = listOf(partName, sectionOf(ex.type), ex.equipment)
            .filter { it.isNotBlank() }.joinToString(" • ")
        if (ctx.isNotBlank()) {
            val ctxTv = makeText(ctx, 12f, false, Theme.textSecondary)
            (ctxTv.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(1), 0, 0)
            info.addView(ctxTv)
        }
        if (sets.isNotEmpty()) {
            val setsTv = makeText(
                "Sets: " + sets.joinToString(" · ") { setText(it) },
                12.5f, false, Theme.textSecondary
            )
            (setsTv.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(1), 0, 0)
            info.addView(setsTv)
        }
        row.addView(info)
        card.addView(row)
        return card
    }

    /** Same deletion semantics as SessionActivity's long-standing
     *  discard: clear the session's timer state, delete the session
     *  (DbHelper.deleteSession removes its sets, exercises and
     *  session_parts), and return to the History list, which
     *  re-renders on resume without the discarded workout. */
    private fun confirmDiscard() {
        AlertDialog.Builder(this)
            .setTitle("Discard this workout?")
            .setMessage("This removes it from your history.")
            .setPositiveButton("Discard") { _, _ ->
                WorkoutTimer.clear(this, sessionId)
                db.deleteSession(sessionId)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
