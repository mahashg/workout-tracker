package com.mahesh.workouttracker

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** v3 Home: the Week N scoreboard over body parts (the fixed 5-day routine
 *  assignment is retired). Start Workout -> pick today's parts -> assembled
 *  flat list. In-progress resume behavior is unchanged.
 *  Budget: since v3.8.1 the body-part grid is the page's single flex
 *  region (rows share its height), so the fixed blocks — title 32/40 +
 *  hero ~65/76 + sync 52 + start 54/60 + the At Home card ~97/160 —
 *  always fit; the grid compresses on short screens and breathes on
 *  tall ones. Nothing can be pushed off-screen by construction. */
class MainActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var created = false

    // v3.4: Home Health Connect sync button state.
    private var hcSyncRunning = false
    private val hcPermLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.contains(HealthSync.WRITE_PERMISSION)) doHomeSync()
        else Toast.makeText(this, "Health Connect permission not granted", Toast.LENGTH_SHORT).show()
    }

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

    /** The gym in-progress session, if any. v3.8.1: a Movie Movements
     *  session is also "in progress" in the sessions table, but it is
     *  resumed from the At Home card — never from Start Workout. */
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

        root.addView(screenTitle("Home").apply {
            (layoutParams as LinearLayout.LayoutParams).apply { height = dp(if (compact) 32 else 40); setMargins(0, 0, 0, 0) }
            gravity = Gravity.CENTER_VERTICAL
        })
        // Week hero (blue gradient, same family as v2.6): Week N + range + bar.
        val hero = heroCard()
        hero.setPadding(dp(16), dp(if (compact) 5 else 8), dp(16), dp(if (compact) 3 else 6))
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(makeText("Week ${wm.weekNumber}", if (compact) 21f else 23f, true, Color.WHITE))
        titleRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        titleRow.addView(makeText(weekRangeLabel(wm.weekStart), 12.5f, false, 0xD9FFFFFF.toInt()))
        hero.addView(titleRow)
        hero.addView(segmentedWeekProgress(nonRepeatable.map { counts.containsKey(it.id) }).apply {
            (layoutParams as LinearLayout.LayoutParams).apply { height = dp(10); setMargins(0, dp(if (compact) 4 else 6), 0, 0) }
        })
        hero.addView(makeText("$doneCount of ${nonRepeatable.size} body parts done this week", 12.5f, false, 0xD9FFFFFF.toInt()).apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(if (compact) 3 else 4), 0, 0)
        })
        root.addView(hero)

        // Body-part grid: 2 columns x 6 rows, one page, no scrolling.
        // v3.8.1: the grid is now the page's single FLEX region (rows
        // share its height equally) — it funds the At Home card below by
        // compressing on short screens, so by construction the fixed
        // blocks under it (sync / Start / At Home) can never be pushed
        // off-screen; on taller screens the cells simply get airier.
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

        // v3.4 (addition A): slim Health Connect sync row above Start.
        val hcBtn = makeSecondaryButton(if (hcSyncRunning) "Syncing…" else "Sync to Health Connect") { onHcSyncTap() }
        tightButton(hcBtn, 48, 14, primary = false)
        hcBtn.isEnabled = !hcSyncRunning
        (hcBtn.layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(4), 0, 0) }
        root.addView(hcBtn)
        val inProgress = gymInProgress()
        val startBtn = makeButton(if (inProgress != null) "Resume Workout" else "Start Workout") { startWorkout() }
        tightButton(startBtn, if (compact) 48 else 54, 14, primary = true)
        (startBtn.layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, dp(6), 0, 0) }
        root.addView(startBtn)

        // v3.8.1 (approved mock screen 1): the warm At Home card under
        // Start Workout. This build carries ONE row — Movie Movements;
        // the Home Workout row joins the same card in the catalog build.
        root.addView(atHomeCard(compact))
    }

    /** The At Home card: #FFF7E8 fill, 1.5dp #F2C94C border, 16dp
     *  corners (mock screen 1). The whole Movie row is tappable (the
     *  Start/Resume pill inside is its visual affordance); with a movie
     *  session in progress the pill reads "Resume" and the deck picks
     *  the session back up. */
    private fun atHomeCard(compact: Boolean): View {
        val movie = db.inProgressSessions().firstOrNull { it.routineName == MovieDeck.ROUTINE_NAME }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFF7E8")); cornerRadius = dp(16).toFloat()
                setStroke((1.5f * resources.displayMetrics.density).toInt(), Color.parseColor("#F2C94C"))
            }
            setPadding(dp(13), dp(if (compact) 9 else 12), dp(13), dp(if (compact) 9 else 12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(10), 0, 0) }
        }
        card.addView(makeText("🏠 At Home", 15.5f, true).apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        })
        if (!compact) {
            card.addView(makeText("No gym, no equipment — everyday movements for the living room.", 12.5f, false, Color.parseColor("#7A6414")).apply {
                (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(2), 0, 0)
            })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFFDF6")); cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#F0DFAE"))
            }
            setPadding(dp(12), dp(if (compact) 8 else 10), dp(12), dp(if (compact) 8 else 10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(if (compact) 7 else 10), 0, 0) }
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, MovieDeckActivity::class.java))
            }
        }
        row.addView(makeText("🎬", 22f, false).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(10), 0, dp(8), 0) }
        }
        col.addView(makeText("Movie Movements", 14.5f, true).apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        })
        col.addView(makeText(
            if (movie != null) "In progress — pick up where you left off."
            else "Endless: one movement at a time, random order, until you stop.",
            12f, false, Color.parseColor("#7A6414")).apply {
            maxLines = if (compact) 1 else 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(1), 0, 0)
        })
        row.addView(col)
        row.addView(TextView(this).apply {
            text = if (movie != null) "Resume" else "Start"
            textSize = 13f; setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply { setColor(Theme.primary); cornerRadius = dp(9).toFloat() }
            setPadding(dp(13), dp(8), dp(13), dp(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        card.addView(row)
        return card
    }

    /** Home sync button flow (v3.4 addition A): unavailable -> toast;
     *  toggle off -> ask first (never silently override his off switch);
     *  permission missing -> request it; otherwise upsert all completed
     *  workouts and toast the count. Runs off the UI thread; the button
     *  is disabled while a sync is in flight. */
    private fun onHcSyncTap() {
        if (hcSyncRunning) return
        when (HealthSync.sdkStatus(this)) {
            HealthConnectClient.SDK_UNAVAILABLE ->
                Toast.makeText(this, "Health Connect isn't available on this device", Toast.LENGTH_SHORT).show()
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                Toast.makeText(this, "Health Connect needs an update on this device", Toast.LENGTH_SHORT).show()
            else -> {
                if (!HealthSync.isEnabled(this)) {
                    AlertDialog.Builder(this)
                        .setMessage("Health Connect sync is off.")
                        .setPositiveButton("Turn on") { _, _ -> HealthSync.setEnabled(this, true); continueHcFlow() }
                        .setNegativeButton("Not now", null)
                        .show()
                } else continueHcFlow()
            }
        }
    }

    private fun continueHcFlow() {
        HealthSync.checkWritePermission(this) { granted ->
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (granted) doHomeSync() else hcPermLauncher.launch(setOf(HealthSync.WRITE_PERMISSION))
            }
        }
    }

    private fun doHomeSync() {
        hcSyncRunning = true; render()
        HealthSync.syncAllCompleted(this, db) { written, total ->
            runOnUiThread {
                hcSyncRunning = false
                if (!isFinishing) {
                    Toast.makeText(this, when {
                        total == 0 -> "No completed workouts to sync yet"
                        written == 0 -> "Couldn't sync — check Health Connect permission"
                        else -> "Synced $written workouts to Health Connect"
                    }, Toast.LENGTH_LONG).show()
                    render()
                }
            }
        }
    }

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
        val inProgress = gymInProgress()
        if (inProgress == null) {
            startActivity(Intent(this, PickPartsActivity::class.java))
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Workout in progress")
            .setMessage("${inProgress.routineName} is in progress. Resume it, view its cards, or discard it and start a new workout?")
            .setPositiveButton("Resume workout") { _, _ ->
                // v3.7: resume opens the hub (the sections screen left
                // the start/resume flow with the assemble page).
                startActivity(Intent(this, WorkoutHubActivity::class.java).putExtra("sessionId", inProgress.id))
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
