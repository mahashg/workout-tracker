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
            // v3.8.3 ROOT-CAUSE FIX: the cards were given height 0 +
            // weight 1 inside this page-filling box, so a page with ONE
            // session stretched that session's card to the full screen
            // height — and with the inner row at MATCH_PARENT height +
            // CENTER_VERTICAL gravity, the entry floated in the middle
            // of a giant empty card (the user's screenshot). Cards now
            // wrap their content and list top-down like a normal
            // history list; any leftover page space stays empty below.
            (card.layoutParams as LinearLayout.LayoutParams).apply { height = LinearLayout.LayoutParams.WRAP_CONTENT; weight = 0f; setMargins(0, dp(4), 0, dp(4)) }
            card.setPadding(dp(12), dp(10), dp(12), dp(10))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            info.addView(makeText("${DateUtil.display(s.date)} • Week ${s.weekNumber}", 11f, true, Theme.textSecondary))
            val name = makeText(s.routineName, 15f, true)
            name.maxLines = 1; name.ellipsize = android.text.TextUtils.TruncateAt.END
            info.addView(name)
            val durText = if (s.startedAt > 0) WorkoutTimer.formatDuration(if (s.completed) s.elapsedSec else WorkoutTimer.liveElapsedSec(this, s)) else ""
            // v3 sessions store focus as part ids ("back,biceps") — show names.
            // v3.11: circuit sessions store focus as "circuit:<id>" — label
            // them as a circuit instead of leaking the raw token.
            val focusLabel = when {
                s.focus.startsWith(CircuitsData.FOCUS_PREFIX) -> "⚡ Circuit"
                else -> s.focus.split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ") { tok -> BodyParts.byId(tok)?.name ?: tok }
            }
            // v3.8.3: build the sub-line from non-empty segments only —
            // a Movie Movements session has no focus parts, and the old
            // concatenation rendered a stray leading "· 8 min".
            val sub = makeText(listOf(focusLabel, durText).filter { it.isNotBlank() }.joinToString(" • "), 11f, false, Theme.textSecondary)
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
