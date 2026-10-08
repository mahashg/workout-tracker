package com.mahesh.workouttracker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class WorkoutSectionsActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var sessionId: Long = -1
    private var timerChip: TimerChipView? = null
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); db = DbHelper(this); sessionId = intent.getLongExtra("sessionId", -1); if (sessionId < 0) { finish(); return } }
    override fun onResume() { super.onResume(); if (::db.isInitialized && sessionId > 0) render() }
    override fun onPause() { super.onPause(); timerChip?.stopTicking(); timerChip?.foldNow() }

    private fun render() {
        val session = db.getSession(sessionId) ?: run { finish(); return }
        val exs = db.getSessionExercises(sessionId)
        val root = fitRoot(); setContentView(root)
        root.addView(topBar(session.routineName, "${DateUtil.display(session.date)} • Week ${session.weekNumber}"))
        val muscles = linkedSetOf<String>(); for (ex in exs) muscles.addAll(DbHelper.parseMuscles(ex.targetMuscles, ex.name))

        // Compact header: body map + name + timer chip (+ beginner note inline)
        val header = cardLayout()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(BodyMapView(this, muscles, true).apply { layoutParams = LinearLayout.LayoutParams(dp(52), dp(64)) })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(10), 0, 0, 0) }
        info.addView(makeText(session.routineName, 18f, true))
        info.addView(caption("${session.focus} • ${musclesLabel(muscles)}"))
        info.addView(statusPill(if (session.completed) "Done" else if (exs.any { db.effectiveStatus(it) != "pending" }) "In Progress" else "Pending"))
        row.addView(info); header.addView(row)
        if (!session.completed) {
            db.ensureSessionStarted(sessionId)
            val chip = TimerChipView(this)
            chip.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, 0) }
            chip.bind(this, db, db.getSession(sessionId) ?: session) {}
            chip.startTicking()
            timerChip = chip
            header.addView(chip)
        } else if (session.startedAt > 0) {
            header.addView(caption("Time: ${WorkoutTimer.formatDuration(session.elapsedSec)}"))
        }
        if (Beginner.beginnerMode(this) && !session.completed) {
            header.addView(makeText("Beginner Mode ON — cards show posture checks & cues. Stop if you feel sharp pain.", 11f, false, Color.parseColor("#92400E")))
        }
        root.addView(header)

        // Three section cards share the remaining height.
        val allHandled = exs.isNotEmpty() && exs.all { db.effectiveStatus(it) != "pending" }
        root.addView(makeText("Today's sections", 14f, true, Theme.textSecondary))
        val sectionBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        for ((label, type) in listOf("Warm Up" to "Warmup", "Exercise" to "Main", "Cool Down" to "Stretch")) {
            val list = exs.filter { it.type.equals(type, true) }; if (list.isEmpty()) continue
            val done = list.count { db.effectiveStatus(it) == "done" }; val skipped = list.count { db.effectiveStatus(it) == "skipped" }; val handled = done + skipped
            val colorHex = when (type) { "Warmup" -> "#D97706"; "Stretch" -> "#0D9488"; else -> "#2563EB" }
            val iconRes = when (type) { "Warmup" -> R.drawable.ic_arrow_up; "Stretch" -> R.drawable.ic_check; else -> R.drawable.ic_play }
            val openCards = { startActivity(Intent(this, CardSessionActivity::class.java).apply { putExtra("sessionId", sessionId); putExtra("sectionType", type) }) }
            val card = cardLayout(colorHex, tappable = true) { openCards() }
            (card.layoutParams as LinearLayout.LayoutParams).apply { height = 0; weight = 1f; setMargins(0, dp(4), 0, dp(4)) }
            card.setPadding(dp(12), dp(6), dp(12), dp(6))
            val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT) }
            row2.addView(View(this).apply { setBackgroundColor(Color.parseColor(colorHex)); layoutParams = LinearLayout.LayoutParams(dp(6), LinearLayout.LayoutParams.MATCH_PARENT).apply { setMargins(0, dp(2), dp(10), dp(2)) } })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            titleRow.addView(iconView(iconRes, 20, Color.parseColor(colorHex)))
            titleRow.addView(makeText("  $label", 19f, true, Color.parseColor(colorHex)))
            titleRow.addView(iconView(R.drawable.ic_chevron, 20, Theme.textTertiary))
            col.addView(titleRow)
            col.addView(caption("$handled of ${list.size} handled • $done done • $skipped skipped • tap to open"))
            col.addView(hProgress(list.size, handled, Color.parseColor(colorHex)))
            row2.addView(col); card.addView(row2)
            sectionBox.addView(card)
        }
        root.addView(sectionBox)

        // Fixed finish bar
        val footRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val listBtn = makeSecondaryButton("View as list") { startActivity(Intent(this, SessionActivity::class.java).apply { putExtra("sessionId", sessionId) }) }
        (listBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48) }
        val finishBtn = makeButton("Finish Workout") {
            if (!allHandled) { AlertDialog.Builder(this).setTitle("Finish early?").setMessage("Some exercises are not done or skipped yet. Finish anyway?").setPositiveButton("Finish") { _, _ -> doFinish() }.setNegativeButton("Keep going", null).show() }
            else doFinish()
        }
        (finishBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1.2f; height = dp(48) }
        if (allHandled) finishBtn.background = roundedBg("#15803D", 16)
        footRow.addView(listBtn); footRow.addView(finishBtn)
        root.addView(footRow)
    }
    private fun doFinish() { val fresh = db.getSession(sessionId); if (fresh != null) WorkoutTimer.finish(this, db, fresh); db.setSessionCompleted(sessionId, true); startActivity(Intent(this, SummaryActivity::class.java).apply { putExtra("sessionId", sessionId) }); finish() }
}
