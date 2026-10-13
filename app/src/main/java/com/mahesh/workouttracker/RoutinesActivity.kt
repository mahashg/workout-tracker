package com.mahesh.workouttracker

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** v3 Library tab (named "Body Parts" until v3.1): the 11 body parts in
 *  the same one-page 2-column grid as Home. Tap a part for its warm-ups,
 *  exercises and stretches (from the DB) with per-exercise last performance,
 *  and part-scoped add/edit. */
class RoutinesActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = DbHelper(this)
        Seed.applyV2IfNeeded(this, db)
        JsonSeed.applyV3IfNeeded(this, db)
        render()
    }
    override fun onResume() { super.onResume(); if (::db.isInitialized) render() }

    private fun render() {
        val wm = WeekManager.reconcile(this, db)
        val compact = isCompactScreen()
        val root = tabScaffold("Library")
        root.addView(screenTitle("Library").apply {
            (layoutParams as LinearLayout.LayoutParams).apply { height = dp(40); setMargins(0, 0, 0, 0) }
            gravity = Gravity.CENTER_VERTICAL
        })
        val lastTrained = db.lastTrainedDates()
        val counts = db.partCountsInWeek(wm.weekStart)
        val cellH = if (compact) 56 else 66
        for (rowParts in BodyParts.all.chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (part in rowParts) {
                row.addView(partCell(part, lastTrained[part.id], counts[part.id] ?: 0), LinearLayout.LayoutParams(0, dp(cellH), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
            if (rowParts.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, dp(cellH), 1f))
            root.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(cellH)).apply { setMargins(0, dp(2), 0, dp(2)) })
        }
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(makeText("Tap a body part to see its exercises.", 12f, false, Theme.textSecondary).apply { gravity = Gravity.CENTER })
    }

    private fun partCell(part: BodyParts.Part, last: String?, countThisWeek: Int): View {
        val done = countThisWeek > 0
        val days = last?.let { daysSince(it) }
        val due = !part.repeatable && !done && (last == null || (days != null && days >= 7))
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(tappable = true)
            setPadding(dp(10), dp(6), dp(10), dp(5))
            isClickable = true
            setOnClickListener { startActivity(Intent(this@RoutinesActivity, BodyPartDetailActivity::class.java).putExtra("bodyPart", part.id)) }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val nameTv = makeText(part.name, 14.5f, true)
        nameTv.maxLines = 1
        top.addView(nameTv)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val count = db.getExercisesForPart(part.id).count { it.type == "Main" }
        top.addView(pillView("$count exercises", "#EDF2F7", Theme.textSecondary))
        cell.addView(top)
        val lastLine = when {
            part.repeatable && done -> "Trained ${countThisWeek}× this week"
            done -> "Done this week · ${last?.let { DateUtil.display(it) } ?: ""}"
            due && last == null -> "Due · not trained yet"
            due -> "Due · last ${last?.let { DateUtil.display(it) } ?: ""}"
            last != null -> "Last · ${DateUtil.display(last)}"
            else -> "Not trained yet"
        }
        val lastTv = makeText(lastLine, 11f, false, if (due) Theme.warn else Theme.textSecondary)
        lastTv.maxLines = 1
        lastTv.setPadding(0, dp(2), 0, 0)
        cell.addView(lastTv)
        return cell
    }
}
