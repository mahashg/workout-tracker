package com.mahesh.workouttracker

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * v3.14 History session detail (Mahesh's rules, from his History-tab
 * report): tapping a COMPLETED workout in History opens this
 * READ-ONLY view —
 *  1. DONE EXERCISES ONLY: the list is the session's exercises with
 *     effectiveStatus == "done" (the app's own done rule — all sets
 *     logged, or marked done with none), in session order. Passed,
 *     skipped and never-started exercises do not appear, and the
 *     header (body map, counts) describes the same done set.
 *  2. SCROLLABLE: the rows live in a ScrollView whose child wraps its
 *     content; every row wraps its content too — no fixed heights, no
 *     equal-weight compression (the v2.5.1 one-page list in
 *     SessionActivity is the clipping failure this replaces here).
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
 *     long-press actions. Rows are text: name, part/section/equipment
 *     line, and the logged (done) sets via the shared setText format.
 * In-progress sessions keep routing to the live SessionActivity, as do
 * the Summary / Sections "View as list" buttons — this screen serves
 * History's completed workouts only (gym, Movie Movements, circuits).
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

        // ---- Header: wrap-content (never a fixed height). Body map
        // and counts are computed from the DONE exercises — the same
        // set the list below shows. ----
        val header = cardLayout()
        header.setPadding(dp(12), dp(10), dp(12), dp(10))
        val hRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val doneMuscles = linkedSetOf<String>()
        for (e in done) doneMuscles.addAll(DbHelper.parseMuscles(e.targetMuscles, e.name))
        hRow.addView(BodyMapView(this, doneMuscles, true).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(50))
        })
        val hInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(8), 0, 0, 0)
        }
        val hTitle = makeText(session.routineName, 16f, true)
        hTitle.maxLines = 1; hTitle.ellipsize = android.text.TextUtils.TruncateAt.END
        hInfo.addView(hTitle)
        // Circuit sessions store focus as "circuit:<id>" — label them
        // like the History list does instead of leaking the raw token.
        val focusLabel = when {
            isCircuit -> "⚡ Circuit"
            else -> session.focus.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString(", ") { tok -> BodyParts.byId(tok)?.name ?: tok }
        }
        val hSub = makeText(
            listOf(focusLabel, musclesLabel(doneMuscles)).filter { it.isNotBlank() }.joinToString(" • "),
            11f, false, Theme.textSecondary
        )
        hSub.maxLines = 1; hSub.ellipsize = android.text.TextUtils.TruncateAt.END
        hInfo.addView(hSub)
        if (session.startedAt > 0 && session.completed) {
            hInfo.addView(makeText("Time: ${WorkoutTimer.formatDuration(session.elapsedSec)}", 11f, true, Theme.primary))
        }
        val doneWord = when { isCircuit -> "motions"; isMovie -> "movements"; else -> "exercises" }
        hInfo.addView(makeText("${done.size} $doneWord done", 11.5f, true, Theme.success))
        hRow.addView(hInfo)
        header.addView(hRow)
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
            for ((i, ex) in done.withIndex()) box.addView(doneRow(i, ex))
        }

        // No footer actions: Discard lives in the top bar's ⋯ overflow
        // only — discarding a workout is a rare act, not a common one.
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

    /** One done exercise as pure text: name (+ ✓), its part / section /
     *  equipment line, and its logged sets in the shared setText format
     *  ("50×12", "BW×12", timed doses like "40 sec" pass through).
     *  Only done sets are listed — an exercise is here because it was
     *  done; any never-logged set rows are not performance. */
    private fun doneRow(index: Int, ex: SessionExercise): LinearLayout {
        val card = cardLayout()
        card.setPadding(dp(12), dp(10), dp(12), dp(10))
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val name = makeText("${index + 1}. ${ex.name}", 14.5f, true)
        (name.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f }
        top.addView(name)
        top.addView(makeText("✓", 15f, true, Theme.success))
        card.addView(top)
        val partId = db.sessionExercisePart(ex)
        val partName = if (partId.isNotBlank()) BodyParts.name(partId) else ""
        val ctx = listOf(partName, sectionOf(ex.type), ex.equipment)
            .filter { it.isNotBlank() }.joinToString(" • ")
        if (ctx.isNotBlank()) {
            val ctxTv = makeText(ctx, 11.5f, false, Theme.textSecondary)
            (ctxTv.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(2), 0, 0)
            card.addView(ctxTv)
        }
        val doneSets = db.getSets(ex.id).filter { it.isDone }
        if (doneSets.isNotEmpty()) {
            val setsTv = makeText(
                "Sets: " + doneSets.joinToString(" · ") { setText(it) },
                12.5f, false, Theme.textSecondary
            )
            (setsTv.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(3), 0, 0)
            card.addView(setsTv)
        }
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
