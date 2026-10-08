package com.mahesh.workouttracker

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private val EXPORT_REQ = 2001
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); db = DbHelper(this); render() }
    // v2.8: re-render on resume so the Google section reflects sign-in state
    // when returning from the browser OAuth round-trip.
    override fun onResume() { super.onResume(); if (::db.isInitialized) render() }

    private fun render() {
        val root = tabScaffold("Settings")
        // Explicit card budgets (v2.5.1; v2.8 adds the Google Backup card and
        // tightens the rest — compact sum 34+110+110+152+78+60 = 544dp, under
        // the ~546dp available at a 640dp viewport; bottom nav always visible).
        val compactSet = isCompactScreen()
        fun budgetCard(h: Int): LinearLayout {
            val c = cardLayout()
            c.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h)).apply { setMargins(0, dp(2), 0, dp(2)) }
            c.setPadding(dp(12), dp(5), dp(12), dp(5))
            return c
        }
        fun noMargin(t: android.widget.TextView, lines: Int = 1): android.widget.TextView {
            (t.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
            t.maxLines = lines; t.ellipsize = android.text.TextUtils.TruncateAt.END
            return t
        }
        // Exact-height button: the factories set minHeight 56/52/48, which
        // silently inflates fixed-budget rows (the v2.6.1 lesson).
        fun fixBtn(b: Button, h: Int) {
            b.minHeight = 0; b.minWidth = 0
            (b.layoutParams as LinearLayout.LayoutParams).height = dp(h)
        }
        root.addView(screenTitle("Settings").apply {
            (layoutParams as LinearLayout.LayoutParams).apply { height = dp(if (compactSet) 34 else 40); setMargins(0, 0, 0, 0) }
            gravity = android.view.Gravity.CENTER_VERTICAL
        })

        val unit = WeekManager.unit(this)
        val unitCard = budgetCard(if (compactSet) 106 else 116)
        unitCard.addView(noMargin(overline("Units")))
        unitCard.addView(noMargin(cardTitle("Weight unit: $unit")))
        unitCard.addView(noMargin(caption("Applies to labels and CSV.")))
        val unitBtn = makeSecondaryButton("Switch to ${if (unit=="lb") "kg" else "lb"}") {
            WeekManager.setUnit(this, if (unit=="lb") "kg" else "lb"); render()
        }
        (unitBtn.layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(4), 0, 0) }
        fixBtn(unitBtn, 44)
        unitCard.addView(unitBtn)
        root.addView(unitCard)

        val beginnerOn = Beginner.beginnerMode(this)
        val begCard = budgetCard(if (compactSet) 106 else 116)
        begCard.addView(noMargin(overline("Beginner Mode")))
        begCard.addView(noMargin(cardTitle("Beginner Mode: ${if(beginnerOn) "ON" else "OFF"}")))
        begCard.addView(noMargin(caption("Posture checks and form cues on every exercise."), 1))
        val begBtn = makeSecondaryButton(if(beginnerOn) "Turn Beginner Mode OFF" else "Turn Beginner Mode ON") { Beginner.setBeginnerMode(this, !beginnerOn); render() }
        (begBtn.layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(4), 0, 0) }
        fixBtn(begBtn, 44)
        begCard.addView(begBtn)
        root.addView(begCard)

        root.addView(googleCard(if (compactSet) 148 else 170, ::fixBtn, ::noMargin))

        // Training-week rule folded into About (keeps this screen one page tall).
        val dataCard = budgetCard(if (compactSet) 74 else 84)
        dataCard.addView(noMargin(overline("Your Data")))
        val dataRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)) }
        val exportBtn = makeButton("Export CSV") { launchExport() }
        (exportBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; setMargins(0, dp(4), dp(4), 0) }
        fixBtn(exportBtn, 44)
        val restoreBtn = makeSecondaryButton("Restore Preloaded") {
            AlertDialog.Builder(this).setTitle("Restore routines?").setMessage("This replaces current routines/exercises with the preloaded v2 5 (with form links and warm-up/stretch items), but does NOT delete history sessions.")
                .setPositiveButton("Restore") { _, _ -> Seed.restore(db); Seed.markV2Applied(this); Toast.makeText(this,"Routines restored", Toast.LENGTH_SHORT).show() }
                .setNegativeButton("Cancel", null).show()
        }
        (restoreBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; setMargins(dp(4), dp(4), 0, 0) }
        fixBtn(restoreBtn, 44)
        dataRow.addView(exportBtn); dataRow.addView(restoreBtn)
        dataCard.addView(dataRow)
        root.addView(dataCard)

        val aboutCard = budgetCard(if (compactSet) 56 else 64)
        aboutCard.addView(noMargin(overline("About")))
        // v2.6.1 copy audit: About is two lines (version + week rule).
        aboutCard.addView(noMargin(caption("Workout Tracker • Version 2.8\nWeek counter advances only after a week with 1+ completed workout."), 2))
        root.addView(aboutCard)
    }

    /** Google Backup card (v2.8): sign-in state, Drive backup/restore.
     *  The stored Client ID is a public identifier, not a secret. */
    private fun googleCard(heightDp: Int, fixBtn: (Button, Int) -> Unit, noMargin: (android.widget.TextView, Int) -> android.widget.TextView): LinearLayout {
        val card = cardLayout()
        card.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)).apply { setMargins(0, dp(2), 0, dp(2)) }
        card.setPadding(dp(12), dp(5), dp(12), dp(5))
        card.addView(noMargin(overline("Google Backup"), 1))
        if (GoogleAuth.isSignedIn(this)) {
            val nameEmail = listOf(GoogleAuth.displayName(this), GoogleAuth.email(this)).filter { it.isNotBlank() }.joinToString(" • ")
            card.addView(noMargin(makeText(nameEmail.ifBlank { "Signed in" }, 14f, true), 1))
            val lastMs = GoogleAuth.lastBackupMs(this)
            val lastText = if (lastMs > 0L) java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US).format(java.util.Date(lastMs)) else "Never"
            card.addView(noMargin(caption("Last backup: $lastText"), 1))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)); setPadding(0, dp(4), 0, 0) }
            val backupBtn = makeButton("Backup now") { doBackup() }
            (backupBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; setMargins(0, 0, dp(4), 0) }
            fixBtn(backupBtn, 44)
            val restoreDriveBtn = makeSecondaryButton("Restore from Drive") { confirmRestore() }
            (restoreDriveBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; setMargins(dp(4), 0, 0, 0) }
            fixBtn(restoreDriveBtn, 44)
            row.addView(backupBtn); row.addView(restoreDriveBtn)
            card.addView(row)
            val signOutBtn = makeSecondaryButton("Sign out") { doSignOut() }
            (signOutBtn.layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(4), 0, 0) }
            fixBtn(signOutBtn, 36)
            card.addView(signOutBtn)
        } else {
            val setupLine = noMargin(caption("Client ID is prefilled and ready — sign in to back up to your Drive. Edit it only to use a different Google Cloud project."), 2)
            card.addView(setupLine)
            val idRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)); setPadding(0, dp(4), 0, 0) }
            val idEt = EditText(this).apply {
                setText(GoogleAuth.clientId(this@SettingsActivity))
                hint = "Google Client ID"
                styleEditText(this)
                textSize = 13f; minHeight = dp(40); isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            }
            val saveBtn = makeSmallButton("Save") {
                GoogleAuth.setClientId(this, idEt.text.toString())
                Toast.makeText(this, "Client ID saved", Toast.LENGTH_SHORT).show()
            }
            (saveBtn.layoutParams as LinearLayout.LayoutParams).apply { width = dp(68); setMargins(dp(6), 0, 0, 0) }
            fixBtn(saveBtn, 40)
            idRow.addView(idEt); idRow.addView(saveBtn)
            card.addView(idRow)
            val signInBtn = makeButton("Sign in with Google") {
                // Save whatever is typed first, so one paste is enough.
                val typed = idEt.text.toString().trim()
                if (typed.isNotBlank() && typed != GoogleAuth.clientId(this)) GoogleAuth.setClientId(this, typed)
                if (GoogleAuth.clientId(this).isBlank()) {
                    AlertDialog.Builder(this).setTitle("Client ID needed")
                        .setMessage("The Google Client ID field is empty. It is normally prefilled — paste a Client ID from your Google Cloud project to continue.")
                        .setPositiveButton("OK", null).show()
                } else if (!GoogleAuth.startSignIn(this)) {
                    Toast.makeText(this, "Could not start Google sign-in", Toast.LENGTH_LONG).show()
                }
            }
            (signInBtn.layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(4), 0, 0) }
            fixBtn(signInBtn, 44)
            card.addView(signInBtn)
        }
        return card
    }

    private fun doBackup() {
        val dlg = progressDialog("Backing up…")
        dlg.show()
        Bg.run({ DriveBackup.backupNow(this, db) }, { _, ex ->
            dlg.dismiss()
            if (ex != null) Toast.makeText(this, "Backup failed: ${ex.message}", Toast.LENGTH_LONG).show()
            else { Toast.makeText(this, "Backup complete", Toast.LENGTH_SHORT).show(); render() }
        })
    }

    private fun confirmRestore() {
        AlertDialog.Builder(this).setTitle("Restore from Drive?")
            .setMessage("Replace all data on this phone with the Drive backup?")
            .setPositiveButton("Restore") { _, _ ->
                val dlg = progressDialog("Restoring…")
                dlg.show()
                Bg.run({ DriveBackup.restoreNow(this, db) }, { counts, ex ->
                    dlg.dismiss()
                    if (ex != null) Toast.makeText(this, "Restore failed: ${ex.message}", Toast.LENGTH_LONG).show()
                    else Toast.makeText(this, "Restored: ${counts?.optInt("routines") ?: 0} routines, ${counts?.optInt("sessions") ?: 0} sessions", Toast.LENGTH_LONG).show()
                    render()
                })
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun doSignOut() {
        val dlg = progressDialog("Signing out…")
        dlg.show()
        Bg.run({ GoogleAuth.signOut(this) }, { _, _ ->
            dlg.dismiss()
            Toast.makeText(this, "Signed out", Toast.LENGTH_SHORT).show()
            render()
        })
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
