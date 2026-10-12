package com.mahesh.workouttracker

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes

class SettingsActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private val EXPORT_REQ = 2001
    private val AUTH_REQ = 2002
    // Google action waiting on an interactive authorize: "signin", "backup",
    // "restore" (re-confirm), or "restore-run" (already confirmed).
    private var pendingAction: String? = null
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); db = DbHelper(this); render() }
    // Re-render on resume so the Google section reflects the latest
    // sign-in/backup state whenever Settings comes back to the front.
    override fun onResume() { super.onResume(); if (::db.isInitialized) render() }

    private fun render() {
        val root = tabScaffold("Settings")
        // Explicit card budgets (v2.5.1; v2.9's simpler Google card shrinks
        // the signed-out state — compact sums: signed-out 34+106+106+118+74+56
        // = 494dp, signed-in 34+106+106+144+74+56 = 520dp, both under the
        // ~546dp available at a 640dp viewport; bottom nav always visible).
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

        val signedIn = GoogleAuth.isSignedIn(this)
        root.addView(googleCard(
            if (signedIn) (if (compactSet) 144 else 158) else (if (compactSet) 118 else 128),
            ::fixBtn, ::noMargin))

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
        aboutCard.addView(noMargin(caption("Workout Tracker • Version 2.9\nWeek counter advances only after a week with 1+ completed workout."), 2))
        root.addView(aboutCard)
    }

    /** Google Backup card (v2.9): GIS sign-in state, Drive backup/restore.
     *  No IDs to paste — Play services identifies the app by its package +
     *  signing key via the Android OAuth client in Mahesh's Cloud project. */
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
            card.addView(noMargin(caption("Sign in with Google to back up your workouts to Drive. The app is identified by the Android client registered for this package and signing key — nothing to paste or set up here."), 3))
            val signInBtn = makeButton("Sign in with Google") { startGoogleAuth("signin") }
            (signInBtn.layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(4), 0, 0) }
            fixBtn(signInBtn, 44)
            card.addView(signInBtn)
        }
        return card
    }

    // ----- Google Identity Services authorization (v2.9) -----

    /** Runs the SDK authorize() for [action]; Google may answer inline or
     *  ask for a resolution UI (account pick / consent), launched via
     *  startIntentSenderForResult and parsed in onActivityResult. */
    private fun startGoogleAuth(action: String) {
        pendingAction = action
        GoogleAuth.client(this).authorize(GoogleAuth.authorizationRequest(this))
            .addOnSuccessListener { result -> handleAuthResult(result) }
            .addOnFailureListener { e ->
                pendingAction = null
                if (e is ApiException) showAuthError(e) else Toast.makeText(this, "Google sign-in failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun handleAuthResult(result: AuthorizationResult) {
        if (result.hasResolution()) {
            val pi = result.pendingIntent
            if (pi == null) {
                pendingAction = null
                Toast.makeText(this, "Google sign-in failed: no sign-in screen available", Toast.LENGTH_LONG).show()
                return
            }
            try {
                @Suppress("DEPRECATION")
                startIntentSenderForResult(pi.intentSender, AUTH_REQ, null, 0, 0, 0)
            } catch (e: IntentSender.SendIntentException) {
                pendingAction = null
                Toast.makeText(this, "Google sign-in failed: could not open the sign-in screen (${e.message})", Toast.LENGTH_LONG).show()
            }
            return
        }
        onAuthorized(result)
    }

    /** Failure wording is deliberately explicit — status code + message —
     *  so any remaining Cloud-side mismatch can be quoted back verbatim. */
    private fun showAuthError(e: ApiException) {
        val code = e.statusCode
        var msg = "Google sign-in failed (code $code): ${e.message ?: "unknown error"}"
        if (code == CommonStatusCodes.DEVELOPER_ERROR) {
            msg += " — Google doesn't recognize this app: the Cloud Android client's package name and signing-certificate SHA-1 must match this app exactly."
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /** A granted result: persist token + identity, then run the pending
     *  action. Also the post-sign-in Drive check from v2.8: remember the
     *  last backup time, and offer the one-time restore when a backup
     *  exists and this phone has no sessions yet. */
    private fun onAuthorized(result: AuthorizationResult) {
        val token = result.accessToken
        if (token.isNullOrBlank()) {
            pendingAction = null
            Toast.makeText(this, "Google sign-in failed: Google returned no access token", Toast.LENGTH_LONG).show()
            return
        }
        GoogleAuth.storeResult(this, result)
        val action = pendingAction
        pendingAction = null
        Bg.run({
            if (GoogleAuth.email(this@SettingsActivity).isBlank() && GoogleAuth.displayName(this@SettingsActivity).isBlank()) {
                GoogleAuth.fetchIdentityBlocking(this@SettingsActivity, token)
            }
            try { DriveBackup.findMeta(this@SettingsActivity) } catch (e: Exception) { null }
        }, { meta, _ ->
            if (meta != null) {
                GoogleAuth.setLastBackupMs(this, DriveBackup.parseModified(meta.modifiedTime))
                if (db.sessionCount() == 0 && !isFinishing && !isDestroyed) {
                    offerDriveRestore(meta)
                    render()
                    return@run
                }
            }
            when (action) {
                "backup" -> doBackup()
                "restore" -> confirmRestore()
                "restore-run" -> doDriveRestore()
                else -> {
                    Toast.makeText(this, "Signed in as ${GoogleAuth.email(this)}", Toast.LENGTH_SHORT).show()
                    render()
                }
            }
        })
    }

    private fun offerDriveRestore(meta: DriveBackup.Meta) {
        AlertDialog.Builder(this)
            .setTitle("Drive backup found")
            .setMessage("Restore your backup from ${displayTime(meta.modifiedTime)}? This replaces all data on this phone.")
            .setPositiveButton("Restore") { _, _ -> doDriveRestore() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun displayTime(rfc3339: String): String {
        val ms = DriveBackup.parseModified(rfc3339)
        if (ms <= 0L) return "your last backup"
        return java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US).format(java.util.Date(ms))
    }

    private fun doBackup() {
        if (!GoogleAuth.isSignedIn(this)) { startGoogleAuth("backup"); return }
        val dlg = progressDialog("Backing up…")
        dlg.show()
        // getAccessToken silently refreshes when it can; a null marker (no
        // exception) means Google needs interaction — run the auth flow and
        // let onAuthorized re-run this backup.
        Bg.run({ if (GoogleAuth.getAccessToken(this) != null) DriveBackup.backupNow(this, db) else null }, { res, ex ->
            dlg.dismiss()
            if (ex != null) Toast.makeText(this, "Backup failed: ${ex.message}", Toast.LENGTH_LONG).show()
            else if (res == null) startGoogleAuth("backup")
            else { Toast.makeText(this, "Backup complete", Toast.LENGTH_SHORT).show(); render() }
        })
    }

    private fun confirmRestore() {
        if (!GoogleAuth.isSignedIn(this)) { startGoogleAuth("restore"); return }
        AlertDialog.Builder(this).setTitle("Restore from Drive?")
            .setMessage("Replace all data on this phone with the Drive backup?")
            .setPositiveButton("Restore") { _, _ -> doDriveRestore() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun doDriveRestore() {
        if (!GoogleAuth.isSignedIn(this)) { startGoogleAuth("restore-run"); return }
        val dlg = progressDialog("Restoring…")
        dlg.show()
        Bg.run({ if (GoogleAuth.getAccessToken(this) != null) DriveBackup.restoreNow(this, db) else null }, { counts, ex ->
            dlg.dismiss()
            if (ex != null) Toast.makeText(this, "Restore failed: ${ex.message}", Toast.LENGTH_LONG).show()
            else if (counts == null) { startGoogleAuth("restore-run"); return@run }
            else Toast.makeText(this, "Restored: ${counts.optInt("routines")} routines, ${counts.optInt("sessions")} sessions", Toast.LENGTH_LONG).show()
            render()
        })
    }

    private fun doSignOut() {
        pendingAction = null
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
        if (requestCode==AUTH_REQ) {
            if (resultCode==Activity.RESULT_OK && data != null) {
                try {
                    onAuthorized(GoogleAuth.client(this).getAuthorizationResultFromIntent(data))
                } catch (e: ApiException) {
                    pendingAction = null
                    showAuthError(e)
                }
            } else {
                pendingAction = null
                Toast.makeText(this, "Google sign-in cancelled", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (requestCode==EXPORT_REQ && resultCode== Activity.RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) {
                try { val csv = CsvExporter.buildCsv(this, db); contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }; Toast.makeText(this,"CSV exported", Toast.LENGTH_SHORT).show() }
                catch (e: Exception) { Toast.makeText(this,"Export failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
}
