package com.mahesh.workouttracker

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** v3 Home: the Week N scoreboard over body parts (the fixed 5-day routine
 *  assignment is retired). Start Workout -> pick today's parts -> assembled
 *  flat list. In-progress resume behavior is unchanged.
 *  Budget (compact): title 40 + hero ~86 + grid 6x(54+5) + start 50 + gaps
 *  ≈ 545 <= ~546 available at a 640dp viewport. */
class MainActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var created = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = DbHelper(this)
        Seed.ensureSeeded(db)
        Seed.applyV2IfNeeded(this, db)
        JsonSeed.applyV3IfNeeded(this, db)
        created = true
        render()
    }

    override fun onResume() {
        super.onResume()
        if (created) render()
    }

    private fun render() {
        val wm = WeekManager.reconcile(this, db)
        val compact = isCompactScreen()
        val root = tabScaffold("Home")
        val lastTrained = db.lastTrainedDates()
        val counts = db.partCountsInWeek(wm.weekStart)
        val nonRepeatable = BodyParts.all.filter { !it.repeatable }
        val doneCount = nonRepeatable.count { counts.containsKey(it.id) }

        root.addView(screenTitle("Home").apply {
            (layoutParams as LinearLayout.LayoutParams).apply { height = dp(40); setMargins(0, 0, 0, 0) }
            gravity = Gravity.CENTER_VERTICAL
        })
        // Week hero (blue gradient, same family as v2.6): Week N + range + bar.
        val hero = heroCard()
        hero.setPadding(dp(16), dp(10), dp(16), dp(10))
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(makeText("Week ${wm.weekNumber}", if (compact) 21f else 23f, true, Color.WHITE))
        titleRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        titleRow.addView(makeText(weekRangeLabel(wm.weekStart), 12.5f, false, 0xD9FFFFFF.toInt()))
        hero.addView(titleRow)
        hero.addView(segmentedWeekProgress(nonRepeatable.map { counts.containsKey(it.id) }).apply {
            (layoutParams as LinearLayout.LayoutParams).apply { height = dp(10); setMargins(0, dp(8), 0, 0) }
        })
        hero.addView(makeText("$doneCount of ${nonRepeatable.size} body parts done this week", 12.5f, false, 0xD9FFFFFF.toInt()).apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(6), 0, 0)
        })
        root.addView(hero)

        // Body-part grid: 2 columns x 6 rows, one page, no scrolling.
        val cellH = if (compact) 54 else 62
        for (rowParts in BodyParts.all.chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (part in rowParts) {
                row.addView(partCell(part, lastTrained[part.id], counts[part.id] ?: 0), LinearLayout.LayoutParams(0, dp(cellH), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
            if (rowParts.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, dp(cellH), 1f))
            root.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(cellH)).apply { setMargins(0, dp(2), 0, dp(2)) })
        }

        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val inProgress = db.getSessions().firstOrNull { !it.completed }
        val startBtn = makeButton(if (inProgress != null) "Resume Workout" else "Start Workout") { startWorkout() }
        tightButton(startBtn, if (compact) 50 else 54, 14, primary = true)
        root.addView(startBtn)
    }

    private fun partCell(part: BodyParts.Part, last: String?, countThisWeek: Int): View {
        val done = countThisWeek > 0
        val days = last?.let { daysSince(it) }
        val due = !part.repeatable && !done && (last == null || (days != null && days >= 7))
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(tappable = true)
            setPadding(dp(10), dp(6), dp(10), dp(5))
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, BodyPartDetailActivity::class.java).putExtra("bodyPart", part.id))
            }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val nameTv = makeText(part.name, 14.5f, true)
        nameTv.maxLines = 1
        top.addView(nameTv)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val (pillText, pillBg, pillInk) = when {
            part.repeatable -> Triple("${countThisWeek}× this week", "#EDF2F7", Theme.textSecondary)
            done -> Triple("✓ Done", "#DCFCE7", Theme.success)
            due -> Triple("Due", "#FEF3C7", Theme.warn)
            else -> Triple("Pending", "#E2E8F0", Theme.pendingFg)
        }
        top.addView(pillView(pillText, pillBg, pillInk))
        cell.addView(top)
        val lastLine = when {
            last == null -> "Not trained yet"
            days != null && days == 0L -> "Last · today"
            days != null && days == 1L -> "Last · yesterday"
            days != null -> "Last · ${DateUtil.display(last)} · ${days}d ago"
            else -> "Last · ${DateUtil.display(last)}"
        }
        val lastTv = makeText(lastLine, 11f, false, Theme.textSecondary)
        lastTv.maxLines = 1
        lastTv.setPadding(0, dp(2), 0, 0)
        cell.addView(lastTv)
        return cell
    }

    private fun startWorkout() {
        val inProgress = db.getSessions().firstOrNull { !it.completed }
        if (inProgress == null) {
            startActivity(Intent(this, PickPartsActivity::class.java))
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Workout in progress")
            .setMessage("${inProgress.routineName} is in progress. Resume it, view its cards, or discard it and start a new workout?")
            .setPositiveButton("Resume workout") { _, _ ->
                startActivity(Intent(this, WorkoutSectionsActivity::class.java).putExtra("sessionId", inProgress.id))
            }
            .setNeutralButton("View cards") { _, _ ->
                // Cards open at the first section that still has pending work.
                val exs = db.getSessionExercises(inProgress.id)
                val section = listOf("Warmup", "Main", "Stretch").firstOrNull { t ->
                    exs.any { it.type.equals(t, true) && db.effectiveStatus(it) == "pending" }
                } ?: "Main"
                startActivity(Intent(this, CardSessionActivity::class.java).putExtra("sessionId", inProgress.id).putExtra("sectionType", section))
            }
            .setNegativeButton("Discard & start new") { _, _ ->
                db.deleteSession(inProgress.id)
                startActivity(Intent(this, PickPartsActivity::class.java))
            }
            .show()
    }

    private fun weekRangeLabel(weekStart: String): String {
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val start = fmt.parse(weekStart) ?: return ""
            val cal = Calendar.getInstance().apply { time = start; add(Calendar.DAY_OF_YEAR, 6) }
            val label = SimpleDateFormat("EEE MMM d", Locale.US)
            "${label.format(start)} – ${label.format(cal.time)}"
        } catch (e: Exception) { "" }
    }
}
