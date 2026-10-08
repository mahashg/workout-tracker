package com.mahesh.workouttracker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class WorkoutDetailActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var routineId: Long = -1
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); db = DbHelper(this); Seed.ensureSeeded(db); Seed.applyV2IfNeeded(this, db); routineId = intent.getLongExtra("routineId", -1); if (routineId < 0) { finish(); return }; render() }
    override fun onResume() { super.onResume(); if (::db.isInitialized && routineId > 0) render() }

    private fun openPreview(type: String?) {
        startActivity(Intent(this, PreviewCardsActivity::class.java).apply {
            putExtra("routineId", routineId)
            if (type != null) putExtra("sectionType", type)
        })
    }

    private fun render() {
        val routine = db.getRoutine(routineId) ?: run { finish(); return }
        val state = WeekManager.reconcile(this, db)
        val sessionsWeek = db.sessionsInWeek(state.weekStart)
        val inProg = sessionsWeek.firstOrNull { it.routineId == routine.id && !it.completed } ?: db.inProgressSessions().firstOrNull { it.routineId == routine.id }
        val completed = sessionsWeek.firstOrNull { it.routineId == routine.id && it.completed }
        val sess = inProg ?: completed
        val root = fitRoot(); setContentView(root)
        root.addView(topBar(routine.name, DateUtil.dayName(routine.weekday)))

        // v2.6: ONE clean card — body-parts hero with the status pill and the
        // Start/Preview action pair inside it (the separate top Start card and
        // its floating button row are gone).
        val exs = db.getExercises(routine.id)
        val mainCount = exs.count { it.type.equals("Main", true) }
        val totalCount = exs.size
        // Hero + actions card. Explicit budget (v2.6.1): padding 16 + body-map
        // row 68 + status row 30 (pill has its own 24dp height, no margins) +
        // 48dp action pair + 6/4 gaps = 176dp. The pill can no longer touch
        // the card's bottom edge (it was clipped at 172dp with default margins).
        val hero = cardLayout("#F59E0B")
        hero.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(176)).apply { setMargins(0, 0, 0, 0) }
        hero.setPadding(dp(10), dp(8), dp(10), dp(8))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68)) }
        val muscles = db.routineMuscles(routine.id) // MAIN exercises only (v2.6.1)
        row.addView(BodyMapView(this, muscles, true).apply { layoutParams = LinearLayout.LayoutParams(dp(52), dp(64)) })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(10), 0, 0, 0) }
        val statusTxt = when { completed != null -> "Done"; inProg != null -> "In Progress"; else -> "Pending" }
        val hName = makeText(routine.name, 18f, true)
        hName.maxLines = 1; hName.ellipsize = android.text.TextUtils.TruncateAt.END
        (hName.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        info.addView(hName)
        // v2.6.1 count line (agreed mockup labeling): main count as "exercises",
        // total (incl. warm-up & cool-down) separate. Muscles live in the focus
        // line + body map — not repeated here (and never warm-up muscles).
        val hCap = caption("${routine.focus}\n${DateUtil.dayName(routine.weekday)} • $mainCount exercises • $totalCount total with warm-up & cool-down")
        hCap.maxLines = 2; hCap.ellipsize = android.text.TextUtils.TruncateAt.END
        (hCap.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        info.addView(hCap)
        row.addView(info); hero.addView(row)

        // Status row: pill ONLY (v2.6.1 copy audit — the trailing "Not started
        // yet / Pick up where you left off" line duplicated the pill; cut).
        // Explicit 30dp row, pill given its own 24dp height with margins stripped
        // so it can never clip against the buttons or the card edge.
        val pillRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(30)) }
        val pill = statusPill(statusTxt)
        (pill.layoutParams as LinearLayout.LayoutParams).apply { height = dp(24); setMargins(0, 0, 0, 0) }
        pill.setPadding(dp(10), 0, dp(10), 0)
        pillRow.addView(pill)
        hero.addView(pillRow)

        // ONE clean action pair: equal halves, 48dp, 15sp text, 20dp icons,
        // 12dp radius. tightButton strips the factory minHeight (56/52) that
        // was inflating both buttons past their 48dp budget (screenshot).
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)) }
        val startBtn = primaryButtonWithIcon(if (inProg != null) "Resume Workout" else if (completed != null) "Start Again" else "Start Workout", R.drawable.ic_play) {
            val sid = if (inProg != null) inProg.id else db.createSession(routine, state.weekNumber, state.weekStart)
            startActivity(Intent(this, WorkoutSectionsActivity::class.java).apply { putExtra("sessionId", sid) })
        }
        (startBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48); setMargins(0, 0, dp(4), 0) }
        tightButton(startBtn, 48, 12, primary = true)
        startBtn.setPadding(dp(8), 0, dp(8), 0)
        val previewBtn = makeSecondaryButton("Preview") { openPreview(null) }
        previewBtn.setCompoundDrawables(boundedIcon(R.drawable.ic_eye, 20, Theme.primary), null, null, null)
        previewBtn.compoundDrawablePadding = dp(6)
        (previewBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48); setMargins(dp(4), 0, 0, 0) }
        tightButton(previewBtn, 48, 12, primary = false)
        previewBtn.setPadding(dp(8), 0, dp(8), 0)
        btnRow.addView(startBtn); btnRow.addView(previewBtn)
        hero.addView(btnRow)
        root.addView(hero)

        // Sections: three compact rows (tap -> per-section preview). v2.6.1
        // copy audit: no "What you'll do — tap a section…" explainer header;
        // the rows carry their own counts and the chevron affordance.
        val sectionBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        for ((label, type) in listOf("Warm Up" to "Warmup", "Exercise" to "Main", "Cool Down" to "Stretch")) {
            val colorHex = when (type) { "Warmup" -> "#D97706"; "Stretch" -> "#0D9488"; else -> "#2563EB" }
            val card = cardLayout(colorHex, tappable = true) { openPreview(type) }
            (card.layoutParams as LinearLayout.LayoutParams).apply { height = 0; weight = 1f; setMargins(0, dp(4), 0, dp(4)) }
            card.setPadding(dp(12), dp(6), dp(12), dp(6))
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT) }
            r.addView(View(this).apply { setBackgroundColor(Color.parseColor(colorHex)); layoutParams = LinearLayout.LayoutParams(dp(5), LinearLayout.LayoutParams.MATCH_PARENT).apply { setMargins(0, dp(2), dp(10), dp(2)) } })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            col.addView(makeText(label, 16f, true, Color.parseColor(colorHex)))
            val line = if (sess != null) {
                val list = db.getSessionExercises(sess.id).filter { it.type.equals(type, true) }
                val done = list.count { db.effectiveStatus(it) == "done" }; val skipped = list.count { db.effectiveStatus(it) == "skipped" }
                "${done + skipped} of ${list.size} done/skipped"
            } else {
                val list = exs.filter { it.type.equals(type, true) }
                "${list.size} activities"
            }
            col.addView(caption(line))
            r.addView(col)
            if (sess != null) {
                val list = db.getSessionExercises(sess.id).filter { it.type.equals(type, true) }
                val handled = list.count { db.effectiveStatus(it) != "pending" }
                col.addView(hProgress(list.size, handled, Color.parseColor(colorHex)))
            }
            r.addView(iconView(R.drawable.ic_chevron, 20, Theme.textTertiary))
            card.addView(r)
            sectionBox.addView(card)
        }
        root.addView(sectionBox)

        if (sess != null) {
            val openBtn = makeButton("Open Sections") { startActivity(Intent(this, WorkoutSectionsActivity::class.java).apply { putExtra("sessionId", sess.id) }) }
            (openBtn.layoutParams as LinearLayout.LayoutParams).apply { height = dp(48); setMargins(0, dp(4), 0, 0) }
            root.addView(openBtn)
        }
    }
}
