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

        // Compact header. Explicit 112dp budget (v2.5.1): map+name row (64),
        // timer/status line (30), beginner note folded into the caption line.
        val header = cardLayout()
        header.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(112)).apply { setMargins(0, 0, 0, 0) }
        header.setPadding(dp(10), dp(6), dp(10), dp(6))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)) }
        row.addView(BodyMapView(this, muscles, true).apply { layoutParams = LinearLayout.LayoutParams(dp(48), dp(58)) })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(10), 0, 0, 0) }
        val sName = makeText(session.routineName, 17f, true)
        sName.maxLines = 1; sName.ellipsize = android.text.TextUtils.TruncateAt.END
        (sName.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        info.addView(sName)
        val statusTxt = if (session.completed) "Done" else if (exs.any { db.effectiveStatus(it) != "pending" }) "In Progress" else "Pending"
        // v2.6.1 copy audit: no "Beginner Mode ON" suffix (duplicates the
        // Settings toggle); focus • muscles • status is the one short line.
        val sCap = caption("${session.focus} • ${musclesLabel(muscles)} • $statusTxt")
        sCap.maxLines = 2; sCap.ellipsize = android.text.TextUtils.TruncateAt.END
        (sCap.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        info.addView(sCap)
        row.addView(info); header.addView(row)
        if (!session.completed) {
            db.ensureSessionStarted(sessionId)
            val chip = TimerChipView(this)
            chip.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30)).apply { setMargins(0, dp(4), 0, 0) }
            chip.bind(this, db, db.getSession(sessionId) ?: session) {}
            chip.startTicking()
            timerChip = chip
            header.addView(chip)
        } else if (session.startedAt > 0) {
            header.addView(caption("Time: ${WorkoutTimer.formatDuration(session.elapsedSec)}").apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(4), 0, 0) })
        }
        root.addView(header)

        // Three section cards share the remaining height. (v2.6.1 copy audit:
        // the "Today's sections" header duplicated the top bar — removed.)
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
            col.addView(caption("$done done • $skipped skipped"))
            col.addView(hProgress(list.size, handled, Color.parseColor(colorHex)))
            row2.addView(col); card.addView(row2)
            sectionBox.addView(card)
        }
        root.addView(sectionBox)
        val allHandled = exs.isNotEmpty() && exs.all { db.effectiveStatus(it) != "pending" }

        // Fixed finish bar
        val footRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val listBtn = makeSecondaryButton("View as list") { startActivity(Intent(this, SessionActivity::class.java).apply { putExtra("sessionId", sessionId) }) }
        (listBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48); setMargins(0, dp(4), dp(4), 0) }
        val finishBtn = makeButton("Finish Workout") {
            if (!allHandled) { AlertDialog.Builder(this).setTitle("Finish early?").setMessage("Some exercises are not done or skipped yet. Finish anyway?").setPositiveButton("Finish") { _, _ -> doFinish() }.setNegativeButton("Keep going", null).show() }
            else doFinish()
        }
        (finishBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1.2f; height = dp(48); setMargins(dp(4), dp(4), 0, 0) }
        if (allHandled) finishBtn.background = roundedBg("#15803D", 16)
        footRow.addView(listBtn); footRow.addView(finishBtn)
        root.addView(footRow)
    }
    private fun doFinish() { val fresh = db.getSession(sessionId); if (fresh != null) WorkoutTimer.finish(this, db, fresh); db.setSessionCompleted(sessionId, true); startActivity(Intent(this, SummaryActivity::class.java).apply { putExtra("sessionId", sessionId) }); finish() }
}
