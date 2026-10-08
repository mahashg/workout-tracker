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
        val wuCount = exs.count { it.type.equals("Warmup", true) }
        val cdCount = exs.count { it.type.equals("Stretch", true) }
        // Hero + actions card. Explicit budget (v2.6): body-map row 64 + status
        // pill row ~22 + 48dp action pair + padding = 172dp.
        val hero = cardLayout("#F59E0B")
        hero.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(172)).apply { setMargins(0, 0, 0, 0) }
        hero.setPadding(dp(10), dp(6), dp(10), dp(6))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)) }
        val muscles = db.routineMuscles(routine.id)
        row.addView(BodyMapView(this, muscles, true).apply { layoutParams = LinearLayout.LayoutParams(dp(52), dp(64)) })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(10), 0, 0, 0) }
        val statusTxt = when { completed != null -> "Done"; inProg != null -> "In Progress"; else -> "Pending" }
        val hName = makeText(routine.name, 18f, true)
        hName.maxLines = 1; hName.ellipsize = android.text.TextUtils.TruncateAt.END
        (hName.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        info.addView(hName)
        val hCap = caption("${routine.focus}\n${DateUtil.dayName(routine.weekday)} • $mainCount exercises ($wuCount Warm Up • $mainCount Exercise • $cdCount Cool Down) • ${musclesLabel(muscles)}")
        hCap.maxLines = 3; hCap.ellipsize = android.text.TextUtils.TruncateAt.END
        (hCap.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        info.addView(hCap)
        row.addView(info); hero.addView(row)

        // Status pill row above the action pair.
        val pillRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(26)) }
        pillRow.addView(statusPill(statusTxt))
        pillRow.addView(makeText(if (inProg != null) "  Pick up where you left off" else if (completed != null) "  Done this week — go again anytime" else "  Not started yet", 12f, false, Theme.textSecondary).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        hero.addView(pillRow)

        // ONE clean action pair: equal halves, same 48dp height, 8dp gap.
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)) }
        val startBtn = primaryButtonWithIcon(if (inProg != null) "Resume Workout" else if (completed != null) "Start Again" else "Start Workout", R.drawable.ic_play) {
            val sid = if (inProg != null) inProg.id else db.createSession(routine, state.weekNumber, state.weekStart)
            startActivity(Intent(this, WorkoutSectionsActivity::class.java).apply { putExtra("sessionId", sid) })
        }
        (startBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48); setMargins(0, 0, dp(4), 0) }
        val previewBtn = makeSecondaryButton("Preview") { openPreview(null) }
        previewBtn.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_eye, 0, 0, 0)
        previewBtn.compoundDrawablePadding = dp(6)
        try { previewBtn.compoundDrawables[0]?.setTint(Theme.primary) } catch (e: Exception) {}
        (previewBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48); setMargins(dp(4), 0, 0, 0) }
        btnRow.addView(startBtn); btnRow.addView(previewBtn)
        hero.addView(btnRow)
        root.addView(hero)

        // Sections: three compact rows (tap -> per-section preview). Names live
        // in the preview cards; this page only carries counts/progress.
        root.addView(makeText(if (sess != null) "Your progress — tap a section to preview its cards" else "What you'll do — tap a section to preview its cards", 13f, true, Theme.textSecondary).apply {
            (layoutParams as LinearLayout.LayoutParams).apply { height = dp(24); setMargins(0, 0, 0, 0) }
            gravity = Gravity.CENTER_VERTICAL
        })
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
                "${done + skipped} of ${list.size} done/skipped • tap to preview ›"
            } else {
                val list = exs.filter { it.type.equals(type, true) }
                "${list.size} activities • tap to preview ›"
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
