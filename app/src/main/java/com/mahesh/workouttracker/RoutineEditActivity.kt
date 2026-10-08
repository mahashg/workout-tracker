package com.mahesh.workouttracker

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class RoutineEditActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var routineId: Long = -1
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); db = DbHelper(this); routineId = intent.getLongExtra("routineId", -1); render() }
    override fun onResume() { super.onResume(); if (::db.isInitialized && routineId > 0) render() }
    private fun render() {
        val existing = if (routineId > 0) db.getRoutine(routineId) else null
        val root = rootLayout(); setContentView(ScrollView(this).apply { addView(root) })
        root.addView(topBar(if (existing==null) "Add Workout" else "Edit Workout", existing?.name ?: ""))
        if (existing != null) {
            val header = cardLayout("#2563EB")
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val muscles = db.routineMuscles(existing.id)
            row.addView(BodyMapView(this, muscles, true).apply { layoutParams = LinearLayout.LayoutParams(dp(110), dp(138)) })
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(10),0,0,0) }
            info.addView(makeText(existing.name, 18f, true))
            info.addView(makeText("Targets: ${musclesLabel(muscles)}\n${existing.focus} • ${DateUtil.dayName(existing.weekday)}", 12f, false, Theme.textSecondary))
            row.addView(info); header.addView(row); root.addView(header)
        }
        val nameEt = EditText(this).apply { setText(existing?.name ?: ""); hint="Workout name"; styleEditText(this) }
        val focusEt = EditText(this).apply { setText(existing?.focus ?: ""); hint="Muscle focus (e.g. Chest, Triceps)"; styleEditText(this) }
        val notesEt = EditText(this).apply { setText(existing?.notes ?: ""); hint="Notes"; styleEditText(this) }
        val daySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@RoutineEditActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Unassigned"))
            setSelection(when(existing?.weekday){ null->7; -1->7; else->existing.weekday })
        }
        root.addView(makeText("Name", 13f, false, Theme.textSecondary)); root.addView(nameEt)
        root.addView(makeText("Muscle focus", 13f, false, Theme.textSecondary)); root.addView(focusEt)
        root.addView(makeText("Assigned day", 13f, false, Theme.textSecondary)); root.addView(daySpinner)
        root.addView(makeText("Notes", 13f, false, Theme.textSecondary)); root.addView(notesEt)
        root.addView(makeText("Form videos are per exercise below (▶ Watch Form), not per workout.", 12f, false, Color.parseColor("#FDE68A")))
        root.addView(makeButton("Save Workout") {
            val name = nameEt.text.toString().trim(); if (name.isEmpty()) { Toast.makeText(this,"Name required", Toast.LENGTH_SHORT).show(); return@makeButton }
            val wd = if (daySpinner.selectedItemPosition==7) -1 else daySpinner.selectedItemPosition
            if (existing==null) {
                routineId = db.insertRoutine(Routine(0,name,focusEt.text.toString(),wd,"",notesEt.text.toString()))
                Toast.makeText(this,"Saved", Toast.LENGTH_SHORT).show(); render()
            } else {
                // preserve deprecated routines.youtubeUrl column, unused in v2 UI
                db.updateRoutine(Routine(existing.id,name,focusEt.text.toString(),wd,existing.youtubeUrl,notesEt.text.toString()))
                Toast.makeText(this,"Saved", Toast.LENGTH_SHORT).show()
            }
        })
        if (existing != null) {
            root.addView(makeText("Exercises", 18f, true))
            val exs = db.getExercises(existing.id)
            var lastSection = ""
            for ((idx, e) in exs.withIndex()) {
                val section = when(e.type.lowercase()){ "warmup"->"Warm-up"; "stretch"->"Stretching"; else->"Main" }
                if (section!=lastSection) { root.addView(sectionHeader(section)); lastSection=section }
                val card = cardLayout()
                val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val mus = DbHelper.parseMuscles(e.targetMuscles, e.name)
                if (mus.isNotEmpty()) top.addView(BodyMapView(this, mus).apply { layoutParams = LinearLayout.LayoutParams(dp(52), dp(66)) })
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(8),0,0,0) }
                info.addView(makeText("${idx+1}. ${e.name}", 15f, true))
                val brow = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
                brow.addView(equipmentBadge(e.equipment)); info.addView(brow)
                info.addView(makeText("${e.type} • ${e.defaultSets} sets • ${e.targetReps}${if(mus.isNotEmpty()) "\nTargets: ${musclesLabel(mus)}" else ""}", 12f, false, Theme.textSecondary))
                top.addView(info); card.addView(top)
                if (e.youtubeUrl.isNotBlank()) card.addView(makeSmallButton("▶ Watch Form") { openUrl(this, e.youtubeUrl) })
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                if (idx>0) row.addView(makeSmallButton("↑") { db.swapExerciseOrder(e, exs[idx-1]); render() })
                if (idx<exs.size-1) row.addView(makeSmallButton("↓") { db.swapExerciseOrder(e, exs[idx+1]); render() })
                row.addView(makeSmallButton("Edit") { startActivity(Intent(this, ExerciseEditActivity::class.java).apply{ putExtra("exerciseId", e.id); putExtra("routineId", existing.id) }) })
                row.addView(makeSmallButton("Delete") { AlertDialog.Builder(this).setTitle("Delete exercise?").setPositiveButton("Delete"){_,_-> db.deleteExercise(e.id); render()}.setNegativeButton("Cancel",null).show() })
                card.addView(row); root.addView(card)
            }
            root.addView(makeButton("＋ Add Exercise") { startActivity(Intent(this, ExerciseEditActivity::class.java).apply{ putExtra("exerciseId", -1L); putExtra("routineId", existing.id) }) })
            root.addView(makeButton("🗑 Delete This Workout") {
                AlertDialog.Builder(this).setTitle("Delete workout?").setMessage("Exercises will be deleted. History sessions stay.")
                    .setPositiveButton("Delete"){_,_-> db.deleteRoutine(existing.id); finish()}.setNegativeButton("Cancel",null).show()
            })
        }
        root.addView(makeButton("Back") { finish() })
    }
}
