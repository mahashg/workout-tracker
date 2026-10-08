package com.mahesh.workouttracker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = DbHelper(this)
        Seed.ensureSeeded(db)
        Seed.applyV2IfNeeded(this, db)
        render()
    }
    override fun onResume() { super.onResume(); if (::db.isInitialized) render() }

    private fun compactRow(heightDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)).apply { setMargins(0, dp(3), 0, dp(3)) }

    private fun render() {
        val state = WeekManager.reconcile(this, db)
        val weekStartCal = DateUtil.parse(state.weekStart)
        val weekEndCal = DateUtil.saturdayOf(weekStartCal)
        val weekStartStr = state.weekStart
        val todayIdx = DateUtil.weekdayIndex(DateUtil.today())
        val routines = db.getRoutines()
        val sessionsThisWeek = db.sessionsInWeek(weekStartStr)
        val root = tabScaffold("Home")
        val compact = isCompactScreen()

        // ---- Hero header card (gradient, compact) ----
        val doneCount = routines.count { r -> sessionsThisWeek.any { it.routineId == r.id && it.completed } }
        val hero = heroCard()
        val heroRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val heroInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        heroInfo.addView(makeText("WORKOUT TRACKER", 11f, true, Color.parseColor("#BFDBFE")))
        heroInfo.addView(makeText("Week ${state.weekNumber}", if (compact) 24f else 28f, true, Color.WHITE))
        heroInfo.addView(makeText("${DateUtil.display(weekStartStr)} – ${DateUtil.display(DateUtil.fmt(weekEndCal))}  •  Sun – Sat", 12f, false, Color.parseColor("#DBEAFE")))
        heroRow.addView(heroInfo)
        if (routines.isNotEmpty()) heroRow.addView(makeText("$doneCount / ${routines.size}\ndone", 15f, true, Color.WHITE).apply { gravity = Gravity.CENTER })
        hero.addView(heroRow)
        if (routines.isNotEmpty()) {
            hero.addView(hProgress(routines.size, doneCount, Color.parseColor("#4ADE80")).apply { background = roundedBg("#3B82F6", 8) })
        }
        // Muscles trained this week folded into the hero (no separate card).
        val weekMuscles = linkedSetOf<String>()
        for (s in sessionsThisWeek.filter { it.completed }) for (ex in db.getSessionExercises(s.id)) if (db.effectiveStatus(ex) == "done") weekMuscles.addAll(DbHelper.parseMuscles(ex.targetMuscles, ex.name))
        if (weekMuscles.isNotEmpty()) hero.addView(makeText("Trained: ${musclesLabel(weekMuscles)}", 11f, false, Color.parseColor("#DBEAFE")))
        root.addView(hero)

        // ---- TODAY hero (daily loop: one primary action) ----
        val suggested = routines.firstOrNull { it.weekday == todayIdx }
        root.addView(overline("Today"))
        if (suggested != null) {
            val st = statusFor(suggested, sessionsThisWeek)
            val progS = sessionsThisWeek.firstOrNull { it.routineId == suggested.id && !it.completed }
            val sugCard = cardLayout("#F59E0B", tappable = true) { openDetail(suggested.id) }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val muscles = db.routineMuscles(suggested.id)
            row.addView(BodyMapView(this, muscles).apply { layoutParams = LinearLayout.LayoutParams(dp(52), dp(64)) })
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(10), 0, 0, 0) }
            info.addView(makeText("${DateUtil.dayName(todayIdx)} • ${suggested.focus}", 11f, true, Color.parseColor("#92400E")))
            info.addView(makeText(suggested.name, 18f, true))
            val exsToday = db.getExercises(suggested.id)
            val wu = exsToday.count { it.type.equals("Warmup", true) }
            val main = exsToday.count { it.type.equals("Main", true) }
            val cd = exsToday.count { it.type.equals("Stretch", true) }
            var sub = "$wu Warm Up • $main Exercise • $cd Cool Down • ${musclesLabel(muscles)}"
            if (progS != null && progS.startedAt > 0) sub += "\n${WorkoutTimer.formatDuration(WorkoutTimer.liveElapsedSec(this, progS))} so far"
            info.addView(caption(sub))
            info.addView(statusPill(st))
            row.addView(info); sugCard.addView(row)
            val startBtn = primaryButtonWithIcon(if (progS != null) "Resume Workout" else if (st == "Done") "Do Again" else "Start Workout", R.drawable.ic_play) { openDetail(suggested.id) }
            (startBtn.layoutParams as LinearLayout.LayoutParams).height = dp(48)
            sugCard.addView(startBtn)
            root.addView(sugCard)
        } else {
            val restCard = cardLayout("#F59E0B")
            restCard.addView(cardTitle("Rest day"))
            restCard.addView(caption("No workout assigned for ${DateUtil.dayName(todayIdx)}. Pick any pending workout below."))
            root.addView(restCard)
        }

        // ---- Resume banner (compact single row, only when it differs from today's) ----
        val inProg = db.inProgressSessions().firstOrNull { it.weekStart == weekStartStr } ?: db.inProgressSessions().firstOrNull()
        if (inProg != null && (suggested == null || inProg.routineId != suggested.id)) {
            val card = cardLayout("#F59E0B", tappable = true) { openSections(inProg.id) }
            card.layoutParams = compactRow(if (compact) 54 else 60)
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            info.addView(makeText("● IN PROGRESS — ${inProg.routineName}", 14f, true, Color.parseColor("#92400E")))
            info.addView(caption("Week ${inProg.weekNumber}${if (inProg.startedAt > 0) " • ${WorkoutTimer.formatDuration(WorkoutTimer.liveElapsedSec(this, inProg))} so far" else ""} • tap to resume"))
            r.addView(info)
            r.addView(iconView(R.drawable.ic_chevron, 20, Theme.textTertiary))
            card.addView(r)
            root.addView(card)
        }

        // ---- This Week: flexible region of compact (~52dp) rows ----
        root.addView(makeText("This Week", 16f, true).apply { setPadding(0, dp(4), 0, 0) })
        val weekBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        if (routines.isEmpty()) weekBox.addView(caption("No routines yet. Add one in Routines."))
        for (r in routines) {
            val done = sessionsThisWeek.any { it.routineId == r.id && it.completed }
            val status = statusFor(r, sessionsThisWeek)
            val card = cardLayout(if (r.id == suggested?.id) "#F59E0B" else null, tappable = true) { openDetail(r.id) }
            (card.layoutParams as LinearLayout.LayoutParams).apply { height = 0; weight = 1f; setMargins(0, dp(3), 0, dp(3)) }
            card.setPadding(dp(10), dp(4), dp(10), dp(4))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT) }
            val muscles = db.routineMuscles(r.id)
            row.addView(BodyMapView(this, muscles).apply { layoutParams = LinearLayout.LayoutParams(dp(34), dp(42)) })
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(8), 0, 0, 0) }
            val title = makeText("${if (done) "✓ " else ""}${r.name}", 14f, true)
            if (done) strike(title, true)
            title.maxLines = 1; title.ellipsize = android.text.TextUtils.TruncateAt.END
            info.addView(title)
            val sub = makeText("${DateUtil.dayName(r.weekday)} • ${db.routineExerciseCount(r.id)} exercises • ${musclesLabel(muscles)}", 11f, false, Theme.textSecondary)
            sub.maxLines = 1; sub.ellipsize = android.text.TextUtils.TruncateAt.END
            info.addView(sub)
            row.addView(info)
            row.addView(statusPill(status))
            row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
            row.addView(iconView(R.drawable.ic_chevron, 18, Theme.textTertiary))
            card.addView(row)
            weekBox.addView(card)
        }
        root.addView(weekBox)
    }
    private fun statusFor(r: Routine, sessionsThisWeek: List<SessionInfo>): String = when {
        sessionsThisWeek.any { it.routineId == r.id && it.completed } -> "Done"
        sessionsThisWeek.any { it.routineId == r.id && !it.completed } -> "In Progress"
        else -> "Pending"
    }
    private fun openDetail(routineId: Long) { startActivity(Intent(this, WorkoutDetailActivity::class.java).apply { putExtra("routineId", routineId) }) }
    private fun openSections(id: Long) { startActivity(Intent(this, WorkoutSectionsActivity::class.java).apply { putExtra("sessionId", id) }) }
}
