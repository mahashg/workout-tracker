package com.mahesh.workouttracker

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** v3.9 Home = the CHOOSER (approved mock r4, frame 1): "What are we
 *  doing today?" over two doors. v3.9.3 (approved mock
 *  v3.9.3-progress-bar-mock-r1) adds three things:
 *  1. THE IN-PROGRESS BAR: while any session runs, a bar at the very
 *     top of Home shows it — icon, label, live elapsed timer — and a
 *     tap goes straight back in (gym: first pending card, hub
 *     fallback; movie: the deck's self-resume). The v3.9.1 three-way
 *     resume dialog is DELETED: the bar (and the active door, body
 *     and ▶ alike) IS the resume affordance. Abandoning a session
 *     still happens from inside the workout.
 *  2. ONE ACTIVE WORKOUT: while a gym session runs, the Movements
 *     door is dimmed + 🔒 and only toasts "finish first" (mirror for
 *     a movie session). Creation points enforce it too (PickParts /
 *     MovieDeck refuse to CREATE the other kind) — resuming the
 *     active session is never blocked.
 *  3. The Movements door icon is 🏠 (was 🎬): "do it at home" at a
 *     glance. The deck inside keeps 🎬.
 *  If BOTH kinds are somehow in progress (a legacy install from
 *  before exclusivity), the bar shows the most recently STARTED one
 *  (startedAt, then id); each door still resumes its own kind.
 *  The week line lives on the chooser sub-title ("Week N · X of 10
 *  parts done"). The v3.4 Health Connect sync row is GONE (sync UI
 *  lives in Settings only; finished workouts still auto-sync). */
class MainActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var created = false

    // --- In-progress bar ticker (TimerChipView's discipline: a 1s
    // Handler loop, started when the bar is built in render(), stopped
    // on onPause and before every re-render, so it can never tick a
    // detached view or outlive Home's visibility). ---
    private val barHandler = Handler(Looper.getMainLooper())
    private var barTimerLabel: TextView? = null
    private var barSessionId: Long = -1
    private var barTicking = false
    private val barTicker = object : Runnable {
        override fun run() {
            refreshBarTimer()
            if (barTicking) barHandler.postDelayed(this, 1000L)
        }
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

    override fun onPause() {
        super.onPause()
        stopBarTicker()
    }

    override fun onDestroy() {
        stopBarTicker()
        super.onDestroy()
    }

    /** The gym in-progress session, if any. A Movie Movements session
     *  is also "in progress" in the sessions table, but it belongs to
     *  the home door — never to the Workout door. */
    private fun gymInProgress(): SessionInfo? =
        db.getSessions().firstOrNull { !it.completed && it.routineName != MovieDeck.ROUTINE_NAME }

    private fun movieInProgress(): SessionInfo? =
        db.inProgressSessions().firstOrNull { it.routineName == MovieDeck.ROUTINE_NAME }

    private fun refreshBarTimer() {
        val label = barTimerLabel ?: return
        val s = db.getSession(barSessionId) ?: return
        label.text = WorkoutTimer.formatClock(WorkoutTimer.liveElapsedSec(this, s))
    }

    private fun startBarTicker() {
        if (barTimerLabel == null || barTicking) return
        barTicking = true
        barHandler.post(barTicker)
    }

    private fun stopBarTicker() {
        barTicking = false
        barHandler.removeCallbacks(barTicker)
        barTimerLabel = null
        barSessionId = -1
    }

    /** The movie bar's warm tone — the ONE documented theme branch
     *  for the bar (mock frame 2): M3 rosewood #7D5260 under Material,
     *  burnt orange #9A3412 under Mist (the mock was drawn in the
     *  Material palette; #9A3412 keeps the same warm-family read on
     *  Mist's blue chrome). Everything else reads palette tokens. */
    private fun movieBarColor(): Int =
        if (AppTheme.name(this) == "mist") Color.parseColor("#9A3412") else Color.parseColor("#7D5260")

    /** The in-progress bar (mock .pbar): filled, 14dp radius, icon in
     *  a translucent white disc, title + sub left, live timer +
     *  "TAP TO CONTINUE →" right. Registers the timer label/session
     *  for the ticker; the caller starts the ticker after render. */
    private fun progressBar(session: SessionInfo): LinearLayout {
        val isMovie = session.routineName == MovieDeck.ROUTINE_NAME
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(if (isMovie) movieBarColor() else Theme.primary)
                cornerRadius = dp(14).toFloat()
            }
            elevation = dp(4).toFloat()
            setPadding(dp(13), dp(11), dp(13), dp(11))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, 0) }
            isClickable = true
            setOnClickListener {
                if (isMovie) startActivity(Intent(this@MainActivity, MovieDeckActivity::class.java))
                else startActivity(firstPendingCardIntent(db, session.id))
            }
        }
        bar.addView(TextView(this).apply {
            text = if (isMovie) "🏠" else "🏋️"; textSize = 20f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(56, 255, 255, 255)) }
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
        })
        val exs = db.getSessionExercises(session.id)
        val titleText: String
        val subText: String
        if (isMovie) {
            titleText = "Movements in progress"
            subText = "${exs.count { db.effectiveStatus(it) == "done" }} movements done so far"
        } else {
            titleText = "Workout in progress"
            val done = exs.count { db.effectiveStatus(it) != "pending" }
            subText = "${session.routineName} · $done of ${exs.size} done"
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(11), 0, dp(8), 0) }
        }
        col.addView(makeText(titleText, 14.5f, true, Color.WHITE).apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0); maxLines = 1
        })
        col.addView(makeText(subText, 11.5f, false, Color.argb(224, 255, 255, 255)).apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(1), 0, 0); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        bar.addView(col)
        val tmCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val timer = makeText(WorkoutTimer.formatClock(WorkoutTimer.liveElapsedSec(this, session)), 17f, true, Color.WHITE)
        timer.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        timer.gravity = Gravity.END
        (timer.layoutParams as LinearLayout.LayoutParams).apply { width = LinearLayout.LayoutParams.WRAP_CONTENT; setMargins(0, 0, 0, 0) }
        tmCol.addView(timer)
        tmCol.addView(makeText("TAP TO CONTINUE →", 10f, true, Color.argb(217, 255, 255, 255)).apply {
            gravity = Gravity.END; letterSpacing = 0.06f
            (layoutParams as LinearLayout.LayoutParams).apply { width = LinearLayout.LayoutParams.WRAP_CONTENT; setMargins(0, dp(1), 0, 0) }
        })
        bar.addView(tmCol)
        barTimerLabel = timer
        barSessionId = session.id
        // Show the workout's OWN clock: the same liveElapsedSec the
        // chip and the deck read. ensureRunning is idempotent — it
        // only stamps a resume point if the session has none.
        WorkoutTimer.ensureRunning(this, db, session)
        return bar
    }

    /** The 🔒 that replaces ▶ on a locked door (mock .lock): no click
     *  of its own — the door's click shows the "finish first" toast. */
    private fun lockGlyph(): TextView = TextView(this).apply {
        text = "🔒"; textSize = 19f; gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun render() {
        stopBarTicker()
        val wm = WeekManager.reconcile(this, db)
        val compact = isCompactScreen()
        val root = tabScaffold("Home")
        val counts = db.partCountsInWeek(wm.weekStart)
        val nonRepeatable = BodyParts.all.filter { !it.repeatable }
        val doneCount = nonRepeatable.count { counts.containsKey(it.id) }

        val gym = gymInProgress()
        val movie = movieInProgress()

        // ---- In-progress bar (very top, above the title) ----
        // Legacy both-active installs: the most recently STARTED
        // session owns the bar; each door below still resumes its own.
        val barSession = listOfNotNull(gym, movie)
            .maxWithOrNull(compareBy<SessionInfo> { it.startedAt }.thenBy { it.id })
        if (barSession != null) root.addView(progressBar(barSession))

        val title = makeText("What are we doing today?", 21f, true)
        (title.layoutParams as LinearLayout.LayoutParams).apply { height = dp(if (compact) 32 else 38); setMargins(0, dp(2), 0, 0) }
        title.gravity = Gravity.CENTER_VERTICAL
        root.addView(title)
        val sub = caption("Week ${wm.weekNumber} · $doneCount of ${nonRepeatable.size} parts done")
        (sub.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        root.addView(sub)

        // ---- Workout door ----
        // Active: body + ▶ resume directly (the bar's tap). Movie
        // active: locked (dim + 🔒 + toast). Idle: the picker.
        val gymDoor: LinearLayout
        if (gym != null) {
            val exs = db.getSessionExercises(gym.id)
            val done = exs.count { db.effectiveStatus(it) != "pending" }
            gymDoor = doorCard(Palettes.current.doorGymBg, Palettes.current.doorGymBorder) {
                startActivity(firstPendingCardIntent(db, gym.id))
            }
            gymDoor.addView(doorIcon("🏋️", IconTints.BLUE))
            gymDoor.addView(doorTextCol("Workout", "Workout in progress · $done of ${exs.size} done", gym.routineName))
            gymDoor.addView(playGlyph { startActivity(firstPendingCardIntent(db, gym.id)) })
        } else if (movie != null) {
            val lastGymNote = "Available after you finish at home"
            gymDoor = doorCard(Palettes.current.doorGymBg, Palettes.current.doorGymBorder) {
                toast("Finish your current Movements first")
            }
            gymDoor.alpha = 0.62f
            gymDoor.addView(doorIcon("🏋️", IconTints.BLUE))
            gymDoor.addView(doorTextCol("Workout", "Gym day — pick parts, follow the cards.", lastGymNote))
            gymDoor.addView(lockGlyph())
        } else {
            val lastGym = db.getSessions().firstOrNull { it.completed && it.routineName != MovieDeck.ROUTINE_NAME }
            val gymMeta = if (lastGym != null) "Last: ${lastGym.routineName} · ${shortDay(lastGym.date)}" else "No workouts yet"
            gymDoor = doorCard(Palettes.current.doorGymBg, Palettes.current.doorGymBorder) {
                startActivity(Intent(this, PickPartsActivity::class.java))
            }
            gymDoor.addView(doorIcon("🏋️", IconTints.BLUE))
            gymDoor.addView(doorTextCol("Workout", "Gym day — pick parts, follow the cards.", gymMeta))
            gymDoor.addView(playGlyph { startActivity(Intent(this, PickPartsActivity::class.java)) })
        }
        (gymDoor.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(14), 0, 0)
        root.addView(gymDoor)

        // ---- Movements at Home door (🏠 since v3.9.3) ----
        // Active: body + ▶ resume the deck directly (the bar's tap).
        // Gym active: locked (mirror of the gym door). Idle: body
        // opens the At Home page, ▶ starts the deck (v3.9).
        val homeDoor: LinearLayout
        if (movie != null) {
            val doneSoFar = db.getSessionExercises(movie.id).count { db.effectiveStatus(it) == "done" }
            homeDoor = doorCard(Palettes.current.doorHomeBg, Palettes.current.doorHomeBorder) {
                startActivity(Intent(this, MovieDeckActivity::class.java))
            }
            homeDoor.addView(doorIcon("🏠", IconTints.WARM))
            homeDoor.addView(doorTextCol("Movements at Home", "In progress — pick up where you left off.", "$doneSoFar movements done so far"))
            homeDoor.addView(playGlyph { startActivity(Intent(this, MovieDeckActivity::class.java)) })
        } else if (gym != null) {
            homeDoor = doorCard(Palettes.current.doorHomeBg, Palettes.current.doorHomeBorder) {
                toast("Finish your current workout first")
            }
            homeDoor.alpha = 0.62f
            homeDoor.addView(doorIcon("🏠", IconTints.WARM))
            homeDoor.addView(doorTextCol("Movements at Home", "The endless couch deck + home workouts.", "Available after you finish this workout"))
            homeDoor.addView(lockGlyph())
        } else {
            val lastMovie = db.getSessions().firstOrNull { it.completed && it.routineName == MovieDeck.ROUTINE_NAME }
            val homeMeta = if (lastMovie != null) {
                val n = db.getSessionExercises(lastMovie.id).count { db.effectiveStatus(it) == "done" }
                "Last: $n movements · ${shortDay(lastMovie.date)}"
            } else "Not tried yet"
            homeDoor = doorCard(Palettes.current.doorHomeBg, Palettes.current.doorHomeBorder) {
                startActivity(Intent(this, AtHomeActivity::class.java))
            }
            homeDoor.addView(doorIcon("🏠", IconTints.WARM))
            homeDoor.addView(doorTextCol("Movements at Home", "The endless couch deck + home workouts.", homeMeta))
            homeDoor.addView(playGlyph {
                // The deck activity itself resumes an in-progress session.
                startActivity(Intent(this, MovieDeckActivity::class.java))
            })
        }
        (homeDoor.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(12), 0, 0)
        root.addView(homeDoor)

        // Nothing else on the chooser — the rest of the screen is air.
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        if (barSession != null) startBarTicker()
    }
}
