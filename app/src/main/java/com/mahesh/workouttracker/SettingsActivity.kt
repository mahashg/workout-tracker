package com.mahesh.workouttracker

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController

class SettingsActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private val EXPORT_REQ = 2001

    // v3.4 Health Connect state: hcGranted is refreshed async after every
    // render (the permission check is a suspend call); null = not checked.
    private var hcGranted: Boolean? = null
    private var hcSyncRunning = false
    private val hcPermLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.contains(HealthSync.WRITE_PERMISSION)) {
            hcGranted = true
            HealthSync.setEnabled(this, true)
            Toast.makeText(this, "Health Connect sync on", Toast.LENGTH_SHORT).show()
        } else {
            hcGranted = false
            HealthSync.setEnabled(this, false)
            Toast.makeText(this, "Permission not granted — Health Connect sync stayed off", Toast.LENGTH_LONG).show()
        }
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); db = DbHelper(this); render(); refreshHcPermission() }
    override fun onResume() { super.onResume(); if (::db.isInitialized) { render(); refreshHcPermission() } }

    private fun refreshHcPermission() {
        HealthSync.checkWritePermission(this) { g ->
            runOnUiThread { if (!isFinishing && hcGranted != g) { hcGranted = g; render() } }
        }
    }

    private fun render() {
        val root = tabScaffold("Settings")
        // Explicit card budgets (v2.5.1 discipline). v3.4: the Health
        // Connect card joins the page, so Units + Beginner Mode now share
        // one Preferences card (same text, same toggles) and switch
        // buttons are the app-standard 48dp (they were 56 only because
        // the v3.1 cards were under-budgeted and clipped them). Compact
        // totals: title 32 + Preferences 218 + Rest 68 + Health Connect
        // 96 + Data 114 = 528dp + 8dp card margins = ~536dp, under the
        // ~546dp available at a 640dp viewport; bottom nav visible.
        val compactSet = isCompactScreen()
        fun budgetCard(h: Int): LinearLayout {
            val c = cardLayout()
            c.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h)).apply { setMargins(0, dp(1), 0, dp(1)) }
            c.setPadding(dp(12), dp(6), dp(12), dp(6))
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

        // v3.4: Units + Beginner Mode share one Preferences card so the
        // Health Connect card fits the one-page budget. Same text and
        // toggles as the two v3.1 cards; buttons are exactly 48dp with
        // the card budgeted above its content — nothing clips (the v3.1
        // clipping was under-budgeted cards, not button height).
        val prefCard = budgetCard(if (compactSet) 218 else 226)
        prefCard.addView(noMargin(overline("Preferences")))
        val unit = WeekManager.unit(this)
        prefCard.addView(noMargin(cardTitle("Weight unit: $unit")))
        prefCard.addView(noMargin(caption("Applies to labels and CSV.")))
        val unitBtn = makeSecondaryButton("Switch to ${if (unit=="lb") "kg" else "lb"}") {
            WeekManager.setUnit(this, if (unit=="lb") "kg" else "lb"); render()
        }
        (unitBtn.layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(6), 0, 0) }
        fixBtn(unitBtn, 48)
        prefCard.addView(unitBtn)
        val beginnerOn = Beginner.beginnerMode(this)
        val begTitle = noMargin(cardTitle("Beginner Mode: ${if(beginnerOn) "ON" else "OFF"}"))
        (begTitle.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(10), 0, 0)
        prefCard.addView(begTitle)
        prefCard.addView(noMargin(caption("Posture checks and form cues on every exercise."), 1))
        val begBtn = makeSecondaryButton(if(beginnerOn) "Turn Beginner Mode OFF" else "Turn Beginner Mode ON") { Beginner.setBeginnerMode(this, !beginnerOn); render() }
        (begBtn.layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(6), 0, 0) }
        fixBtn(begBtn, 48)
        prefCard.addView(begBtn)
        root.addView(prefCard)

        // Rest Timer card (v3): the Rest Ring auto-starts when a set is
        // logged — 30s between sets / 60s between exercises by default,
        // both adjustable here. v3.2 polish: steppers 30x32 -> 36x36 and
        // the row 38 -> 40dp, card rebudgeted 72 -> 78 (compact).
        val restCard = budgetCard(if (compactSet) 68 else 72)
        restCard.addView(noMargin(overline("Rest Timer")))
        val restRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)) }
        fun restGroup(label: String, value: Int, step: Int, onChange: (Int) -> Unit) {
            val g = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            g.addView(noMargin(makeText(label, 11.5f, false, Theme.textSecondary)))
            val minus = makeSmallButton("−") { onChange(value - step) }
            minus.minWidth = 0; minus.minHeight = 0; minus.setPadding(0, 0, 0, 0)
            (minus.layoutParams as LinearLayout.LayoutParams).apply { width = dp(36); height = dp(36); setMargins(dp(4), 0, dp(2), 0) }
            g.addView(minus)
            val vTv = makeText("${value}s", 14.5f, true)
            vTv.gravity = android.view.Gravity.CENTER
            (vTv.layoutParams as LinearLayout.LayoutParams).apply { width = dp(38); setMargins(0, 0, 0, 0) }
            g.addView(vTv)
            val plus = makeSmallButton("+") { onChange(value + step) }
            plus.minWidth = 0; plus.minHeight = 0; plus.setPadding(0, 0, 0, 0)
            (plus.layoutParams as LinearLayout.LayoutParams).apply { width = dp(36); height = dp(36); setMargins(dp(2), 0, 0, 0) }
            g.addView(plus)
            restRow.addView(g)
        }
        val restSet = WeekManager.restSetSec(this)
        val restEx = WeekManager.restExerciseSec(this)
        restGroup("Between sets", restSet, 5) { v -> WeekManager.setRestSecs(this, v.coerceIn(0, 300), restEx); render() }
        restGroup("Between exercises", restEx, 15) { v -> WeekManager.setRestSecs(this, restSet, v.coerceIn(0, 600)); render() }
        restCard.addView(restRow)
        root.addView(restCard)

        // v3.4: Health Connect card. Opt-in toggle (default OFF) + status
        // line that reflects reality + manual backfill of past workouts.
        // Turning the toggle ON with permission missing launches the
        // Health Connect permission request; denial drops the pref back
        // to OFF with a toast (hcPermLauncher). No account/OAuth involved.
        val hcCard = budgetCard(if (compactSet) 96 else 100)
        hcCard.addView(noMargin(overline("Health Connect")))
        val hcStatus = HealthSync.sdkStatus(this)
        val hcOn = HealthSync.isEnabled(this)
        val hcStatusText = when {
            hcStatus == HealthConnectClient.SDK_UNAVAILABLE -> "Not available on this device"
            hcStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Health Connect needs an update on this device"
            hcGranted == null -> "Checking permission…"
            hcGranted == false -> "Tap to grant permission"
            hcOn -> "On — workouts sync after you finish them"
            else -> "Off"
        }
        hcCard.addView(noMargin(caption(hcStatusText), 1))
        val hcRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)) }
        val hcToggleBtn = makeSecondaryButton(when {
            hcStatus == HealthConnectClient.SDK_UNAVAILABLE -> "Unavailable"
            hcGranted == false -> "Grant permission"
            hcOn -> "Turn sync OFF"
            else -> "Turn sync ON"
        }) {
            when {
                hcStatus == HealthConnectClient.SDK_UNAVAILABLE ->
                    Toast.makeText(this, "Health Connect isn't available on this device", Toast.LENGTH_SHORT).show()
                hcGranted != true -> hcPermLauncher.launch(setOf(HealthSync.WRITE_PERMISSION))
                else -> { HealthSync.setEnabled(this, !hcOn); render() }
            }
        }
        hcToggleBtn.isEnabled = hcStatus != HealthConnectClient.SDK_UNAVAILABLE
        (hcToggleBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; setMargins(0, dp(4), dp(4), 0) }
        fixBtn(hcToggleBtn, 48)
        val hcSyncBtn = makeSecondaryButton(if (hcSyncRunning) "Syncing…" else "Sync past workouts") {
            if (hcSyncRunning) return@makeSecondaryButton
            hcSyncRunning = true; render()
            HealthSync.syncAllCompleted(this, db) { written, total ->
                runOnUiThread {
                    hcSyncRunning = false
                    Toast.makeText(this, when {
                        total == 0 -> "No completed workouts to sync yet"
                        written == 0 -> "Couldn't sync — check Health Connect permission"
                        else -> "Synced $written workouts to Health Connect"
                    }, Toast.LENGTH_LONG).show()
                    render()
                }
            }
        }
        hcSyncBtn.isEnabled = hcGranted == true && !hcSyncRunning
        (hcSyncBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; setMargins(dp(4), dp(4), 0, 0) }
        fixBtn(hcSyncBtn, 48)
        hcRow.addView(hcToggleBtn); hcRow.addView(hcSyncBtn)
        hcCard.addView(hcRow)
        root.addView(hcCard)

        // v3.1: the Google Backup section was removed from Settings at the
        // user's request. GoogleAuth / DriveBackup remain in the codebase
        // but are unreachable from any UI; the post-workout auto-backup
        // call sites no-op silently when never signed in.

        // Data card; About (version + week rule) is folded in at the bottom
        // (keeps this screen one page tall).
        val dataCard = budgetCard(if (compactSet) 114 else 118)
        dataCard.addView(noMargin(overline("Your Data")))
        val dataRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)) }
        val exportBtn = makeButton("Export CSV") { launchExport() }
        (exportBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; setMargins(0, dp(4), dp(4), 0) }
        fixBtn(exportBtn, 48)
        val restoreBtn = makeSecondaryButton("Restore Preloaded") {
            AlertDialog.Builder(this).setTitle("Restore preloaded exercises?").setMessage("This resets the exercise library to the preloaded body-part data — edits to preloaded exercises are undone, exercises you added yourself stay, and history is NOT deleted.")
                .setPositiveButton("Restore") { _, _ -> Seed.restore(db); Seed.markV2Applied(this); JsonSeed.restoreDefaults(this, db); Toast.makeText(this,"Preloaded exercises restored", Toast.LENGTH_SHORT).show(); render() }
                .setNegativeButton("Cancel", null).show()
        }
        (restoreBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; setMargins(dp(4), dp(4), 0, 0) }
        fixBtn(restoreBtn, 48)
        dataRow.addView(exportBtn); dataRow.addView(restoreBtn)
        dataCard.addView(dataRow)
        // v2.6.1 copy audit: About is two lines (version + week rule).
        dataCard.addView(noMargin(caption("Workout Tracker • Version 3.5\nWeek counter advances only after a week with 1+ completed workout."), 2))
        root.addView(dataCard)
    }

    private fun launchExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="text/csv"; putExtra(Intent.EXTRA_TITLE, "workout-export.csv") }
        startActivityForResult(intent, EXPORT_REQ)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Only the CSV document picker uses this legacy callback.
        if (requestCode==EXPORT_REQ && resultCode== Activity.RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) {
                try { val csv = CsvExporter.buildCsv(this, db); contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }; Toast.makeText(this,"CSV exported", Toast.LENGTH_SHORT).show() }
                catch (e: Exception) { Toast.makeText(this,"Export failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
}
