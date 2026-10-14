package com.mahesh.workouttracker

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController

/** v3.9 Settings (mock r4 frame 5): icon-first. Every row is a pastel
 *  squircle icon + name + one-line desc + its control (segment,
 *  toggle, steppers); Export / Restore are two icon tiles; no
 *  full-width text buttons remain. ALL behavior is the pre-v3.9
 *  behavior, re-skinned only: the unit pref, the rest-timer prefs/steps,
 *  the full Health Connect flow (opt-in toggle, tap-to-grant,
 *  denial drops the pref back off, PROVIDER_UPDATE status, manual
 *  backfill), the CSV export, and the v3.8.3 upsert Restore with its
 *  counts dialog. The v3.8.3 wrap-content property is kept: cards
 *  size to their content, nothing clips. This is also the ONLY page
 *  with Health Connect UI (the chooser's sync row is gone). */
class SettingsActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private val EXPORT_REQ = 2001

    // Health Connect state: hcGranted is refreshed async after every
    // render (the permission check is a suspend call); null = unchecked.
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

    /** One icon row card: squircle icon + name/desc column + control.
     *  Content-sized (wrap-content) like every v3.8.3 card. */
    private fun iconCard(icon: String, tint: Int, name: String, desc: String, control: android.view.View?, onCardClick: (() -> Unit)? = null): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Theme.surface); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Theme.stroke) }
            elevation = dp(2).toFloat()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, dp(4)) }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(settingsIcon(icon, tint))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(12), 0, dp(8), 0) }
        }
        val nm = makeText(name, 15f, true)
        (nm.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        nm.maxLines = 1
        col.addView(nm)
        val ds = makeText(desc, 12f, false, Theme.textSecondary)
        (ds.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(1), 0, 0)
        ds.maxLines = 2
        col.addView(ds)
        row.addView(col)
        if (control != null) row.addView(control)
        card.addView(row)
        if (onCardClick != null) { card.isClickable = true; card.setOnClickListener { onCardClick() } }
        return card
    }

    private fun render() {
        val root = tabScaffold("Settings")
        val compactSet = isCompactScreen()
        root.addView(screenTitle("Settings").apply {
            (layoutParams as LinearLayout.LayoutParams).apply { height = dp(if (compactSet) 32 else 40); setMargins(0, 0, 0, 0) }
            gravity = Gravity.CENTER_VERTICAL
        })

        // Theme (v3.9 addition): the M3 palette is the default; Mist
        // is the opt-in. v3.9.1: changing it re-themes IN PLACE —
        // swap Palettes.current and recreate THIS activity, staying
        // on Settings (the v3.9 CLEAR_TASK relaunch to Home was
        // jarring). Palette tokens are read at view-build time, so
        // the rebuilt page is fully re-paletted, and activities
        // opened afterwards read the new palette at their creation.
        val themeName = AppTheme.name(this)
        root.addView(iconCard("🎨", IconTints.PINK, "Theme", "App colors",
            segControl(listOf("Material", "Mist"), if (themeName == "mist") 1 else 0) { idx ->
                val chosen = if (idx == 1) "mist" else "material"
                if (chosen != AppTheme.name(this)) {
                    AppTheme.set(this, chosen)
                    Palettes.apply(this)
                    recreate()
                }
            }))

        // Weight unit: the existing pref, as a segment now.
        val unit = WeekManager.unit(this)
        root.addView(iconCard("⚖️", IconTints.LILAC, "Weight unit", "Labels + CSV",
            segControl(listOf("kg", "lb"), if (unit == "kg") 0 else 1) { idx ->
                WeekManager.setUnit(this, if (idx == 0) "kg" else "lb"); render()
            }))

        // v3.9.1: no Beginner Mode row — Beginner is always ON and
        // the option is gone (Beginner.beginnerMode() is hard-true;
        // posture checks render unconditionally).

        // Rest Timer: header row + the steppers row inside the same
        // card. Same prefs and steps as v3.8.3 (sets ±5 in 0..300,
        // exercises ±15 in 0..600).
        val restSet = WeekManager.restSetSec(this)
        val restEx = WeekManager.restExerciseSec(this)
        val restCard = iconCard("⏱️", IconTints.BLUE, "Rest Timer", "Auto-rest between sets / exercises", null)
        val stepRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(10), 0, 0) }
        }
        fun stepGroup(label: String, value: Int, step: Int, onChange: (Int) -> Unit) {
            val g = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val lab = makeText(label, 10.5f, true, Theme.textTertiary)
            lab.letterSpacing = 0.06f
            (lab.layoutParams as LinearLayout.LayoutParams).apply { width = LinearLayout.LayoutParams.WRAP_CONTENT; setMargins(0, 0, dp(6), 0) }
            g.addView(lab)
            fun roundBtn(t: String, delta: Int): TextView = TextView(this).apply {
                text = t; textSize = 15f; gravity = Gravity.CENTER; setTextColor(Theme.primary)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                background = roundedBg(Palettes.current.stepperBg, 999)
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                isClickable = true
                setOnClickListener { onChange(value + delta) }
            }
            g.addView(roundBtn("−", -step))
            val vTv = makeText("${value}s", 14f, true)
            vTv.gravity = Gravity.CENTER
            (vTv.layoutParams as LinearLayout.LayoutParams).apply { width = dp(40); setMargins(0, 0, 0, 0) }
            g.addView(vTv)
            g.addView(roundBtn("+", step))
            stepRow.addView(g)
        }
        stepGroup("SETS", restSet, 5) { v -> WeekManager.setRestSecs(this, v.coerceIn(0, 300), restEx); render() }
        stepGroup("EXERCISES", restEx, 15) { v -> WeekManager.setRestSecs(this, restSet, v.coerceIn(0, 600)); render() }
        restCard.addView(stepRow)
        root.addView(restCard)

        // Health Connect: desc = the status line (same semantics as
        // the old card, verbatim); the toggle and the row tap share
        // one handler — permission missing -> request it (the mock:
        // "granting permission happens by tapping the row when it's
        // off"); granted -> flip the opt-in. "↻ Sync past" is the
        // compact backfill action under the row.
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
        fun hcTap() {
            when {
                hcStatus == HealthConnectClient.SDK_UNAVAILABLE ->
                    Toast.makeText(this, "Health Connect isn't available on this device", Toast.LENGTH_SHORT).show()
                hcGranted != true -> hcPermLauncher.launch(setOf(HealthSync.WRITE_PERMISSION))
                else -> { HealthSync.setEnabled(this, !hcOn); render() }
            }
        }
        val hcCard = iconCard("💚", IconTints.GREEN, "Health Connect", hcStatusText,
            toggleSwitch(hcOn && hcGranted == true) { hcTap() },
            onCardClick = { hcTap() })
        val syncRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), 0, 0) }
        }
        val syncBtn = TextView(this).apply {
            text = if (hcSyncRunning) "Syncing…" else "↻ Sync past"
            textSize = 12.5f; setTextColor(Theme.primary)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            background = roundedBg(Theme.surfaceVariant, 999)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isEnabled = hcGranted == true && !hcSyncRunning
            alpha = if (isEnabled) 1f else 0.45f
            isClickable = true
            setOnClickListener {
                if (hcSyncRunning) return@setOnClickListener
                hcSyncRunning = true; render()
                HealthSync.syncAllCompleted(this@SettingsActivity, db) { written, total ->
                    runOnUiThread {
                        hcSyncRunning = false
                        Toast.makeText(this@SettingsActivity, when {
                            total == 0 -> "No completed workouts to sync yet"
                            written == 0 -> "Couldn't sync — check Health Connect permission"
                            else -> "Synced $written workouts to Health Connect"
                        }, Toast.LENGTH_LONG).show()
                        render()
                    }
                }
            }
        }
        syncRow.addView(syncBtn)
        hcCard.addView(syncRow)
        root.addView(hcCard)

        // Data tiles: Export CSV + Restore, side by side.
        val tiles = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, 0) }
        }
        fun tile(emoji: String, tint: Int, label: String, marginLeft: Int, marginRight: Int, onClick: () -> Unit): LinearLayout {
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                background = GradientDrawable().apply { setColor(Theme.surface); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Theme.stroke) }
                elevation = dp(2).toFloat()
                setPadding(dp(8), dp(9), dp(8), dp(9))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(marginLeft), 0, dp(marginRight), 0) }
                isClickable = true
                setOnClickListener { onClick() }
            }
            // v3.9.1: smaller tiles (40dp bubble, tighter padding) and
            // tray glyphs — 📤 out-tray = export/send out, 📥 in-tray
            // = restore/bring in.
            t.addView(doorIcon(emoji, tint, 40, 20f))
            val lb = makeText(label, 13f, true)
            lb.gravity = Gravity.CENTER
            (lb.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(5), 0, 0)
            t.addView(lb)
            return t
        }
        tiles.addView(tile("📤", IconTints.BLUE, "Export CSV", 0, 4) { launchExport() })
        tiles.addView(tile("📥", IconTints.WARM, "Restore", 4, 0) { confirmRestore() })
        root.addView(tiles)

        // About: version + the week rule, allowed to wrap fully.
        val about = caption("Workout Tracker · Version 3.9.2\nWeek counter advances only after a week with 1+ completed workout.")
        (about.layoutParams as LinearLayout.LayoutParams).setMargins(dp(2), dp(10), dp(2), 0)
        root.addView(about)
    }

    /** The v3.8.3 Restore, unchanged: confirm -> upsert by sourceId
     *  (JsonSeed.restorePreloaded) -> dialog with the real counts;
     *  failures surface in a "Restore failed" dialog. */
    private fun confirmRestore() {
        AlertDialog.Builder(this).setTitle("Restore preloaded exercises?").setMessage("This resets the exercise library to the preloaded body-part data — edits to preloaded exercises are undone, exercises you added yourself stay, and history is NOT deleted.")
            .setPositiveButton("Restore") { _, _ ->
                try {
                    val (inserted, refreshed) = JsonSeed.restorePreloaded(this, db)
                    AlertDialog.Builder(this).setTitle("Restore complete")
                        .setMessage("Added $inserted new preloaded exercise${if (inserted == 1) "" else "s"} and refreshed $refreshed existing one${if (refreshed == 1) "" else "s"} to the preloaded data. Exercises you added yourself and your history were not touched.")
                        .setPositiveButton("OK", null).show()
                } catch (e: Exception) {
                    AlertDialog.Builder(this).setTitle("Restore failed")
                        .setMessage(e.message ?: "Something went wrong while restoring.")
                        .setPositiveButton("OK", null).show()
                }
                render()
            }
            .setNegativeButton("Cancel", null).show()
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
