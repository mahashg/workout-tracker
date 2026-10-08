package com.mahesh.workouttracker

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class RoutinesActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); db = DbHelper(this); Seed.applyV2IfNeeded(this, db); render() }
    override fun onResume() { super.onResume(); if (::db.isInitialized) render() }
    private fun render() {
        val root = tabScaffold("Routines")
        root.addView(screenTitle("Routines"))
        root.addView(caption("Grouped by assigned day (Sun–Sat). Tap a workout to edit its exercises and form links."))
        root.addView(makeButton("＋ Add New Workout") { startActivity(Intent(this, RoutineEditActivity::class.java).apply{ putExtra("routineId", -1L) }) })
        val routines = db.getRoutines()
        val grouped = routines.groupBy { it.weekday }
        for (day in listOf(0,1,2,3,4,5,6,-1)) {
            val list = grouped[day] ?: continue
            root.addView(sectionLabelText(DateUtil.dayName(day)))
            for (r in list) {
                val card = cardLayout(tappable = true) { startActivity(Intent(this, RoutineEditActivity::class.java).apply{ putExtra("routineId", r.id) }) }
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val muscles = db.routineMuscles(r.id)
                row.addView(BodyMapView(this, muscles).apply { layoutParams = LinearLayout.LayoutParams(dp(64), dp(80)) })
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(12),0,0,0) }
                info.addView(makeText(r.name, 16f, true))
                info.addView(caption("${r.focus}\n${db.routineExerciseCount(r.id)} exercises • ${musclesLabel(muscles)}"))
                row.addView(info)
                row.addView(makeText("✏️ ›", 16f, true, Theme.textTertiary))
                card.addView(row)
                card.addView(makeSecondaryButton("Edit Workout & Exercises") { startActivity(Intent(this, RoutineEditActivity::class.java).apply{ putExtra("routineId", r.id) }) })
                root.addView(card)
            }
        }
        if (routines.isEmpty()) root.addView(caption("No routines yet."))
    }
}
