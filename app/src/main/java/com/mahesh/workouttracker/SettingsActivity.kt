package com.mahesh.workouttracker

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes

class SettingsActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private val EXPORT_REQ = 2001

    // Google action waiting on an interactive authorize: "signin", "backup",
    // "restore" (re-confirm), or "restore-run" (already confirmed). It is
    // persisted rather than kept only in a field so an Activity recreation
    // while Google's screen is open cannot lose the action that should run
    // after a successful result.
    private fun savePendingAction(action: String?) {
        val e = getSharedPreferences(FLOW_PREFS, Context.MODE_PRIVATE).edit()
        if (action == null) e.remove(KEY_PENDING_ACTION) else e.putString(KEY_PENDING_ACTION, action)
        e.apply()
    }
    private fun savedPendingAction(): String? =
        getSharedPreferences(FLOW_PREFS, Context.MODE_PRIVATE).getString(KEY_PENDING_ACTION, null)
    private fun clearPendingAction() = savePendingAction(null)

    /** Current Google authorization-result plumbing (v2.9.1). The old
     *  v2.9 code launched the SDK PendingIntent with the deprecated
     *  startIntentSenderForResult() and, crucially, only parsed the returned
     *  Intent when resultCode == RESULT_OK. Google can return a non-OK
     *  result code WITH an Intent containing either a usable result or the
     *  real ApiException. Discarding that Intent made unrelated Google
     *  failures look like a user cancellation. This launcher therefore
     *  hands every callback to handleAuthActivityResult(), which parses a
     *  non-null Intent first and uses resultCode only as diagnostics. */
    private val authResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        handleAuthActivityResult(activityResult.resultCode, activityResult.data)
    }

    companion object {
        private const val FLOW_PREFS = "settings_google_flow"
        private const val KEY_PENDING_ACTION = "pending_action"
        // GoogleSignInStatusCodes.SIGN_IN_CANCELLED. The constant is used
        // directly because play-services-auth 22.0.0 no longer ships that
        // legacy status-code class.
        private const val GOOGLE_SIGN_IN_CANCELLED = 12501
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); db = DbHelper(this); render() }
    // Re-render on resume so the Google section reflects the latest
    // sign-in/backup state whenever Settings comes back to the front.
    override fun onResume() { super.onResume(); if (::db.isInitialized) render() }

    private fun render() {
        val root = tabScaffold("Settings")
        // Explicit card budgets (v2.5.1; v3 adds the Rest Timer card and
        // folds About into the Data card — compact sums: signed-out
        // 32+98+98+64+114+100 = 506dp, signed-in 32+98+98+64+136+100 = 528dp,
        // plus ~20dp of card margins, under the ~546dp available at a 640dp
        // viewport; bottom nav always visible).
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
            (layoutParams as LinearLayout.LayoutParams).apply { height = dp(if (compactSet) 32 else 40); setMargins(0, 0, 0, 0) }
            gravity = android.view.Gravity.CENTER_VERTICAL
        })

        val unit = WeekManager.unit(this)
        val unitCard = budgetCard(if (compactSet) 98 else 108)
        unitCard.addView(noMargin(overline("Units")))
        unitCard.addView(noMargin(cardTitle("Weight unit: $unit")))
        unitCard.addView(noMargin(caption("Applies to labels and CSV.")))
        val unitBtn = makeSecondaryButton("Switch to ${if (unit=="lb") "kg" else "lb"}") {
            WeekManager.setUnit(this, if (unit=="lb") "kg" else "lb"); render()
        }
        (unitBtn.layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(4), 0, 0) }
        fixBtn(unitBtn, 40)
        unitCard.addView(unitBtn)
        root.addView(unitCard)

        val beginnerOn = Beginner.beginnerMode(this)
        val begCard = budgetCard(if (compactSet) 98 else 108)
        begCard.addView(noMargin(overline("Beginner Mode")))
        begCard.addView(noMargin(cardTitle("Beginner Mode: ${if(beginnerOn) "ON" else "OFF"}")))
        begCard.addView(noMargin(caption("Posture checks and form cues on every exercise."), 1))
        val begBtn = makeSecondaryButton(if(beginnerOn) "Turn Beginner Mode OFF" else "Turn Beginner Mode ON") { Beginner.setBeginnerMode(this, !beginnerOn); render() }
        (begBtn.layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(4), 0, 0) }
        fixBtn(begBtn, 40)
        begCard.addView(begBtn)
        root.addView(begCard)

        // Rest Timer card (v3): the Rest Ring auto-starts when a set is
        // logged — 30s between sets / 60s between exercises by default,
        // both adjustable here and carried in the Drive backup prefs.
        val restCard = budgetCard(if (compactSet) 64 else 72)
        restCard.addView(noMargin(overline("Rest Timer")))
        val restRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)) }
        fun restGroup(label: String, value: Int, step: Int, onChange: (Int) -> Unit) {
            val g = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            g.addView(noMargin(makeText(label, 11.5f, false, Theme.textSecondary)))
            val minus = makeSmallButton("−") { onChange(value - step) }
            minus.minWidth = 0; minus.minHeight = 0; minus.setPadding(0, 0, 0, 0)
            (minus.layoutParams as LinearLayout.LayoutParams).apply { width = dp(30); height = dp(32); setMargins(dp(4), 0, dp(2), 0) }
            g.addView(minus)
            val vTv = makeText("${value}s", 14.5f, true)
            vTv.gravity = android.view.Gravity.CENTER
            (vTv.layoutParams as LinearLayout.LayoutParams).apply { width = dp(38); setMargins(0, 0, 0, 0) }
            g.addView(vTv)
            val plus = makeSmallButton("+") { onChange(value + step) }
            plus.minWidth = 0; plus.minHeight = 0; plus.setPadding(0, 0, 0, 0)
            (plus.layoutParams as LinearLayout.LayoutParams).apply { width = dp(30); height = dp(32); setMargins(dp(2), 0, 0, 0) }
            g.addView(plus)
            restRow.addView(g)
        }
        val restSet = WeekManager.restSetSec(this)
        val restEx = WeekManager.restExerciseSec(this)
        restGroup("Between sets", restSet, 5) { v -> WeekManager.setRestSecs(this, v.coerceIn(0, 300), restEx); render() }
        restGroup("Between exercises", restEx, 15) { v -> WeekManager.setRestSecs(this, restSet, v.coerceIn(0, 600)); render() }
        restCard.addView(restRow)
        root.addView(restCard)

        val signedIn = GoogleAuth.isSignedIn(this)
        root.addView(googleCard(
            if (signedIn) (if (compactSet) 136 else 150) else (if (compactSet) 114 else 124),
            ::fixBtn, ::noMargin))

        // Data card; About (version + week rule) is folded in at the bottom
        // (keeps this screen one page tall).
        val dataCard = budgetCard(if (compactSet) 100 else 110)
        dataCard.addView(noMargin(overline("Your Data")))
        val dataRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)) }
        val exportBtn = makeButton("Export CSV") { launchExport() }
        (exportBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; setMargins(0, dp(4), dp(4), 0) }
        fixBtn(exportBtn, 44)
        val restoreBtn = makeSecondaryButton("Restore Preloaded") {
            AlertDialog.Builder(this).setTitle("Restore preloaded exercises?").setMessage("This resets the exercise library to the preloaded body-part data — edits to preloaded exercises are undone, exercises you added yourself stay, and history is NOT deleted.")
                .setPositiveButton("Restore") { _, _ -> Seed.restore(db); Seed.markV2Applied(this); JsonSeed.restoreDefaults(this, db); Toast.makeText(this,"Preloaded exercises restored", Toast.LENGTH_SHORT).show(); render() }
                .setNegativeButton("Cancel", null).show()
        }
        (restoreBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; setMargins(dp(4), dp(4), 0, 0) }
        fixBtn(restoreBtn, 44)
        dataRow.addView(exportBtn); dataRow.addView(restoreBtn)
        dataCard.addView(dataRow)
        // v2.6.1 copy audit: About is two lines (version + week rule).
        dataCard.addView(noMargin(caption("Workout Tracker • Version 3.0\nWeek counter advances only after a week with 1+ completed workout."), 2))
        root.addView(dataCard)
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
            fixBtn(signOutBtn, 30)
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

    // ----- Google Identity Services authorization (v2.9, fixed in v2.9.1) -----

    /** Runs the SDK authorize() for [action] using this Activity as the
     *  interactive context. Google may answer inline or ask for a
     *  resolution UI (account pick / consent), launched through
     *  authResultLauncher and parsed in handleAuthActivityResult(). */
    private fun startGoogleAuth(action: String) {
        savePendingAction(action)
        GoogleAuth.interactiveClient(this).authorize(GoogleAuth.authorizationRequest(this))
            .addOnSuccessListener { result -> handleAuthResult(result) }
            .addOnFailureListener { e ->
                clearPendingAction()
                if (e is ApiException) showAuthError(e) else Toast.makeText(this, "Google sign-in failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun handleAuthResult(result: AuthorizationResult) {
        if (result.hasResolution()) {
            val pi = result.pendingIntent
            if (pi == null) {
                clearPendingAction()
                Toast.makeText(this, "Google sign-in failed: no sign-in screen available", Toast.LENGTH_LONG).show()
                return
            }
            try {
                authResultLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } catch (e: Exception) {
                clearPendingAction()
                Toast.makeText(this, "Google sign-in failed: could not open the sign-in screen (${e.message})", Toast.LENGTH_LONG).show()
            }
            return
        }
        onAuthorized(result)
    }

    /** Parse-first result handling: a non-null returned Intent is ALWAYS
     *  given to getAuthorizationResultFromIntent(), even when Google's
     *  resolution activity reports RESULT_CANCELED. Only a null Intent is
     *  treated as a bare cancellation. No access token or Intent extra
     *  value is displayed or logged. */
    private fun handleAuthActivityResult(resultCode: Int, data: Intent?) {
        if (data != null) {
            try {
                onAuthorized(GoogleAuth.interactiveClient(this).getAuthorizationResultFromIntent(data))
            } catch (e: ApiException) {
                clearPendingAction()
                if (isGoogleCancellation(e)) showAuthCancelled(e, resultCode) else showAuthError(e, resultCode)
            } catch (e: Exception) {
                clearPendingAction()
                Toast.makeText(this, "Google sign-in failed: ${e.message ?: "unknown error"} (activity result $resultCode)", Toast.LENGTH_LONG).show()
            }
            return
        }
        clearPendingAction()
        Toast.makeText(this, "Google sign-in cancelled (Google returned no result; activity result $resultCode)", Toast.LENGTH_LONG).show()
    }

    private fun isGoogleCancellation(e: ApiException): Boolean =
        e.statusCode == CommonStatusCodes.CANCELED ||
            e.statusCode == GOOGLE_SIGN_IN_CANCELLED

    /** Google itself returned a cancellation status after its screen
     *  closed. Keep the exact status visible: Mahesh's v2.9 report was an
     *  account selection followed by "cancelled", and Play services can
     *  use a cancellation status for a re-authentication failure as well
     *  as for a deliberate Back/Cancel. The code distinguishes those next
     *  steps; a bare "cancelled" toast does not. */
    private fun showAuthCancelled(e: ApiException, resultCode: Int) {
        val msg = "Google returned cancellation code ${e.statusCode}: ${e.message ?: "sign-in cancelled"}\n" +
            "Activity result: $resultCode\n\n" +
            "If you selected an account and did not press Back or Cancel, please send me this exact code."
        if (!isFinishing && !isDestroyed) {
            AlertDialog.Builder(this)
                .setTitle("Google sign-in didn't finish")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        } else {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** Failure wording is deliberately explicit — status code + message —
     *  so any remaining Cloud-side mismatch can be quoted back verbatim. */
    private fun showAuthError(e: ApiException, resultCode: Int? = null) {
        val code = e.statusCode
        var msg = "Google sign-in failed (code $code): ${e.message ?: "unknown error"}"
        if (resultCode != null) msg += "\nActivity result: $resultCode"
        if (code == CommonStatusCodes.DEVELOPER_ERROR) {
            msg += "\n\nGoogle doesn't recognize this app: the Cloud Android client's package name and signing-certificate SHA-1 must match this app exactly."
        }
        if (!isFinishing && !isDestroyed) {
            AlertDialog.Builder(this)
                .setTitle("Google sign-in failed")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        } else {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** A granted result: persist token + identity, then run the pending
     *  action. Also the post-sign-in Drive check from v2.8: remember the
     *  last backup time, and offer the one-time restore when a backup
     *  exists and this phone has no sessions yet. */
    private fun onAuthorized(result: AuthorizationResult) {
        val token = result.accessToken
        if (token.isNullOrBlank()) {
            clearPendingAction()
            Toast.makeText(this, "Google sign-in failed: Google returned no access token", Toast.LENGTH_LONG).show()
            return
        }
        GoogleAuth.storeResult(this, result)
        val action = savedPendingAction()
        clearPendingAction()
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
        clearPendingAction()
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
        // Google authorization results arrive through authResultLauncher,
        // not this legacy callback. Only the CSV document picker remains.
        if (requestCode==EXPORT_REQ && resultCode== Activity.RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) {
                try { val csv = CsvExporter.buildCsv(this, db); contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }; Toast.makeText(this,"CSV exported", Toast.LENGTH_SHORT).show() }
                catch (e: Exception) { Toast.makeText(this,"Export failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
}
