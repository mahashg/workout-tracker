package com.mahesh.workouttracker

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private val EXPORT_REQ = 2001
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); db = DbHelper(this); render() }
    private fun render() {
        val root = tabScaffold("Settings")
        root.addView(screenTitle("Settings"))

        val unit = WeekManager.unit(this)
        val unitCard = cardLayout()
        unitCard.addView(overline("Units"))
        unitCard.addView(cardTitle("Weight unit: $unit"))
        unitCard.addView(caption("Applies to labels, steppers and CSV export."))
        unitCard.addView(makeSecondaryButton("Switch to ${if (unit=="lb") "kg" else "lb"}") {
            WeekManager.setUnit(this, if (unit=="lb") "kg" else "lb"); render()
        })
        root.addView(unitCard)

        val beginnerOn = Beginner.beginnerMode(this)
        val begCard = cardLayout()
        begCard.addView(overline("Beginner Mode"))
        begCard.addView(cardTitle("Beginner Mode: ${if(beginnerOn) "ON" else "OFF"}"))
        begCard.addView(caption("Shows posture checks, form cues and starter hints on every exercise. Stop if you feel sharp pain. (Default ON)"))
        begCard.addView(makeSecondaryButton(if(beginnerOn) "Turn Beginner Mode OFF" else "Turn Beginner Mode ON") { Beginner.setBeginnerMode(this, !beginnerOn); render() })
        root.addView(begCard)

        val weekCard = cardLayout()
        weekCard.addView(overline("Training Week"))
        weekCard.addView(bodyText("Week runs Sunday–Saturday."))
        weekCard.addView(caption("The Week counter only advances if you completed at least 1 workout that week."))
        root.addView(weekCard)

        val dataCard = cardLayout()
        dataCard.addView(overline("Your Data"))
        dataCard.addView(makeButton("⬇ Export CSV") { launchExport() })
        dataCard.addView(makeSecondaryButton("Restore Preloaded Routines") {
            AlertDialog.Builder(this).setTitle("Restore routines?").setMessage("This replaces current routines/exercises with the preloaded v2 5 (with form links and warm-up/stretch items), but does NOT delete history sessions.")
                .setPositiveButton("Restore") { _, _ -> Seed.restore(db); Seed.markV2Applied(this); Toast.makeText(this,"Routines restored", Toast.LENGTH_SHORT).show() }
                .setNegativeButton("Cancel", null).show()
        })
        root.addView(dataCard)

        val aboutCard = cardLayout()
        aboutCard.addView(overline("About"))
        aboutCard.addView(caption("Workout Tracker • Version 2.2 (v2.2)\nForm videos are per exercise."))
        root.addView(aboutCard)
    }
    private fun launchExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="text/csv"; putExtra(Intent.EXTRA_TITLE, "workout-export.csv") }
        startActivityForResult(intent, EXPORT_REQ)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode==EXPORT_REQ && resultCode== Activity.RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) {
                try { val csv = CsvExporter.buildCsv(this, db); contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }; Toast.makeText(this,"CSV exported", Toast.LENGTH_SHORT).show() }
                catch (e: Exception) { Toast.makeText(this,"Export failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
}
