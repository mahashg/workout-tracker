package com.mahesh.workouttracker

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class HistoryActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var page: Int = 0
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); db = DbHelper(this); render() }
    override fun onResume() { super.onResume(); if (::db.isInitialized) render() }
    private fun render() {
        val root = tabScaffold("History")
        root.addView(screenTitle("History").apply {
            (layoutParams as LinearLayout.LayoutParams).apply { height = dp(40); setMargins(0, 0, 0, 0) }
            gravity = Gravity.CENTER_VERTICAL
        })
        val sessions = db.getSessions()
        val pageSize = 5
        val pageCount = ((sessions.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        if (page !in 0 until pageCount) page = pageCount - 1
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        if (sessions.isEmpty()) box.addView(caption("No sessions yet."))
        for (s in sessions.drop(page * pageSize).take(pageSize)) {
            val open = { val i = Intent(this, SessionActivity::class.java); i.putExtra("sessionId", s.id); startActivity(i) }
            val card = cardLayout(tappable = true) { open() }
            (card.layoutParams as LinearLayout.LayoutParams).apply { height = 0; weight = 1f; setMargins(0, dp(4), 0, dp(4)) }
            card.setPadding(dp(12), dp(6), dp(12), dp(6))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT) }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            info.addView(makeText("${DateUtil.display(s.date)} • Week ${s.weekNumber}", 11f, true, Theme.textSecondary))
            val name = makeText(s.routineName, 15f, true)
            name.maxLines = 1; name.ellipsize = android.text.TextUtils.TruncateAt.END
            info.addView(name)
            val dur = if (s.startedAt > 0) " • ${WorkoutTimer.formatDuration(if (s.completed) s.elapsedSec else WorkoutTimer.liveElapsedSec(this, s))}" else ""
            val sub = makeText(s.focus + dur, 11f, false, Theme.textSecondary)
            sub.maxLines = 1; sub.ellipsize = android.text.TextUtils.TruncateAt.END
            info.addView(sub)
            row.addView(info)
            row.addView(statusPill(if (s.completed) "Done" else "In Progress"))
            row.addView(iconView(R.drawable.ic_chevron, 20))
            card.addView(row)
            box.addView(card)
        }
        root.addView(box)
        if (pageCount > 1) root.addView(pagerBar(page, pageCount, { page--; render() }, { page++; render() }))
    }
}
