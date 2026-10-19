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
import java.text.SimpleDateFormat
import java.util.Locale

/** v3.9 Home = the CHOOSER (approved mock r4, frame 1): "What are we
 *  doing today?" over two doors; v3.11 adds a third, Circuits (⚡,
 *  with a NEW badge), and circuit sessions join the one-active-
 *  workout exclusivity + the in-progress bar below. v3.9.3 (approved mock
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
 *     a movie session; v3.12: a circuit session locks both doors the
 *     same way). Creation points enforce it too (PickParts /
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
        db.getSessions().firstOrNull { !it.completed && it.routineName != MovieDeck.ROUTINE_NAME && !CircuitsData.isCircuit(it) }

    private fun movieInProgress(): SessionInfo? =
        db.inProgressSessions().firstOrNull { it.routineName == MovieDeck.ROUTINE_NAME }

    /** v3.11: the circuit in-progress session, if any. Circuit
     *  sessions live in the same table (focus "circuit:<id>",
     *  routineName = the circuit's name) but belong to the Circuits
     *  door — never to the Workout door (see gymInProgress). */
    private fun circuitInProgress(): SessionInfo? =
        db.inProgressSessions().firstOrNull { CircuitsData.isCircuit(it) }

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
        // v3.11: a circuit session gets its own branch (checked BEFORE
        // the gym fallback — a circuit's routineName is the circuit's
        // name, so without this it would read as a gym workout).
        val isCircuit = CircuitsData.isCircuit(session)
        val isMovie = session.routineName == MovieDeck.ROUTINE_NAME
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(when { isCircuit -> Theme.success; isMovie -> movieBarColor(); else -> Theme.primary })
                cornerRadius = dp(14).toFloat()
            }
            elevation = dp(4).toFloat()
            setPadding(dp(13), dp(11), dp(13), dp(11))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, 0) }
            isClickable = true
            setOnClickListener {
                when {
                    isCircuit -> startActivity(Intent(this@MainActivity, CircuitPlayerActivity::class.java).putExtra("sessionId", session.id))
                    isMovie -> startActivity(Intent(this@MainActivity, MovieDeckActivity::class.java))
                    else -> startActivity(firstPendingCardIntent(db, session.id))
                }
            }
        }
        bar.addView(TextView(this).apply {
            text = when { isCircuit -> "⚡"; isMovie -> "🏠"; else -> "🏋️" }; textSize = 20f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(56, 255, 255, 255)) }
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
        })
        val exs = db.getSessionExercises(session.id)
        val titleText: String
        val subText: String
        if (isCircuit) {
            titleText = "Circuit in progress"
            val done = exs.count { db.effectiveStatus(it) == "done" }
            subText = "${session.routineName} · $done of ${exs.size} motions done"
        } else if (isMovie) {
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

    /** v3.11: the Circuits door's text column — doorTextCol's exact
     *  line shapes (name 16.5f bold / desc 12.5f / meta 12f, same
     *  margins) rebuilt locally because doorTextCol has no badge
     *  support: the name line is a horizontal row of the name + the
     *  mock's small green "NEW" pill. */
    private fun circuitTextCol(desc: String, meta: String?): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(12), 0, dp(6), 0) }
        }
        fun line(t: String, sp: Float, bold: Boolean, color: Int, top: Int, maxLines: Int = 2): TextView {
            val tv = makeText(t, sp, bold, color)
            (tv.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(top), 0, 0)
            tv.maxLines = maxLines; tv.ellipsize = android.text.TextUtils.TruncateAt.END
            return tv
        }
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val nameTv = line("Circuits", 16.5f, true, Theme.textPrimary, 0, 1)
        (nameTv.layoutParams as LinearLayout.LayoutParams).apply { width = LinearLayout.LayoutParams.WRAP_CONTENT; setMargins(0, 0, 0, 0) }
        nameRow.addView(nameTv)
        nameRow.addView(TextView(this).apply {
            text = "NEW"; textSize = 10f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = roundedBg(Theme.success, 8)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(6), 0, 0, 0) }
        })
        col.addView(nameRow)
        col.addView(line(desc, 12.5f, false, Theme.textSecondary, 2))
        if (meta != null) col.addView(line(meta, 12f, false, Theme.textTertiary, 3, 1))
        return col
    }

    private fun render() {
        stopBarTicker()
        val simple = SimpleMode.isOn(this)
        val wm = WeekManager.reconcile(this, db)
        val compact = isCompactScreen()
        // v3.18 Simple Mode: no tab scaffold (no bottom nav, no
        // Library tab) — a plain fit root; Settings is reached from
        // the ⚙️ in the header row, History from the quiet foot link.
        val root = if (simple) {
            val r = fitRoot()
            setContentView(r)
            r
        } else tabScaffold("Home")
        val counts = db.partCountsInWeek(wm.weekStart)
        val nonRepeatable = BodyParts.all.filter { !it.repeatable }
        val doneCount = nonRepeatable.count { counts.containsKey(it.id) }

        val gym = gymInProgress()
        val movie = movieInProgress()
        val circuit = circuitInProgress()

        // ---- In-progress bar (very top, above the title) ----
        // Legacy both-active installs: the most recently STARTED
        // session owns the bar; each door below still resumes its own.
        val barSession = listOfNotNull(gym, movie, circuit)
            .maxWithOrNull(compareBy<SessionInfo> { it.startedAt }.thenBy { it.id })
        if (barSession != null) root.addView(progressBar(barSession))

        val title = makeText("What are we doing today?", 21f, true)
        (title.layoutParams as LinearLayout.LayoutParams).apply { height = dp(if (compact) 32 else 38); setMargins(0, dp(2), 0, 0) }
        title.gravity = Gravity.CENTER_VERTICAL
        val sub = caption("Week ${wm.weekNumber} · $doneCount of ${nonRepeatable.size} parts done")
        (sub.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        if (simple) {
            // v3.18: the two header texts stack in a weighted column
            // (their MATCH_PARENT widths stripped to WRAP_CONTENT so
            // they can never push the gear off the row) and the ⚙️
            // sits at the right — the only Settings entry in simple
            // mode, since there is no bottom nav.
            (title.layoutParams as LinearLayout.LayoutParams).width = LinearLayout.LayoutParams.WRAP_CONTENT
            (sub.layoutParams as LinearLayout.LayoutParams).width = LinearLayout.LayoutParams.WRAP_CONTENT
            val headerCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            headerCol.addView(title)
            headerCol.addView(sub)
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            headerRow.addView(headerCol)
            headerRow.addView(TextView(this).apply {
                text = "⚙️"; textSize = 22f; gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                isClickable = true
                setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            })
            root.addView(headerRow)
        } else {
            root.addView(title)
            root.addView(sub)
        }

        // ---- v3.18 Simple Mode hero: "▶ Start again" (idle only) ----
        // Most days need exactly one tap: repeat the last completed
        // gym workout. lastGym uses the SAME predicate as the idle
        // Workout door below; the parts come from session_parts.
        // Tapping the pill re-enters through PickPartsActivity's CSV
        // extra, so session creation still flows through the existing
        // startSessionWithAll (continuity + exclusivity untouched).
        // Omitted entirely when there is no completed gym workout or
        // that workout recorded no parts, and whenever any session
        // (gym / movie / circuit) is in progress.
        if (simple && gym == null && movie == null && circuit == null) {
            val lastGym = db.getSessions().firstOrNull { it.completed && it.routineName != MovieDeck.ROUTINE_NAME && !CircuitsData.isCircuit(it) }
            val heroParts = lastGym?.let { db.getSessionParts(it.id) } ?: emptyList()
            if (lastGym != null && heroParts.isNotEmpty()) {
                val hero = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Theme.primary); cornerRadius = dp(22).toFloat()
                    }
                    elevation = dp(3).toFloat()
                    setPadding(dp(18), dp(16), dp(18), dp(16))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(12), 0, 0) }
                }
                val weekday = try {
                    SimpleDateFormat("EEEE", Locale.US).format(DateUtil.parse(lastGym.date).time).uppercase(Locale.US)
                } catch (e: Exception) { "" }
                hero.addView(makeText("LAST WORKOUT · $weekday", 11.5f, true, 0xB3FFFFFF.toInt()).apply {
                    letterSpacing = 0.08f
                    (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
                })
                hero.addView(makeText(lastGym.routineName, 20f, true, Color.WHITE).apply {
                    (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(6), 0, 0)
                })
                val exCount = db.getSessionExercises(lastGym.id).size
                val mins = (lastGym.elapsedSec + 30) / 60
                val metaText = "$exCount exercises" + if (mins > 0) " · about $mins min" else ""
                hero.addView(makeText(metaText, 13f, false, 0xD9FFFFFF.toInt()).apply {
                    (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(3), 0, 0)
                })
                hero.addView(TextView(this).apply {
                    text = "▶ Start again"; textSize = 16f; setTextColor(Theme.primary); gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(999).toFloat() }
                    setPadding(dp(16), dp(13), dp(16), dp(13))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(14), 0, 0) }
                    isClickable = true
                    setOnClickListener {
                        startActivity(Intent(this@MainActivity, PickPartsActivity::class.java).putExtra(PickPartsActivity.EXTRA_START_PARTS_CSV, heroParts.joinToString(",")))
                    }
                })
                root.addView(hero)
            }
        }

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
        } else if (circuit != null) {
            // v3.12: circuit active — locked (door symmetry fix;
            // mirror of the movie lock above).
            gymDoor = doorCard(Palettes.current.doorGymBg, Palettes.current.doorGymBorder) {
                toast("Finish your current circuit first")
            }
            gymDoor.alpha = 0.62f
            gymDoor.addView(doorIcon("🏋️", IconTints.BLUE))
            gymDoor.addView(doorTextCol("Workout", "Gym day — pick parts, follow the cards.", "Available after you finish this circuit"))
            gymDoor.addView(lockGlyph())
        } else {
            val lastGym = db.getSessions().firstOrNull { it.completed && it.routineName != MovieDeck.ROUTINE_NAME && !CircuitsData.isCircuit(it) }
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
        } else if (circuit != null) {
            // v3.12: circuit active — locked (door symmetry fix;
            // mirror of the gym lock above).
            homeDoor = doorCard(Palettes.current.doorHomeBg, Palettes.current.doorHomeBorder) {
                toast("Finish your current circuit first")
            }
            homeDoor.alpha = 0.62f
            homeDoor.addView(doorIcon("🏠", IconTints.WARM))
            homeDoor.addView(doorTextCol("Movements at Home", "The endless couch deck + home workouts.", "Available after you finish this circuit"))
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

        // ---- Circuits door (⚡, v3.11) ----
        // Active: body + ▶ resume the player directly. Gym/movie
        // active: locked (dim + 🔒 + "finish first" toast), the same
        // one-active-workout rule the other two doors enforce on each
        // other. Idle: body + ▶ open the Circuits home.
        val circuitDoor: LinearLayout
        if (circuit != null) {
            val exs = db.getSessionExercises(circuit.id)
            val done = exs.count { db.effectiveStatus(it) == "done" }
            val openPlayer = {
                startActivity(Intent(this@MainActivity, CircuitPlayerActivity::class.java).putExtra("sessionId", circuit.id))
            }
            circuitDoor = doorCard(Palettes.current.doorCircuitBg, Palettes.current.doorCircuitBorder) { openPlayer() }
            circuitDoor.addView(doorIcon("⚡", IconTints.GREEN))
            circuitDoor.addView(circuitTextCol("Circuit in progress · $done of ${exs.size} motions done", circuit.routineName))
            circuitDoor.addView(playGlyph { openPlayer() })
        } else if (gym != null) {
            circuitDoor = doorCard(Palettes.current.doorCircuitBg, Palettes.current.doorCircuitBorder) {
                toast("Finish your current workout first")
            }
            circuitDoor.alpha = 0.62f
            circuitDoor.addView(doorIcon("⚡", IconTints.GREEN))
            circuitDoor.addView(circuitTextCol("Timed rounds of motions.", "Available after you finish this workout"))
            circuitDoor.addView(lockGlyph())
        } else if (movie != null) {
            circuitDoor = doorCard(Palettes.current.doorCircuitBg, Palettes.current.doorCircuitBorder) {
                toast("Finish your current Movements first")
            }
            circuitDoor.alpha = 0.62f
            circuitDoor.addView(doorIcon("⚡", IconTints.GREEN))
            circuitDoor.addView(circuitTextCol("Timed rounds of motions.", "Available after you finish at home"))
            circuitDoor.addView(lockGlyph())
        } else {
            val lastCircuit = db.getSessions().firstOrNull { it.completed && CircuitsData.isCircuit(it) }
            val circuitMeta = if (lastCircuit != null) "Last: ${lastCircuit.routineName} · ${shortDay(lastCircuit.date)}" else "Not tried yet"
            circuitDoor = doorCard(Palettes.current.doorCircuitBg, Palettes.current.doorCircuitBorder) {
                startActivity(Intent(this, CircuitsHomeActivity::class.java))
            }
            circuitDoor.addView(doorIcon("⚡", IconTints.GREEN))
            circuitDoor.addView(circuitTextCol("Timed rounds of motions · ${CircuitsData.load(this).size} presets", circuitMeta))
            circuitDoor.addView(playGlyph { startActivity(Intent(this, CircuitsHomeActivity::class.java)) })
        }
        (circuitDoor.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(12), 0, 0)
        root.addView(circuitDoor)

        // Nothing else on the chooser — the rest of the screen is air.
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // v3.18 Simple Mode: one quiet History link at the foot (the
        // bottom-nav History tab is gone in simple mode).
        if (simple) {
            val historyLink = makeText("🕘 History", 14.5f, false, Theme.textSecondary)
            historyLink.gravity = Gravity.CENTER
            historyLink.isClickable = true
            historyLink.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
            root.addView(historyLink)
        }

        if (barSession != null) startBarTicker()
    }
}
