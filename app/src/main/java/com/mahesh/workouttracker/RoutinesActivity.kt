package com.mahesh.workouttracker

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class RoutinesActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var page: Int = 0
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); db = DbHelper(this); Seed.applyV2IfNeeded(this, db); render() }
    override fun onResume() { super.onResume(); if (::db.isInitialized) render() }

    private fun render() {
        val root = tabScaffold("Routines")
        root.addView(screenTitle("Routines"))
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val libBtn = primaryButtonWithIcon("Exercise Library (33 extra moves)", R.drawable.ic_list) { startActivity(Intent(this, LibraryActivity::class.java)) }
        (libBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48) }
        val addBtn = makeSecondaryButton("Add New Workout") { startActivity(Intent(this, RoutineEditActivity::class.java).apply { putExtra("routineId", -1L) }) }
        (addBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48) }
        btnRow.addView(libBtn); btnRow.addView(addBtn)
        root.addView(btnRow)

        // Flattened in day order, paginated 4 compact cards per page.
        val routines = db.getRoutines()
        val ordered = mutableListOf<Routine>()
        val grouped = routines.groupBy { it.weekday }
        for (day in listOf(0, 1, 2, 3, 4, 5, 6, -1)) grouped[day]?.let { ordered.addAll(it) }
        val pageSize = 4
        val pageCount = ((ordered.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        if (page !in 0 until pageCount) page = pageCount - 1
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        if (ordered.isEmpty()) box.addView(caption("No routines yet."))
        for (r in ordered.drop(page * pageSize).take(pageSize)) {
            val card = cardLayout(tappable = true) { startActivity(Intent(this, RoutineEditActivity::class.java).apply { putExtra("routineId", r.id) }) }
            (card.layoutParams as LinearLayout.LayoutParams).apply { height = 0; weight = 1f; setMargins(0, dp(4), 0, dp(4)) }
            card.setPadding(dp(12), dp(6), dp(12), dp(6))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT) }
            val muscles = db.routineMuscles(r.id)
            row.addView(BodyMapView(this, muscles).apply { layoutParams = LinearLayout.LayoutParams(dp(38), dp(46)) })
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(10), 0, 0, 0) }
            info.addView(makeText(r.name, 15f, true))
            val sub = makeText("${DateUtil.dayName(r.weekday)} • ${r.focus} • ${db.routineExerciseCount(r.id)} exercises • ${musclesLabel(muscles)}", 11f, false, Theme.textSecondary)
            sub.maxLines = 2; sub.ellipsize = android.text.TextUtils.TruncateAt.END
            info.addView(sub)
            info.addView(caption("Tap to edit exercises & form links"))
            row.addView(info)
            row.addView(iconView(R.drawable.ic_chevron, 20))
            card.addView(row)
            box.addView(card)
        }
        root.addView(box)
        if (pageCount > 1) root.addView(pagerBar(page, pageCount, { page--; render() }, { page++; render() }))
    }
}
