package com.mahesh.workouttracker

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Landing spot for the OAuth redirect (scheme com.mahesh.workouttracker,
 * host oauth2redirect; registered in the manifest, singleTask). Exchanges
 * the auth code for tokens on a background thread, then lands on Settings.
 * If a Drive backup already exists and this phone has no sessions yet, it
 * offers the restore once, right here.
 */
class OauthCallbackActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = DbHelper(this)
        val root = fitRoot()
        root.addView(makeText("Signing in with Google…", 16f, true).apply { gravity = Gravity.CENTER })
        setContentView(root)
        handle(intent?.data)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent.data)
    }

    private fun handle(data: Uri?) {
        if (handled) return
        handled = true
        val code = data?.getQueryParameter("code")
        val error = data?.getQueryParameter("error")
        val state = data?.getQueryParameter("state")
        if (code.isNullOrBlank()) {
            Toast.makeText(this, "Sign-in failed${if (error != null) ": $error" else ""}", Toast.LENGTH_LONG).show()
            goSettings()
            return
        }
        if (state == null || state != GoogleAuth.expectedState(this)) {
            GoogleAuth.clearPending(this)
            Toast.makeText(this, "Sign-in failed: state mismatch", Toast.LENGTH_LONG).show()
            goSettings()
            return
        }
        Bg.run({ GoogleAuth.exchangeCode(this, code); DriveBackup.findMeta(this) }, { meta, ex ->
            if (ex != null) {
                Toast.makeText(this, "Sign-in failed: ${ex.message}", Toast.LENGTH_LONG).show()
                goSettings()
                return@run
            }
            Toast.makeText(this, "Signed in as ${GoogleAuth.email(this)}", Toast.LENGTH_SHORT).show()
            if (meta != null) {
                GoogleAuth.setLastBackupMs(this, DriveBackup.parseModified(meta.modifiedTime))
                if (db.sessionCount() == 0 && !isFinishing && !isDestroyed) {
                    AlertDialog.Builder(this)
                        .setTitle("Drive backup found")
                        .setMessage("Restore your backup from ${displayTime(meta.modifiedTime)}? This replaces all data on this phone.")
                        .setPositiveButton("Restore") { _, _ -> doRestore() }
                        .setNegativeButton("Not now") { _, _ -> goSettings() }
                        .setOnCancelListener { goSettings() }
                        .show()
                    return@run
                }
            }
            goSettings()
        })
    }

    private fun doRestore() {
        val dlg = progressDialog("Restoring…")
        dlg.show()
        Bg.run({ DriveBackup.restoreNow(this, db) }, { counts, ex ->
            dlg.dismiss()
            if (ex != null) {
                Toast.makeText(this, "Restore failed: ${ex.message}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Restored: ${counts?.optInt("routines") ?: 0} routines, ${counts?.optInt("sessions") ?: 0} sessions", Toast.LENGTH_LONG).show()
            }
            goSettings()
        })
    }

    private fun displayTime(rfc3339: String): String {
        val ms = DriveBackup.parseModified(rfc3339)
        if (ms <= 0L) return "your last backup"
        return java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US).format(java.util.Date(ms))
    }

    private fun goSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP) })
        finish()
    }
}
