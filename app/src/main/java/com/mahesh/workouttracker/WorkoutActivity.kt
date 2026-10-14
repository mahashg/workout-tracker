package com.mahesh.workouttracker

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** v3.9 Workout page (mock r4 frame 2): the pre-v3.9 Home content —
 *  week hero, body-part scoreboard grid, Start — moved behind the
 *  chooser's Workout door and re-skinned icon-first. Start is a
 *  door-row with a plain ▶ (no text bar); its in-progress tap runs
 *  the SAME resume dialog Home's Resume button ran (Resume workout /
 *  View all exercises / Discard & start new, semantics unchanged).
 *  Beginner Mode gets a slim row with the same pref Settings uses —
 *  one setting, two switches. No sync UI here (Settings only).
 *  Layout keeps Home's pattern: the parts grid is the single
 *  flex region, so the page fits without scrolling by construction. */
class WorkoutActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var created = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = DbHelper(this)
        Seed.applyV2IfNeeded(this, db)
        JsonSeed.applyV3IfNeeded(this, db)
        created = true
        render()
    }

    override fun onResume() {
        super.onResume()
        if (created) render()
    }

    private fun gymInProgress(): SessionInfo? =
        db.getSessions().firstOrNull { !it.completed && it.routineName != MovieDeck.ROUTINE_NAME }

    private fun render() {
        val wm = WeekManager.reconcile(this, db)
        val compact = isCompactScreen()
        val root = tabScaffold("Home")
        val lastTrained = db.lastTrainedDates()
        val counts = db.partCountsInWeek(wm.weekStart)
        val nonRepeatable = BodyParts.all.filter { !it.repeatable }
        val doneCount = nonRepeatable.count { counts.containsKey(it.id) }

        val title = makeText("🏋️ Workout", 21f, true)
        (title.layoutParams as LinearLayout.LayoutParams).apply { height = dp(if (compact) 32 else 38); setMargins(0, dp(2), 0, 0) }
        title.gravity = Gravity.CENTER_VERTICAL
        root.addView(title)

        // Week hero (mock frame 2): WEEK N overline, the count, the
        // trained-parts line, the 10-segment bar.
        val hero = heroCard()
        hero.setPadding(dp(16), dp(if (compact) 6 else 9), dp(16), dp(if (compact) 6 else 9))
        val overRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val over = makeText("WEEK ${wm.weekNumber}", 11f, true, 0xD9FFFFFF.toInt())
        over.letterSpacing = 0.1f
        (over.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; setMargins(0, 0, 0, 0) }
        overRow.addView(over)
        val range = makeText(weekRangeLabel(wm.weekStart), 12f, false, 0xD9FFFFFF.toInt())
        (range.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        overRow.addView(range)
        hero.addView(overRow)
        val countTv = makeText("$doneCount of ${nonRepeatable.size} body parts done", if (compact) 18f else 20f, true, Color.WHITE)
        (countTv.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(2), 0, 0)
        hero.addView(countTv)
        val trainedNames = BodyParts.all.filter { counts.containsKey(it.id) }.map { it.name }
        val trainedTv = makeText(
            if (trainedNames.isEmpty()) "Nothing trained yet this week" else trainedNames.joinToString(" · ") + " trained",
            12.5f, false, 0xD9FFFFFF.toInt())
        (trainedTv.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(1), 0, 0)
        trainedTv.maxLines = 1; trainedTv.ellipsize = android.text.TextUtils.TruncateAt.END
        hero.addView(trainedTv)
        hero.addView(segmentedWeekProgress(nonRepeatable.map { counts.containsKey(it.id) }).apply {
            (layoutParams as LinearLayout.LayoutParams).apply { height = dp(10); setMargins(0, dp(if (compact) 5 else 7), 0, 0) }
        })
        root.addView(hero)

        // BODY PARTS: the pre-v3.9 Home grid, unchanged presentation
        // (last-trained + Done/Due/Pending pills, Core "× this week").
        val label = overline("Body parts")
        (label.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(4), 0, dp(2))
        root.addView(label)
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        for (rowParts in BodyParts.all.chunked(2)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(0, dp(1), 0, dp(1)) }
            }
            for (part in rowParts) {
                row.addView(partCell(part, lastTrained[part.id], counts[part.id] ?: 0), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
            if (rowParts.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            grid.addView(row)
        }
        root.addView(grid)

        // Start Workout door-row: plain ▶ starts; in progress the desc
        // shows it and the tap runs the existing resume dialog.
        val inProgress = gymInProgress()
        val startDesc = if (inProgress != null) {
            val exs = db.getSessionExercises(inProgress.id)
            val done = exs.count { db.effectiveStatus(it) != "pending" }
            "Workout in progress · $done of ${exs.size} done"
        } else "Pick parts — first card opens directly."
        val startRow = whiteDoorRow { startWorkout() }
        (startRow.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(10), 0, 0)
        startRow.addView(doorIcon("🏋️", IconTints.BLUE, 48, 25f))
        startRow.addView(doorTextCol("Start Workout", startDesc, null))
        startRow.addView(playGlyph { startWorkout() })
        root.addView(startRow)

        // Beginner Mode slim row: the Settings pref, on this page too.
        val begRow = whiteDoorRow { }
        begRow.isClickable = false
        (begRow.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(8), 0, 0)
        begRow.setPadding(dp(14), dp(8), dp(14), dp(8))
        begRow.addView(settingsIcon("🐣", IconTints.YOLK))
        val begCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(12), 0, dp(8), 0) }
        }
        val begName = makeText("Beginner Mode", 14.5f, true)
        (begName.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        begCol.addView(begName)
        val begDesc = makeText("Posture checks on every exercise", 12f, false, Theme.textSecondary)
        (begDesc.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(1), 0, 0)
        begCol.addView(begDesc)
        begRow.addView(begCol)
        begRow.addView(toggleSwitch(Beginner.beginnerMode(this)) { on ->
            Beginner.setBeginnerMode(this, on); render()
        })
        root.addView(begRow)
    }

    /** The part cell exactly as Home rendered it pre-v3.9 (name +
     *  state pill + last-trained line); tap opens the part page. */
    private fun partCell(part: BodyParts.Part, last: String?, countThisWeek: Int): View {
        val done = countThisWeek > 0
        val days = last?.let { daysSince(it) }
        val due = !part.repeatable && !done && (last == null || (days != null && days >= 7))
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(tappable = true)
            setPadding(dp(10), dp(4), dp(10), dp(3))
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@WorkoutActivity, BodyPartDetailActivity::class.java).putExtra("bodyPart", part.id))
            }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val nameTv = makeText(part.name, 14.5f, true)
        nameTv.maxLines = 1
        top.addView(nameTv)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val (pillText, pillBg, pillInk) = when {
            part.repeatable -> Triple("${countThisWeek}× this week", hexOf(Theme.surfaceVariant), Theme.textSecondary)
            done -> Triple("✓ Done", hexOf(Theme.successBg), Theme.success)
            due -> Triple("Due", hexOf(Theme.warnBg), Theme.warn)
            else -> Triple("Pending", hexOf(Theme.pendingBg), Theme.pendingFg)
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

    /** The pre-v3.9 Home Start/Resume behavior, unchanged: no session
     *  in progress -> the picker; in progress -> the three-way dialog
     *  (Resume lands on the first pending card, v3.8.2). */
    private fun startWorkout() {
        val inProgress = gymInProgress()
        if (inProgress == null) {
            startActivity(Intent(this, PickPartsActivity::class.java))
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Workout in progress")
            .setMessage("${inProgress.routineName} is in progress. Resume it, view all its exercises, or discard it and start a new workout?")
            .setPositiveButton("Resume workout") { _, _ ->
                startActivity(firstPendingCardIntent(db, inProgress.id))
            }
            .setNeutralButton("View all exercises") { _, _ ->
                startActivity(Intent(this, WorkoutHubActivity::class.java).putExtra("sessionId", inProgress.id))
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
