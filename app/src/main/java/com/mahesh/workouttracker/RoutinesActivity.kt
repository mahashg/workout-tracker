package com.mahesh.workouttracker

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class RoutinesActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); db = DbHelper(this); Seed.applyV2IfNeeded(this, db); render() }
    override fun onResume() { super.onResume(); if (::db.isInitialized) render() }

    private fun render() {
        val root = tabScaffold("Routines")
        root.addView(screenTitle("Routines").apply {
            (layoutParams as LinearLayout.LayoutParams).apply { height = dp(40); setMargins(0, 0, 0, 0) }
            gravity = Gravity.CENTER_VERTICAL
        })
        // Fixed 56dp action row: Library as a compact card (two-line label so
        // "Exercise Library" never clips at 360dp wide) + Add Routine button.
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)) }
        val libCard = cardLayout("#2563EB", tappable = true) { startActivity(Intent(this, LibraryActivity::class.java)) }
        (libCard.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1.35f; height = dp(56); setMargins(0, 0, dp(4), 0) }
        libCard.setPadding(dp(10), dp(4), dp(8), dp(4))
        val libRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT) }
        libRow.addView(iconView(R.drawable.ic_list, 20, Theme.primary))
        val libInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(8), 0, 0, 0) }
        val libTitle = makeText("Exercise Library", 15f, true)
        libTitle.maxLines = 1
        (libTitle.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        libInfo.addView(libTitle)
        val libSub = makeText("33 extra moves", 11f, false, Theme.textSecondary)
        libSub.maxLines = 1
        (libSub.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        libInfo.addView(libSub)
        libRow.addView(libInfo)
        libRow.addView(iconView(R.drawable.ic_chevron, 18, Theme.textTertiary))
        libCard.addView(libRow)
        btnRow.addView(libCard)
        val addBtn = makeSecondaryButton("Add Workout") { startActivity(Intent(this, RoutineEditActivity::class.java).apply { putExtra("routineId", -1L) }) }
        addBtn.maxLines = 1; addBtn.textSize = 14f
        (addBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48); setMargins(dp(4), 0, 0, 0) }
        btnRow.addView(addBtn)
        root.addView(btnRow)

        // Flattened in day order — ALL routines on one fixed page (v2.5.1,
        // Mahesh: "all in one page is ok, no need for pagination"). The region
        // flexes (weight 1) and each card takes an equal share, so more
        // routines simply make shorter cards instead of pushing off-screen.
        val routines = db.getRoutines()
        val ordered = mutableListOf<Routine>()
        val grouped = routines.groupBy { it.weekday }
        for (day in listOf(0, 1, 2, 3, 4, 5, 6, -1)) grouped[day]?.let { ordered.addAll(it) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        if (ordered.isEmpty()) box.addView(caption("No routines yet."))
        for (r in ordered) {
            val card = cardLayout(tappable = true) { startActivity(Intent(this, RoutineEditActivity::class.java).apply { putExtra("routineId", r.id) }) }
            (card.layoutParams as LinearLayout.LayoutParams).apply { height = 0; weight = 1f; setMargins(0, dp(4), 0, dp(4)) }
            card.setPadding(dp(12), dp(6), dp(12), dp(6))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT) }
            val muscles = db.routineMuscles(r.id)
            row.addView(BodyMapView(this, muscles).apply { layoutParams = LinearLayout.LayoutParams(dp(38), dp(46)) })
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(10), 0, 0, 0) }
            val nm = makeText(r.name, 15f, true)
            nm.maxLines = 1; nm.ellipsize = android.text.TextUtils.TruncateAt.END
            (nm.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
            info.addView(nm)
            val sub = makeText("${DateUtil.dayName(r.weekday)} • ${db.routineExerciseCount(r.id)} exercises • ${musclesLabel(muscles)} • tap to edit", 11f, false, Theme.textSecondary)
            sub.maxLines = 1; sub.ellipsize = android.text.TextUtils.TruncateAt.END
            (sub.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
            info.addView(sub)
            row.addView(info)
            row.addView(iconView(R.drawable.ic_chevron, 20))
            card.addView(row)
            box.addView(card)
        }
        root.addView(box)
    }
}
