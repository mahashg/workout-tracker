package com.mahesh.workouttracker

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class SummaryActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var sessionId: Long = -1
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); db = DbHelper(this); sessionId = intent.getLongExtra("sessionId", -1); if (sessionId < 0) { finish(); return }
        val session = db.getSession(sessionId) ?: run { finish(); return }
        val exs = db.getSessionExercises(sessionId)
        val root = fitRoot(); setContentView(root)
        root.addView(topBar("Workout Summary", "${DateUtil.display(session.date)} • Week ${session.weekNumber}"))

        // Explicit budgets (v2.5.1): hero 84 + stats 148 + by-section 92 +
        // note 18 + buttons 48 (+ fixed top bar 56) leaves a flexible filler.
        val celeb = heroCard()
        celeb.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(84)).apply { setMargins(0, 0, 0, 0) }
        celeb.setPadding(dp(12), dp(8), dp(12), dp(8))
        celeb.addView(makeText("Workout complete!", 23f, true, android.graphics.Color.WHITE).apply { gravity = Gravity.CENTER; (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0) })
        celeb.addView(makeText("${session.routineName} — nice work showing up and getting it done.", 12f, false, android.graphics.Color.parseColor("#DBEAFE")).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0) })
        root.addView(celeb)

        val done = exs.count { db.effectiveStatus(it) == "done" }; val skipped = exs.count { db.effectiveStatus(it) == "skipped" }; val setsDone = exs.sumOf { db.getSets(it.id).count { s -> s.isDone } }; val setsTotal = exs.sumOf { db.getSets(it.id).size }
        val muscles = linkedSetOf<String>(); for (ex in exs) if (db.effectiveStatus(ex) == "done") muscles.addAll(DbHelper.parseMuscles(ex.targetMuscles, ex.name))
        val card = cardLayout("#15803D")
        card.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(148)).apply { setMargins(0, 0, 0, 0) }
        card.setPadding(dp(12), dp(8), dp(12), dp(8))
        card.addView(overline("Today you trained"))
        card.addView(cardTitle(musclesLabel(muscles)))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        mid.addView(BodyMapView(this, muscles, true).apply { layoutParams = LinearLayout.LayoutParams(dp(84), dp(104)) })
        val stats = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(12), 0, 0, 0) }
        fun statRow(v: String, l: String) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(makeText(v, 20f, true, Theme.success).apply { layoutParams = LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT) })
            r.addView(caption(l))
            stats.addView(r)
        }
        statRow("$done", "exercises done")
        statRow("$setsDone/$setsTotal", "sets done")
        statRow("$skipped", "skipped")
        if (session.startedAt > 0) statRow(WorkoutTimer.formatDuration(session.elapsedSec), "time")
        mid.addView(stats)
        card.addView(mid)
        root.addView(card)

        val perCard = cardLayout()
        perCard.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(92)).apply { setMargins(0, 0, 0, 0) }
        perCard.setPadding(dp(12), dp(8), dp(12), dp(8))
        perCard.addView(overline("By section"))
        for (label in listOf("Warm Up" to "Warmup", "Exercise" to "Main", "Cool Down" to "Stretch")) {
            val list = exs.filter { it.type.equals(label.second, true) }; if (list.isEmpty()) continue
            perCard.addView(bodyText("${label.first}: ${list.count { db.effectiveStatus(it) == "done" }} done, ${list.count { db.effectiveStatus(it) == "skipped" }} skipped, ${list.count { db.effectiveStatus(it) == "pending" }} not done"))
        }
        root.addView(perCard)

        // Filler keeps the buttons pinned to the bottom of the fixed screen.
        root.addView(LinearLayout(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) })
        root.addView(makeText("Every workout counts — see you next session.", 12f, false, Theme.textSecondary))
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val doneBtn = makeButton("Done") { finish() }
        (doneBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48); setMargins(0, dp(4), dp(4), 0) }
        val listBtn = makeSecondaryButton("View as list") { startActivity(Intent(this, SessionActivity::class.java).apply { putExtra("sessionId", sessionId) }); finish() }
        (listBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48); setMargins(dp(4), dp(4), 0, 0) }
        btnRow.addView(doneBtn); btnRow.addView(listBtn)
        root.addView(btnRow)
    }
}
