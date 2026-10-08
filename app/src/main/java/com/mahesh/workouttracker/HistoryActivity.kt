package com.mahesh.workouttracker

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class HistoryActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); db = DbHelper(this); render() }
    override fun onResume() { super.onResume(); if (::db.isInitialized) render() }
    private fun render() {
        val root = tabScaffold("History")
        root.addView(screenTitle("History"))
        val sessions = db.getSessions()
        if (sessions.isEmpty()) root.addView(caption("No sessions yet."))
        for (s in sessions) {
            val open = { val i=Intent(this, SessionActivity::class.java); i.putExtra("sessionId", s.id); startActivity(i) }
            val card = cardLayout(tappable = true) { open() }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            info.addView(makeText("${DateUtil.display(s.date)} • Week ${s.weekNumber}", 12f, true, Theme.textSecondary))
            info.addView(makeText(s.routineName, 16f, true))
            info.addView(caption(s.focus))
            info.addView(statusPill(if (s.completed) "Done" else "In Progress"))
            row.addView(info)
            row.addView(makeText("›", 26f, true, Theme.textTertiary))
            card.addView(row)
            card.addView(makeSecondaryButton("Open") { open() })
            root.addView(card)
        }
    }
}
