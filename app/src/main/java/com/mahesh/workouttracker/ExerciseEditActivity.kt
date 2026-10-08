package com.mahesh.workouttracker

import android.graphics.Color
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ExerciseEditActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var exerciseId: Long = -1
    private var routineId: Long = -1
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); db = DbHelper(this)
        exerciseId = intent.getLongExtra("exerciseId", -1); routineId = intent.getLongExtra("routineId", -1)
        render()
    }
    private fun render() {
        val existing = if (exerciseId > 0) db.getExercise(exerciseId) else null
        if (existing != null) routineId = existing.routineId
        val root = rootLayout(); setContentView(ScrollView(this).apply { addView(root) })
        root.addView(topBar(if(existing==null) "Add Exercise" else "Edit Exercise", existing?.name ?: ""))
        fun field(label: String, value: String, hint: String): EditText {
            root.addView(makeText(label, 13f, false, Theme.textSecondary))
            val et = EditText(this).apply { setText(value); this.hint=hint; styleEditText(this) }
            root.addView(et); return et
        }
        val nameEt = field("Name", existing?.name ?: "", "Exercise name")
        val types = listOf("Warmup","Main","Stretch")
        root.addView(makeText("Type", 13f, false, Theme.textSecondary))
        val typeSp = Spinner(this).apply { adapter = ArrayAdapter(this@ExerciseEditActivity, android.R.layout.simple_spinner_dropdown_item, types); setSelection(maxOf(0, types.indexOf(existing?.type ?: "Main"))) }
        root.addView(typeSp)
        val equips = listOf("Kettlebell","Dumbbell","Bodyweight","Machine","None")
        root.addView(makeText("Equipment", 13f, false, Theme.textSecondary))
        val equipSp = Spinner(this).apply { adapter = ArrayAdapter(this@ExerciseEditActivity, android.R.layout.simple_spinner_dropdown_item, equips); setSelection(maxOf(0, equips.indexOf(existing?.equipment ?: "None"))) }
        root.addView(equipSp)
        val setsEt = field("Default sets", (existing?.defaultSets ?: 3).toString(), "3")
        val repsEt = field("Target reps", existing?.targetReps ?: "", "10 or 8-12 or 40 sec")
        val weightEt = field("Default weight", existing?.defaultWeight ?: "", "Blank or BW or 20")
        val varEt = field("Variation / notes", existing?.variation ?: "", "Variation")
        val musEt = field("Target muscles", existing?.targetMuscles ?: "", "e.g. shoulders,chest,triceps")
        val postEt = field("Posture check (Beginner Mode)", existing?.postureCheck ?: "", "Check 1;Check 2;Check 3")
        val cuesEt = field("Form cues (; separated)", existing?.cues ?: "", "Cue 1;Cue 2;Cue 3")
        root.addView(makeText("Muscles: shoulders, traps, chest, triceps, biceps, back, core, quads, hamstrings, glutes, calves", 11f, false, Theme.textSecondary))
        val ytEt = field("YouTube URL (form video for this exercise)", existing?.youtubeUrl ?: "", "https://youtube...")
        root.addView(makeButton("Save Exercise") {
            val name = nameEt.text.toString().trim(); if (name.isEmpty()) { Toast.makeText(this,"Name required", Toast.LENGTH_SHORT).show(); return@makeButton }
            val sets = setsEt.text.toString().toIntOrNull() ?: 3
            if (existing == null) {
                val count = db.getExercises(routineId).size
                db.insertExercise(Exercise(0, routineId, name, types[typeSp.selectedItemPosition], equips[equipSp.selectedItemPosition], sets, repsEt.text.toString(), weightEt.text.toString(), varEt.text.toString(), ytEt.text.toString(), count, musEt.text.toString(), cuesEt.text.toString(), postEt.text.toString()))
            } else {
                db.updateExercise(existing.copy(name=name, type=types[typeSp.selectedItemPosition], equipment=equips[equipSp.selectedItemPosition], defaultSets=sets, targetReps=repsEt.text.toString(), defaultWeight=weightEt.text.toString(), variation=varEt.text.toString(), youtubeUrl=ytEt.text.toString(), targetMuscles=musEt.text.toString(), cues=cuesEt.text.toString(), postureCheck=postEt.text.toString()))
            }
            Toast.makeText(this,"Saved", Toast.LENGTH_SHORT).show(); finish()
        })
        root.addView(makeButton("Back") { finish() })
    }
}
