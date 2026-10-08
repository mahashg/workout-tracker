package com.mahesh.workouttracker

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private val EXPORT_REQ = 2001
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); db = DbHelper(this); render() }
    private fun render() {
        val root = tabScaffold("Settings")
        // Explicit card budgets (v2.5.1): title 40 + cards (see heights) + gaps
        // must sum under the tab content height (~546dp at a 640dp viewport).
        val compactSet = isCompactScreen()
        fun budgetCard(h: Int): LinearLayout {
            val c = cardLayout()
            c.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h)).apply { setMargins(0, dp(3), 0, dp(3)) }
            c.setPadding(dp(12), dp(6), dp(12), dp(6))
            return c
        }
        fun noMargin(t: android.widget.TextView, lines: Int = 1): android.widget.TextView {
            (t.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
            t.maxLines = lines; t.ellipsize = android.text.TextUtils.TruncateAt.END
            return t
        }
        root.addView(screenTitle("Settings").apply {
            (layoutParams as LinearLayout.LayoutParams).apply { height = dp(40); setMargins(0, 0, 0, 0) }
            gravity = android.view.Gravity.CENTER_VERTICAL
        })

        val unit = WeekManager.unit(this)
        val unitCard = budgetCard(if (compactSet) 116 else 124)
        unitCard.addView(noMargin(overline("Units")))
        unitCard.addView(noMargin(cardTitle("Weight unit: $unit")))
        unitCard.addView(noMargin(caption("Applies to labels, steppers and CSV export.")))
        val unitBtn = makeSecondaryButton("Switch to ${if (unit=="lb") "kg" else "lb"}") {
            WeekManager.setUnit(this, if (unit=="lb") "kg" else "lb"); render()
        }
        (unitBtn.layoutParams as LinearLayout.LayoutParams).apply { height = dp(44); setMargins(0, dp(4), 0, 0) }
        unitCard.addView(unitBtn)
        root.addView(unitCard)

        val beginnerOn = Beginner.beginnerMode(this)
        val begCard = budgetCard(if (compactSet) 116 else 124)
        begCard.addView(noMargin(overline("Beginner Mode")))
        begCard.addView(noMargin(cardTitle("Beginner Mode: ${if(beginnerOn) "ON" else "OFF"}")))
        begCard.addView(noMargin(caption("Shows posture checks, form cues and starter hints on every exercise. Stop if you feel sharp pain. (Default ON)"), 2))
        val begBtn = makeSecondaryButton(if(beginnerOn) "Turn Beginner Mode OFF" else "Turn Beginner Mode ON") { Beginner.setBeginnerMode(this, !beginnerOn); render() }
        (begBtn.layoutParams as LinearLayout.LayoutParams).apply { height = dp(44); setMargins(0, dp(4), 0, 0) }
        begCard.addView(begBtn)
        root.addView(begCard)

        // Training-week rule folded into About (keeps this screen one page tall).
        val dataCard = budgetCard(if (compactSet) 84 else 88)
        dataCard.addView(noMargin(overline("Your Data")))
        val dataRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)) }
        val exportBtn = makeButton("Export CSV") { launchExport() }
        (exportBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(44); setMargins(0, dp(4), dp(4), 0) }
        val restoreBtn = makeSecondaryButton("Restore Preloaded") {
            AlertDialog.Builder(this).setTitle("Restore routines?").setMessage("This replaces current routines/exercises with the preloaded v2 5 (with form links and warm-up/stretch items), but does NOT delete history sessions.")
                .setPositiveButton("Restore") { _, _ -> Seed.restore(db); Seed.markV2Applied(this); Toast.makeText(this,"Routines restored", Toast.LENGTH_SHORT).show() }
                .setNegativeButton("Cancel", null).show()
        }
        (restoreBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(44); setMargins(dp(4), dp(4), 0, 0) }
        dataRow.addView(exportBtn); dataRow.addView(restoreBtn)
        dataCard.addView(dataRow)
        root.addView(dataCard)

        val aboutCard = budgetCard(if (compactSet) 104 else 112)
        aboutCard.addView(noMargin(overline("About")))
        aboutCard.addView(noMargin(caption("Workout Tracker • Version 2.5.1 (v2.5.1)\nWeek runs Sunday–Saturday; the Week counter only advances if you completed at least 1 workout that week.\nForm videos are per exercise. Workout time is tracked per session. Preview cards browse a workout without starting it. Every screen fits one page — no scrolling."), 4))
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
