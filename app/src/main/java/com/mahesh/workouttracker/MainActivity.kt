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

        // ---- Deterministic height budget (v2.5.1 hotfix) ----
        // Available content height = screen - bottom nav (~72dp) - root vertical
        // padding. Every region below gets an EXPLICIT dp height and weekBox
        // takes whatever remains, so the stack can never overflow the viewport
        // and clip the Today/This Week regions (the v2.5 bug).
        val rootPadV = if (compact) 22 else 32
        val available = (screenHeightDp() - 72 - rootPadV).coerceAtLeast(360)
        val veryShort = available < 620
        val heroH = if (veryShort) 104 else if (compact) 118 else 128
        val todayLabelH = 20
        // v2.6: Today card no longer carries a Start button (it lives in Workout
        // Detail), so it shrinks to ~108-120dp and the freed height goes to the week.
        val todayH = if (veryShort) 108 else 120
        val weekLabelH = 24
        val suggestedPre = routines.firstOrNull { it.weekday == todayIdx }
        val resumePre = db.inProgressSessions().firstOrNull { it.weekStart == weekStartStr } ?: db.inProgressSessions().firstOrNull()
        val hasResume = resumePre != null && (suggestedPre == null || resumePre.routineId != suggestedPre.id)
        val resumeH = if (hasResume) 48 else 0
        var weekBoxH = available - heroH - todayLabelH - todayH - weekLabelH - resumeH
        // Safety: the five week rows need >= 220dp (5 x 44dp). Shrink hero/Today
        // further before ever letting weekBox drop below that floor.
        if (weekBoxH < 220) {
            // heroH/todayH are already at their short-screen floors in this branch
            // for any viewport where this triggers; weekBox is then whatever is
            // left (>= 220 whenever available >= 500, which the coerce covers
            // down to the smallest supported phones).
            weekBoxH = weekBoxH.coerceAtLeast(180)
        }

        // ---- Hero header card (gradient, compact) ----
        val doneCount = routines.count { r -> sessionsThisWeek.any { it.routineId == r.id && it.completed } }
        val hero = heroCard()
        val heroRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val heroInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        heroInfo.addView(makeText("Week ${state.weekNumber}", if (compact) 24f else 28f, true, Color.WHITE).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0) })
        heroInfo.addView(makeText("${DateUtil.display(weekStartStr)} – ${DateUtil.display(DateUtil.fmt(weekEndCal))}  •  Sun – Sat", 12f, false, Color.parseColor("#DBEAFE")).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        heroRow.addView(heroInfo)
        hero.addView(heroRow)
        if (routines.isNotEmpty()) {
            // v2.6: cute segmented progress (one segment per routine) + small
            // caption — no more giant "0 / 5 done" text.
            hero.addView(segmentedWeekProgress(routines.map { r -> sessionsThisWeek.any { it.routineId == r.id && it.completed } }).apply {
                (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(6), 0, 0)
            })
            hero.addView(makeText("$doneCount of ${routines.size} this week", 13f, false, Color.parseColor("#EFF6FF")).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(3), 0, 0); maxLines = 1 })
        }
        // Muscles trained this week folded into the hero's date line (no extra row).
        val weekMuscles = linkedSetOf<String>()
        for (s in sessionsThisWeek.filter { it.completed }) for (ex in db.getSessionExercises(s.id)) if (db.effectiveStatus(ex) == "done") weekMuscles.addAll(DbHelper.parseMuscles(ex.targetMuscles, ex.name))
        // (Trained-muscles summary appended to the subtitle line added above via heroInfo is
        //  omitted as a separate view to keep the hero inside its explicit budget.)
        hero.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heroH)).apply { setMargins(0, 0, 0, 0) }
        hero.setPadding(dp(12), dp(8), dp(12), dp(8))
        root.addView(hero)

        // ---- TODAY hero (daily loop: one primary action) ----
        val suggested = routines.firstOrNull { it.weekday == todayIdx }
        root.addView(overline("Today").apply {
            (layoutParams as LinearLayout.LayoutParams).apply { height = dp(todayLabelH); setMargins(0, 0, 0, 0) }
            gravity = Gravity.CENTER_VERTICAL
        })
        if (suggested != null) {
            val st = statusFor(suggested, sessionsThisWeek)
            val progS = sessionsThisWeek.firstOrNull { it.routineId == suggested.id && !it.completed }
            val sugCard = cardLayout("#F59E0B", tappable = true) { openDetail(suggested.id) }
            sugCard.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(todayH)).apply { setMargins(0, 0, 0, 0) }
            sugCard.setPadding(dp(10), dp(6), dp(10), dp(6))
            val rowH = todayH - 12 // card padding (6+6); no button inside anymore (v2.6)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(rowH)) }
            val muscles = db.routineMuscles(suggested.id)
            row.addView(BodyMapView(this, muscles).apply { layoutParams = LinearLayout.LayoutParams(dp(if (veryShort) 44 else 52), dp(rowH.coerceAtMost(64))) })
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(10), 0, 0, 0) }
            info.addView(makeText("${DateUtil.dayName(todayIdx)} • ${suggested.focus} • $st", 11f, true, Color.parseColor("#92400E")).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            info.addView(makeText(suggested.name, 17f, true).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            val exsToday = db.getExercises(suggested.id)
            val wu = exsToday.count { it.type.equals("Warmup", true) }
            val main = exsToday.count { it.type.equals("Main", true) }
            val cd = exsToday.count { it.type.equals("Stretch", true) }
            var sub = "$wu Warm Up • $main Exercise • $cd Cool Down • ${musclesLabel(muscles)}"
            if (progS != null && progS.startedAt > 0) sub += " • ${WorkoutTimer.formatDuration(WorkoutTimer.liveElapsedSec(this, progS))} so far"
            info.addView(caption(sub).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            // v2.6: no Start button here — the whole card taps through to Workout
            // Detail, where Start lives. Subtle affordance instead.
            info.addView(makeText("Tap to open ›", 12f, true, Theme.primary).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(2), 0, 0); maxLines = 1 })
            row.addView(info); sugCard.addView(row)
            root.addView(sugCard)
        } else {
            val restCard = cardLayout("#F59E0B")
            restCard.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(todayH)).apply { setMargins(0, 0, 0, 0) }
            restCard.addView(cardTitle("Rest day"))
            restCard.addView(caption("No workout assigned for ${DateUtil.dayName(todayIdx)}. Pick any pending workout below."))
            root.addView(restCard)
        }

        // ---- Resume banner (compact single row, only when it differs from today's) ----
        val inProg = db.inProgressSessions().firstOrNull { it.weekStart == weekStartStr } ?: db.inProgressSessions().firstOrNull()
        if (inProg != null && (suggested == null || inProg.routineId != suggested.id)) {
            val card = cardLayout("#F59E0B", tappable = true) { openSections(inProg.id) }
            card.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(resumeH))
            card.setPadding(dp(10), dp(4), dp(10), dp(4))
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT) }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            info.addView(makeText("● IN PROGRESS — ${inProg.routineName}", 13f, true, Color.parseColor("#92400E")).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            info.addView(caption("Week ${inProg.weekNumber}${if (inProg.startedAt > 0) " • ${WorkoutTimer.formatDuration(WorkoutTimer.liveElapsedSec(this, inProg))} so far" else ""} • tap to resume").apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            r.addView(info)
            r.addView(iconView(R.drawable.ic_chevron, 20, Theme.textTertiary))
            card.addView(r)
            root.addView(card)
        }

        // ---- This Week: explicit-height region; rows share it equally ----
        root.addView(makeText("This Week", 16f, true).apply {
            setPadding(0, 0, 0, 0)
            (layoutParams as LinearLayout.LayoutParams).apply { height = dp(weekLabelH); setMargins(0, 0, 0, 0) }
            gravity = Gravity.CENTER_VERTICAL
        })
        val weekBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(weekBoxH))
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
            row.addView(BodyMapView(this, muscles).apply { layoutParams = LinearLayout.LayoutParams(dp(30), dp(if (weekBoxH < 260) 34 else 42)) })
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(8), 0, 0, 0) }
            val title = makeText("${if (done) "✓ " else ""}${r.name}", 14f, true)
            if (done) strike(title, true)
            title.maxLines = 1; title.ellipsize = android.text.TextUtils.TruncateAt.END
            (title.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
            info.addView(title)
            val sub = makeText("${DateUtil.dayName(r.weekday)} • ${db.routineExerciseCount(r.id)} exercises • ${musclesLabel(muscles)}", 11f, false, Theme.textSecondary)
            sub.maxLines = 1; sub.ellipsize = android.text.TextUtils.TruncateAt.END
            (sub.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
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
