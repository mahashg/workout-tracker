package com.mahesh.workouttracker

import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
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
        val root = fitRoot(); setContentView(root)
        root.addView(topBar(if (existing == null) "Add Exercise" else "Edit Exercise", existing?.name ?: ""))

        // Compact two-half form: full form + Save visible with keyboard closed.
        fun halfCol(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(3), 0, dp(3), 0)
        }
        fun field(col: LinearLayout, label: String, value: String, hint: String): EditText {
            col.addView(makeText(label, 11f, false, Theme.textSecondary))
            val et = EditText(this).apply { setText(value); this.hint = hint; styleEditText(this); textSize = 13f; minHeight = dp(40); setPadding(dp(8), dp(4), dp(8), dp(4)) }
            col.addView(et); return et
        }
        fun rowOf(a: LinearLayout, b: LinearLayout): LinearLayout {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(a); r.addView(b); return r
        }

        val nameCol = halfCol(); val nameEt = field(nameCol, "Name", existing?.name ?: "", "Exercise name")
        root.addView(nameCol)

        val types = listOf("Warmup", "Main", "Stretch")
        val equips = listOf("Kettlebell", "Dumbbell", "Bodyweight", "Machine", "None")
        val typeCol = halfCol(); typeCol.addView(makeText("Type", 11f, false, Theme.textSecondary))
        val typeSp = Spinner(this).apply { adapter = ArrayAdapter(this@ExerciseEditActivity, android.R.layout.simple_spinner_dropdown_item, types); setSelection(maxOf(0, types.indexOf(existing?.type ?: "Main"))) }
        typeCol.addView(typeSp)
        val equipCol = halfCol(); equipCol.addView(makeText("Equipment", 11f, false, Theme.textSecondary))
        val equipSp = Spinner(this).apply { adapter = ArrayAdapter(this@ExerciseEditActivity, android.R.layout.simple_spinner_dropdown_item, equips); setSelection(maxOf(0, equips.indexOf(existing?.equipment ?: "None"))) }
        equipCol.addView(equipSp)
        root.addView(rowOf(typeCol, equipCol))

        val setsCol = halfCol(); val setsEt = field(setsCol, "Default sets", (existing?.defaultSets ?: 3).toString(), "3")
        val repsCol = halfCol(); val repsEt = field(repsCol, "Target reps", existing?.targetReps ?: "", "10, 8-12, 40 sec")
        root.addView(rowOf(setsCol, repsCol))

        val weightCol = halfCol(); val weightEt = field(weightCol, "Default weight", existing?.defaultWeight ?: "", "Blank, BW or 20")
        val varCol = halfCol(); val varEt = field(varCol, "Variation / notes", existing?.variation ?: "", "Variation")
        root.addView(rowOf(weightCol, varCol))

        val musCol = halfCol(); val musEt = field(musCol, "Target muscles", existing?.targetMuscles ?: "", "e.g. shoulders,chest")
        val postCol = halfCol(); val postEt = field(postCol, "Posture checks (; separated)", existing?.postureCheck ?: "", "Check 1;Check 2;Check 3")
        root.addView(rowOf(musCol, postCol))

        val cuesCol = halfCol(); val cuesEt = field(cuesCol, "Form cues (; separated)", existing?.cues ?: "", "Cue 1;Cue 2;Cue 3")
        val ytCol = halfCol(); val ytEt = field(ytCol, "YouTube URL (form video)", existing?.youtubeUrl ?: "", "https://youtube...")
        root.addView(rowOf(cuesCol, ytCol))

        val hint = makeText("Muscles: shoulders, traps, chest, triceps, biceps, back, core, quads, hamstrings, glutes, calves", 11f, false, Theme.textSecondary)
        hint.maxLines = 1; hint.ellipsize = android.text.TextUtils.TruncateAt.END
        root.addView(hint)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val saveBtn = makeButton("Save Exercise") {
            val name = nameEt.text.toString().trim(); if (name.isEmpty()) { Toast.makeText(this, "Name required", Toast.LENGTH_SHORT).show(); return@makeButton }
            val sets = setsEt.text.toString().toIntOrNull() ?: 3
            if (existing == null) {
                val count = db.getExercises(routineId).size
                db.insertExercise(Exercise(0, routineId, name, types[typeSp.selectedItemPosition], equips[equipSp.selectedItemPosition], sets, repsEt.text.toString(), weightEt.text.toString(), varEt.text.toString(), ytEt.text.toString(), count, musEt.text.toString(), cuesEt.text.toString(), postEt.text.toString()))
            } else {
                db.updateExercise(existing.copy(name = name, type = types[typeSp.selectedItemPosition], equipment = equips[equipSp.selectedItemPosition], defaultSets = sets, targetReps = repsEt.text.toString(), defaultWeight = weightEt.text.toString(), variation = varEt.text.toString(), youtubeUrl = ytEt.text.toString(), targetMuscles = musEt.text.toString(), cues = cuesEt.text.toString(), postureCheck = postEt.text.toString()))
            }
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show(); finish()
        }
        (saveBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48) }
        val backBtn = makeSecondaryButton("Back") { finish() }
        (backBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48) }
        btnRow.addView(saveBtn); btnRow.addView(backBtn)
        root.addView(btnRow)
    }
}
