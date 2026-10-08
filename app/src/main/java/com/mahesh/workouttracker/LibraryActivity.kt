package com.mahesh.workouttracker

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Exercise library: 33 extra moves (3 per muscle group) as a pool to add to routines.
 *  v2.5: one muscle group per fixed page, switched with the chip rows on top. */
class LibraryActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var groupIndex: Int = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); db = DbHelper(this); render()
    }

    private fun render() {
        val groups = Library.groups()
        if (groupIndex !in groups.indices) groupIndex = 0
        val group = groups[groupIndex]
        val root = fitRoot(); setContentView(root)
        root.addView(topBar("Exercise Library", "33 moves"))

        // Group switcher: wrapping chip rows (selected group highlighted).
        // Explicit 126dp (3 rows x 42dp) so the card region budget is known.
        val chipBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(126)) }
        var chipRow: LinearLayout? = null
        for ((i, g) in groups.withIndex()) {
            if (i % 4 == 0) {
                chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)) }
                chipBox.addView(chipRow)
            }
            val selected = i == groupIndex
            val chip = makeSmallButton(g) { groupIndex = i; render() }
            chip.textSize = 12f
            if (selected) { chip.setTextColor(Theme.onPrimary); chip.background = roundedBg("#2563EB", 12) }
            (chip.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(40); setMargins(dp(2), dp(2), dp(2), dp(2)) }
            chipRow!!.addView(chip)
        }
        root.addView(chipBox)

        // Compact group header with body map
        val entries = Library.byGroup(group)
        val groupMuscles = entries.flatMap { DbHelper.parseMuscles(it.muscles, it.name) }.toSet()
        val header = cardLayout()
        header.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(76)).apply { setMargins(0, 0, 0, 0) }
        header.setPadding(dp(12), dp(6), dp(12), dp(6))
        val hRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hRow.addView(BodyMapView(this, groupMuscles).apply { layoutParams = LinearLayout.LayoutParams(dp(44), dp(54)) })
        val hInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(10), 0, 0, 0) }
        hInfo.addView(makeText(group, 18f, true))
        hInfo.addView(caption("Targets: ${musclesLabel(groupMuscles)}"))
        hRow.addView(hInfo); header.addView(hRow)
        root.addView(header)

        // The group's 3 exercises share the remaining height.
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        for (entry in entries) {
            val card = cardLayout()
            (card.layoutParams as LinearLayout.LayoutParams).apply { height = 0; weight = 1f; setMargins(0, dp(4), 0, dp(4)) }
            card.setPadding(dp(12), dp(6), dp(12), dp(6))
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            top.addView(BodyMapView(this, DbHelper.parseMuscles(entry.muscles, entry.name)).apply { layoutParams = LinearLayout.LayoutParams(dp(38), dp(46)) })
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(8), 0, 0, 0) }
            val name = makeText(entry.name, 15f, true)
            name.maxLines = 1; name.ellipsize = android.text.TextUtils.TruncateAt.END
            info.addView(name)
            val badgeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            badgeRow.addView(equipmentBadge(entry.equipment))
            badgeRow.addView(caption("Do ${entry.defaultSets} sets of ${entry.targetReps} • ${musclesLabel(DbHelper.parseMuscles(entry.muscles, entry.name))}"))
            info.addView(badgeRow)
            val cues = makeText("Do it: " + entry.cues.replace(";", " • "), 12f, false)
            cues.maxLines = 1; cues.ellipsize = android.text.TextUtils.TruncateAt.END
            info.addView(cues)
            if (entry.postureCheck.isNotBlank()) {
                val post = caption("Posture: " + entry.postureCheck.replace(";", " • "))
                post.maxLines = 1; post.ellipsize = android.text.TextUtils.TruncateAt.END
                info.addView(post)
            }
            top.addView(info); card.addView(top)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val watch = smallButtonWithIcon("Watch Form", R.drawable.ic_play) { openUrl(this, Library.urlFor(entry)) }
            (watch.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48) }
            val add = smallButtonWithIcon("Add to Routine", R.drawable.ic_check) { addToRoutine(entry) }
            (add.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48) }
            row.addView(watch); row.addView(add)
            card.addView(row)
            box.addView(card)
        }
        root.addView(box)
        // v2.6.1 copy audit: "Group x of 11" footer removed — the highlighted
        // chip above already shows where you are.
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
