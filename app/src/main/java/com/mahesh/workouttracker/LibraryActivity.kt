package com.mahesh.workouttracker

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Exercise library: 33 extra moves (3 per muscle group) as a pool to add to routines. */
class LibraryActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); db = DbHelper(this); render()
    }

    private fun render() {
        val root = rootLayout(); setContentView(ScrollView(this).apply { addView(root) })
        root.addView(topBar("Exercise Library", "33 extra moves • pick & add to a routine"))
        root.addView(caption("Your 5 daily workouts stay the same. This is a pool of extra exercises grouped by muscle — add any of them to a routine. Form videos open YouTube search results for that exercise."))
        for (group in Library.groups()) {
            root.addView(sectionLabelText(group))
            val entries = Library.byGroup(group)
            // body-map thumbnail for the group
            val groupCard = cardLayout()
            val muscles = entries.flatMap { DbHelper.parseMuscles(it.muscles, it.name) }.toSet()
            groupCard.addView(BodyMapView(this, muscles).apply { layoutParams = LinearLayout.LayoutParams(dp(96), dp(120)) })
            groupCard.addView(caption("Targets: ${musclesLabel(muscles)}"))
            root.addView(groupCard)
            for (entry in entries) {
                val card = cardLayout()
                val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                top.addView(BodyMapView(this, DbHelper.parseMuscles(entry.muscles, entry.name)).apply { layoutParams = LinearLayout.LayoutParams(dp(52), dp(66)) })
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(8), 0, 0, 0) }
                info.addView(makeText(entry.name, 16f, true))
                info.addView(equipmentBadge(entry.equipment))
                info.addView(caption("Targets: ${musclesLabel(DbHelper.parseMuscles(entry.muscles, entry.name))}"))
                info.addView(bodyText("Do ${entry.defaultSets} sets of ${entry.targetReps}"))
                info.addView(bodyText("Do it: " + entry.cues.replace(";", " • ")))
                if (entry.postureCheck.isNotBlank()) info.addView(caption("Posture: " + entry.postureCheck.replace(";", " • ")))
                top.addView(info); card.addView(top)
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(smallButtonWithIcon("Watch Form", R.drawable.ic_play) { openUrl(this, Library.urlFor(entry)) })
                row.addView(smallButtonWithIcon("Add to Routine", R.drawable.ic_check) { addToRoutine(entry) })
                card.addView(row)
                root.addView(card)
            }
        }
    }

    private fun addToRoutine(entry: Library.Entry) {
        val routines = db.getRoutines()
        if (routines.isEmpty()) { Toast.makeText(this, "Create a routine first", Toast.LENGTH_SHORT).show(); return }
        val names = routines.map { "${it.name} (${DateUtil.dayName(it.weekday)})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Add \"${entry.name}\" to…")
            .setItems(names) { _, which ->
                val routine = routines[which]
                val nextOrder = db.getExercises(routine.id).size
                db.insertExercise(Exercise(
                    0, routine.id, entry.name, "Main", entry.equipment, entry.defaultSets, entry.targetReps, "", "",
                    Library.urlFor(entry), nextOrder, entry.muscles, entry.cues, entry.postureCheck
                ))
                Toast.makeText(this, "Added to ${routine.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
