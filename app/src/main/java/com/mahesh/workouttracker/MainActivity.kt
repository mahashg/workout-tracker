package com.mahesh.workouttracker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
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

    private fun render() {
        val state = WeekManager.reconcile(this, db)
        val weekStartCal = DateUtil.parse(state.weekStart)
        val weekEndCal = DateUtil.saturdayOf(weekStartCal)
        val weekStartStr = state.weekStart
        val todayIdx = DateUtil.weekdayIndex(DateUtil.today())
        val routines = db.getRoutines()
        val sessionsThisWeek = db.sessionsInWeek(weekStartStr)
        val root = tabScaffold("Home")

        // ---- Hero header card (gradient) ----
        val doneCount = routines.count { r -> sessionsThisWeek.any { it.routineId == r.id && it.completed } }
        val hero = heroCard()
        hero.addView(makeText("WORKOUT TRACKER", 11f, true, Color.parseColor("#BFDBFE")))
        hero.addView(makeText("Week ${state.weekNumber}", 30f, true, Color.WHITE))
        hero.addView(makeText("${DateUtil.display(weekStartStr)} – ${DateUtil.display(DateUtil.fmt(weekEndCal))}  •  Sun – Sat", 13f, false, Color.parseColor("#DBEAFE")))
        if (routines.isNotEmpty()) {
            hero.addView(makeText("$doneCount of ${routines.size} done this week", 14f, true, Color.WHITE))
            hero.addView(hProgress(routines.size, doneCount, Color.parseColor("#4ADE80")).apply { background = roundedBg("#3B82F6", 8) })
        }
        root.addView(hero)

        // ---- Muscles trained this week (factual progress) ----
        val weekMuscles = linkedSetOf<String>()
        for (s in sessionsThisWeek.filter{it.completed}) for (ex in db.getSessionExercises(s.id)) if (db.effectiveStatus(ex)=="done") weekMuscles.addAll(DbHelper.parseMuscles(ex.targetMuscles, ex.name))
        if (weekMuscles.isNotEmpty()) {
            val wc = cardLayout()
            wc.addView(overline("This week"))
            val wr = LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            wr.addView(BodyMapView(this@MainActivity, weekMuscles).apply{layoutParams=LinearLayout.LayoutParams(dp(64),dp(80))})
            val wi = LinearLayout(this).apply{orientation=LinearLayout.VERTICAL; layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f); setPadding(dp(12),0,0,0)}
            wi.addView(cardTitle("Muscles trained"))
            wi.addView(caption("$doneCount of ${routines.size} workouts • ${musclesLabel(weekMuscles)}"))
            wr.addView(wi); wc.addView(wr); root.addView(wc)
        }

        // ---- Suggested today (featured card) ----
        val suggested = routines.firstOrNull { it.weekday == todayIdx }
        root.addView(overline("Suggested today"))
        if (suggested != null) {
            val st = statusFor(suggested, sessionsThisWeek)
            val progS = sessionsThisWeek.firstOrNull { it.routineId==suggested.id && !it.completed }
            val sugCard = cardLayout("#F59E0B", tappable = true) { openDetail(suggested.id) }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val muscles = db.routineMuscles(suggested.id)
            row.addView(BodyMapView(this, muscles).apply { layoutParams = LinearLayout.LayoutParams(dp(84), dp(104)) })
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(12),0,0,0) }
            info.addView(makeText("${DateUtil.dayName(todayIdx)}", 12f, true, Color.parseColor("#92400E")))
            info.addView(makeText(suggested.name, 20f, true))
            info.addView(caption("${suggested.focus}\n${db.routineExerciseCount(suggested.id)} exercises • ${musclesLabel(muscles)}"))
            info.addView(statusPill(st))
            row.addView(info); sugCard.addView(row)
            sugCard.addView(makeButton(if (progS!=null) "▶  Resume Workout" else if (st=="Done") "↻  Do Again" else "▶  View Workout") { openDetail(suggested.id) })
            root.addView(sugCard)
        } else {
            val restCard = cardLayout("#F59E0B")
            restCard.addView(cardTitle("🌙 Rest day"))
            restCard.addView(caption("No workout assigned for ${DateUtil.dayName(todayIdx)}. Pick any pending workout below if you want to train."))
            root.addView(restCard)
        }

        // ---- Resume banner ----
        val inProg = db.inProgressSessions().firstOrNull { it.weekStart == weekStartStr } ?: db.inProgressSessions().firstOrNull()
        if (inProg != null && (suggested==null || inProg.routineId != suggested.id)) {
            val card = cardLayout("#F59E0B", tappable = true) { openSections(inProg.id) }
            card.addView(makeText("● IN PROGRESS", 11f, true, Color.parseColor("#92400E")))
            card.addView(cardTitle(inProg.routineName))
            card.addView(caption("Started ${DateUtil.display(inProg.date)} • Week ${inProg.weekNumber}"))
            card.addView(makeButton("Resume Workout") { openSections(inProg.id) })
            root.addView(card)
        }

        // ---- This Week list ----
        root.addView(sectionLabelText("This Week"))
        if (routines.isEmpty()) root.addView(caption("No routines yet. Add one in Routines."))
        for (r in routines) {
            val done = sessionsThisWeek.any { it.routineId == r.id && it.completed }
            val prog = sessionsThisWeek.firstOrNull { it.routineId == r.id && !it.completed }
            val status = statusFor(r, sessionsThisWeek)
            val card = cardLayout(if (r.id==suggested?.id) "#F59E0B" else null, tappable = true) { openDetail(r.id) }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val muscles = db.routineMuscles(r.id)
            row.addView(BodyMapView(this, muscles).apply { layoutParams = LinearLayout.LayoutParams(dp(64), dp(80)) })
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(12),0,0,0) }
            val title = makeText("${if(done) "✓ " else ""}${r.name}", 17f, true)
            if (done) strike(title, true)
            info.addView(title)
            info.addView(caption("${r.focus}\n${db.routineExerciseCount(r.id)} exercises • ${musclesLabel(muscles)}"))
            info.addView(statusPill(status))
            row.addView(info)
            val chev = makeText("›", 26f, true, Theme.textTertiary).apply { layoutParams = LinearLayout.LayoutParams(dp(24), LinearLayout.LayoutParams.WRAP_CONTENT) }
            row.addView(chev)
            card.addView(row)
            val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            when {
                prog != null -> btnRow.addView(makeSmallButton("▶ Resume ▸") { openDetail(r.id) })
                done -> btnRow.addView(makeSmallButton("↻ Do Again ▸") { openDetail(r.id) })
                else -> btnRow.addView(makeSmallButton("▶ View ▸") { openDetail(r.id) })
            }
            btnRow.addView(makeText("  ${DateUtil.dayName(r.weekday)}", 12f, true, Theme.textSecondary))
            if (done) btnRow.addView(makeSmallButton("View Log") { openSession(sessionsThisWeek.first { it.routineId == r.id && it.completed }.id) })
            card.addView(btnRow)
            root.addView(card)
        }
    }
    private fun statusFor(r: Routine, sessionsThisWeek: List<SessionInfo>): String = when {
        sessionsThisWeek.any { it.routineId==r.id && it.completed } -> "Done"
        sessionsThisWeek.any { it.routineId==r.id && !it.completed } -> "In Progress"
        else -> "Pending"
    }
    private fun openDetail(routineId: Long) { startActivity(Intent(this, WorkoutDetailActivity::class.java).apply { putExtra("routineId", routineId) }) }
    private fun openSections(id: Long) { startActivity(Intent(this, WorkoutSectionsActivity::class.java).apply { putExtra("sessionId", id) }) }
    private fun openSession(id: Long) { startActivity(Intent(this, SessionActivity::class.java).apply { putExtra("sessionId", id) }) }
}
