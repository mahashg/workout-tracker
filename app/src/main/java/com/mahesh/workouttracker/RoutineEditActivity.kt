package com.mahesh.workouttracker

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class RoutineEditActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var routineId: Long = -1
    private var exercisePage: Int = 0
    // Form state preserved across re-renders (pagination must not lose typing)
    private var formLoaded = false
    private var nameVal = ""; private var focusVal = ""; private var notesVal = ""; private var dayPos = 7

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); db = DbHelper(this); routineId = intent.getLongExtra("routineId", -1); render() }
    override fun onResume() { super.onResume(); if (::db.isInitialized && routineId > 0) render() }

    private fun pickFromLibrary(existing: Routine) {
        val labels = Library.entries.map { "${it.group} • ${it.name} (${it.equipment})" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Pick from Library")
            .setItems(labels) { _, which ->
                val entry = Library.entries[which]
                val nextOrder = db.getExercises(existing.id).size
                db.insertExercise(Exercise(0, existing.id, entry.name, "Main", entry.equipment, entry.defaultSets, entry.targetReps, "", "", Library.urlFor(entry), nextOrder, entry.muscles, entry.cues, entry.postureCheck))
                Toast.makeText(this, "Added ${entry.name}", Toast.LENGTH_SHORT).show(); render()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun tinyBtn(text: String, w: Int, onClick: () -> Unit): Button {
        val b = makeSmallButton(text, onClick)
        b.minWidth = 0; b.minHeight = 0; b.setPadding(0, 0, 0, 0); b.textSize = 11f
        (b.layoutParams as LinearLayout.LayoutParams).apply { width = dp(w); height = dp(40) }
        return b
    }

    private fun render() {
        val existing = if (routineId > 0) db.getRoutine(routineId) else null
        if (!formLoaded) {
            nameVal = existing?.name ?: ""; focusVal = existing?.focus ?: ""; notesVal = existing?.notes ?: ""
            dayPos = when (existing?.weekday) { null -> 7; -1 -> 7; else -> existing.weekday }
            formLoaded = true
        }
        val root = fitRoot(); setContentView(root)
        root.addView(topBar(if (existing == null) "Add Workout" else "Edit Workout", existing?.name ?: ""))

        if (existing != null) {
            // Explicit 64dp header budget (v2.5.1).
            val header = cardLayout("#2563EB")
            header.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)).apply { setMargins(0, 0, 0, 0) }
            header.setPadding(dp(12), dp(6), dp(12), dp(6))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val muscles = db.routineMuscles(existing.id)
            row.addView(BodyMapView(this, muscles, true).apply { layoutParams = LinearLayout.LayoutParams(dp(36), dp(44)) })
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(10), 0, 0, 0) }
            info.addView(makeText(existing.name, 16f, true))
            info.addView(caption("${existing.focus} • ${DateUtil.dayName(existing.weekday)} • Targets: ${musclesLabel(muscles)}"))
            row.addView(info); header.addView(row); root.addView(header)
        }

        // Compact two-half form (fits one screen with the keyboard closed)
        // Build into holders first so we can lay them out in rows
        val formBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(formBox)
        val rowA = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val nameCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(3), 0, dp(3), 0) }
        nameCol.addView(makeText("Name", 11f, false, Theme.textSecondary))
        val nameEt = EditText(this).apply { setText(nameVal); hint = "Workout name"; styleEditText(this); textSize = 13f; minHeight = dp(40); setPadding(dp(8), dp(4), dp(8), dp(4)) }
        nameCol.addView(nameEt)
        val focusCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(3), 0, dp(3), 0) }
        focusCol.addView(makeText("Muscle focus", 11f, false, Theme.textSecondary))
        val focusEt = EditText(this).apply { setText(focusVal); hint = "Chest, Triceps"; styleEditText(this); textSize = 13f; minHeight = dp(40); setPadding(dp(8), dp(4), dp(8), dp(4)) }
        focusCol.addView(focusEt)
        rowA.addView(nameCol); rowA.addView(focusCol)
        formBox.addView(rowA)
        val rowB = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dayCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(3), 0, dp(3), 0) }
        dayCol.addView(makeText("Assigned day", 11f, false, Theme.textSecondary))
        val daySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@RoutineEditActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Unassigned"))
            setSelection(dayPos)
        }
        dayCol.addView(daySpinner)
        val notesCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(3), 0, dp(3), 0) }
        notesCol.addView(makeText("Notes", 11f, false, Theme.textSecondary))
        val notesEt = EditText(this).apply { setText(notesVal); hint = "Notes"; styleEditText(this); textSize = 13f; minHeight = dp(40); setPadding(dp(8), dp(4), dp(8), dp(4)) }
        notesCol.addView(notesEt)
        rowB.addView(dayCol); rowB.addView(notesCol)
        formBox.addView(rowB)

        // Deterministic form budget (v2.5.1): each field row is 58dp
        // (14dp label + 40dp field + 4dp slack); spinners get the field height.
        for (row in listOf(rowA, rowB)) {
            row.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58))
            for (ci in 0 until row.childCount) {
                val col = row.getChildAt(ci) as LinearLayout
                val label = col.getChildAt(0)
                (label.layoutParams as LinearLayout.LayoutParams).apply { height = dp(14); setMargins(0, 0, 0, 0) }
                val field = col.getChildAt(1)
                (field.layoutParams as? LinearLayout.LayoutParams)?.apply { height = dp(40); setMargins(0, 0, 0, 0) }
                if (field is EditText) field.minHeight = dp(40)
            }
        }

        fun capture() {
            nameVal = nameEt.text.toString(); focusVal = focusEt.text.toString(); notesVal = notesEt.text.toString(); dayPos = daySpinner.selectedItemPosition
        }

        val saveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)) }
        val saveBtn = makeButton("Save Workout") {
            capture()
            val name = nameVal.trim(); if (name.isEmpty()) { Toast.makeText(this, "Name required", Toast.LENGTH_SHORT).show(); return@makeButton }
            val wd = if (dayPos == 7) -1 else dayPos
            if (existing == null) {
                routineId = db.insertRoutine(Routine(0, name, focusVal, wd, "", notesVal))
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show(); render()
            } else {
                // preserve deprecated routines.youtubeUrl column, unused in v2 UI
                db.updateRoutine(Routine(existing.id, name, focusVal, wd, existing.youtubeUrl, notesVal))
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            }
        }
        (saveBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48); setMargins(0, 0, 0, 0) }
        saveRow.addView(saveBtn)
        saveRow.addView(makeText("  Form videos are per exercise below.", 11f, false, Theme.textSecondary).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        formBox.addView(saveRow)

        if (existing != null) {
            val exs = db.getExercises(existing.id)
            val pageSize = (((screenHeightDp() - 470) / 54).coerceIn(3, 5))
            val pageCount = ((exs.size + pageSize - 1) / pageSize).coerceAtLeast(1)
            if (exercisePage !in 0 until pageCount) exercisePage = pageCount - 1
            root.addView(makeText("Exercises (${exs.size})", 15f, true).apply {
                (layoutParams as LinearLayout.LayoutParams).apply { height = dp(24); setMargins(0, 0, 0, 0) }
                gravity = Gravity.CENTER_VERTICAL
            })
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            var lastSection = ""
            for (idx in (exercisePage * pageSize) until minOf(exs.size, (exercisePage + 1) * pageSize)) {
                val e = exs[idx]
                val section = when (e.type.lowercase()) { "warmup" -> "Warm-up"; "stretch" -> "Stretching"; else -> "Main" }
                if (section != lastSection) { box.addView(makeText(section, 12f, true, Color.parseColor("#92400E"))); lastSection = section }
                val card = cardLayout()
                (card.layoutParams as LinearLayout.LayoutParams).apply { height = 0; weight = 1f; setMargins(0, dp(3), 0, dp(3)) }
                card.setPadding(dp(10), dp(4), dp(10), dp(4))
                val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT) }
                val mus = DbHelper.parseMuscles(e.targetMuscles, e.name)
                if (mus.isNotEmpty()) top.addView(BodyMapView(this, mus).apply { layoutParams = LinearLayout.LayoutParams(dp(28), dp(34)) })
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(6), 0, 0, 0) }
                val nm = makeText("${idx + 1}. ${e.name}", 13f, true)
                nm.maxLines = 1; nm.ellipsize = android.text.TextUtils.TruncateAt.END
                info.addView(nm)
                info.addView(caption("${e.equipment} • ${e.defaultSets} sets • ${e.targetReps}"))
                top.addView(info)
                if (e.youtubeUrl.isNotBlank()) top.addView(tinyBtn("▶", 34) { openUrl(this, e.youtubeUrl) })
                if (idx > 0) top.addView(tinyBtn("↑", 34) { capture(); db.swapExerciseOrder(e, exs[idx - 1]); render() })
                if (idx < exs.size - 1) top.addView(tinyBtn("↓", 34) { capture(); db.swapExerciseOrder(e, exs[idx + 1]); render() })
                top.addView(tinyBtn("Edit", 46) { capture(); startActivity(Intent(this, ExerciseEditActivity::class.java).apply { putExtra("exerciseId", e.id); putExtra("routineId", existing.id) }) })
                top.addView(tinyBtn("Del", 40) { capture(); AlertDialog.Builder(this).setTitle("Delete exercise?").setPositiveButton("Delete") { _, _ -> db.deleteExercise(e.id); render() }.setNegativeButton("Cancel", null).show() })
                card.addView(top)
                box.addView(card)
            }
            if (exs.isEmpty()) box.addView(caption("No exercises yet — add one below."))
            root.addView(box)
            if (pageCount > 1) root.addView(pagerBar(exercisePage, pageCount, { capture(); exercisePage--; render() }, { capture(); exercisePage++; render() }))

            // Fixed action bars (2 x 48dp, zero button margins)
            val actRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)) }
            val addEx = makeButton("Add Exercise") { capture(); startActivity(Intent(this, ExerciseEditActivity::class.java).apply { putExtra("exerciseId", -1L); putExtra("routineId", existing.id) }) }
            (addEx.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48); setMargins(0, 0, dp(4), 0) }
            val pick = makeSecondaryButton("Pick from Library") { capture(); pickFromLibrary(existing) }
            (pick.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48); setMargins(dp(4), 0, 0, 0) }
            actRow.addView(addEx); actRow.addView(pick)
            root.addView(actRow)
            val actRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)) }
            val del = makeSmallButton("Delete Workout") {
                AlertDialog.Builder(this).setTitle("Delete workout?").setMessage("Exercises will be deleted. History sessions stay.")
                    .setPositiveButton("Delete") { _, _ -> db.deleteRoutine(existing.id); finish() }.setNegativeButton("Cancel", null).show()
            }
            (del.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48); setMargins(0, 0, 0, 0) }
            actRow2.addView(del)
            root.addView(actRow2)
        }
        // v2.6: no Back buttons — exit via the corner back control in topBar.
    }
}
