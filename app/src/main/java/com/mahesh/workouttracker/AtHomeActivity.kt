package com.mahesh.workouttracker

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** v3.9 Movements at Home page (mock r4 frame 3): the Movie
 *  Movements door + the Recent at Home list. The mock's Home Workout
 *  door is DELIBERATELY NOT built in v3.9 — that mode arrives with
 *  the home-catalog build; this page ships Movie door + Recent only
 *  rather than fake a door to a mode that doesn't exist.
 *  The deck activity itself handles start vs resume, so the door and
 *  its ▶ both simply open it. Recent rows come from the sessions
 *  table (routineName = "Movie Movements" today; home-workout
 *  sessions will join the same list when that mode lands). */
class AtHomeActivity : AppCompatActivity() {
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

    private fun render() {
        val root = tabScaffold("Home")
        val compact = isCompactScreen()
        val title = makeText("🏠 Movements at Home", 21f, true)
        (title.layoutParams as LinearLayout.LayoutParams).apply { height = dp(if (compact) 32 else 38); setMargins(0, dp(2), 0, 0) }
        title.gravity = Gravity.CENTER_VERTICAL
        root.addView(title)
        val sub = caption("No gym, no equipment.")
        (sub.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        root.addView(sub)

        // ---- Movie Movements door ----
        val movie = db.inProgressSessions().firstOrNull { it.routineName == MovieDeck.ROUTINE_NAME }
        val deckSize = JsonSeed.deckEntries(this).size
        val desc: String
        val meta: String
        if (movie != null) {
            desc = "In progress — pick up where you left off."
            meta = "Deck: $deckSize movements"
        } else {
            desc = "Endless deck · 30–60s each · until you stop."
            val lastMovie = db.getSessions().firstOrNull { it.completed && it.routineName == MovieDeck.ROUTINE_NAME }
            meta = if (lastMovie != null) {
                val n = db.getSessionExercises(lastMovie.id).count { db.effectiveStatus(it) == "done" }
                "Deck: $deckSize movements · Last: $n done · ${shortDay(lastMovie.date)}"
            } else "Deck: $deckSize movements"
        }
        // v3.9.3 exclusivity: while a GYM session runs and no deck
        // session is in progress, this door is locked — dimmed, a 🔒
        // where ▶ sits, and a tap only toasts "finish first" (the
        // deck must never be CREATED during a gym workout; creation
        // is refused in MovieDeckActivity too). An in-progress deck
        // session is never locked — resuming it is always allowed.
        val gymActive = db.inProgressSessions().any { it.routineName != MovieDeck.ROUTINE_NAME }
        if (movie == null && gymActive) {
            val door = doorCard(Palettes.current.doorHomeBg, Palettes.current.doorHomeBorder) {
                Toast.makeText(this, "Finish your current workout first", Toast.LENGTH_SHORT).show()
            }
            door.alpha = 0.62f
            (door.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(14), 0, 0)
            door.addView(doorIcon("🎬", IconTints.WARM))
            door.addView(doorTextCol("Movie Movements", desc, "Available after you finish this workout"))
            door.addView(TextView(this).apply {
                text = "🔒"; textSize = 19f; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            })
            root.addView(door)
        } else {
            val door = doorCard(Palettes.current.doorHomeBg, Palettes.current.doorHomeBorder) {
                startActivity(Intent(this, MovieDeckActivity::class.java))
            }
            (door.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(14), 0, 0)
            door.addView(doorIcon("🎬", IconTints.WARM))
            door.addView(doorTextCol("Movie Movements", desc, meta))
            door.addView(playGlyph { startActivity(Intent(this, MovieDeckActivity::class.java)) })
            root.addView(door)
        }

        // ---- Recent at Home ----
        val label = overline("Recent at Home")
        (label.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(16), 0, dp(4))
        root.addView(label)
        val homeSessions = db.getSessions().filter { it.routineName == MovieDeck.ROUTINE_NAME }.take(3)
        if (homeSessions.isEmpty()) {
            root.addView(caption("No home sessions yet — finished decks will show here."))
        }
        for (s in homeSessions) {
            val doneCount = db.getSessionExercises(s.id).count { db.effectiveStatus(it) == "done" }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = cardDrawable(tappable = true)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(3), 0, dp(3)) }
                isClickable = true
                setOnClickListener {
                    if (s.completed) startActivity(Intent(this@AtHomeActivity, SessionActivity::class.java).putExtra("sessionId", s.id))
                    else startActivity(Intent(this@AtHomeActivity, MovieDeckActivity::class.java))
                }
            }
            row.addView(settingsIcon("🎬", IconTints.WARM, 38, 19f))
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(10), 0, dp(8), 0) }
            }
            val nm = makeText("Movie Movements", 14f, true)
            (nm.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
            col.addView(nm)
            val dt = makeText(DateUtil.display(s.date), 11.5f, false, Theme.textSecondary)
            (dt.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(1), 0, 0)
            col.addView(dt)
            row.addView(col)
            if (s.completed) row.addView(pillView("✓ $doneCount done · ${shortDay(s.date)}", hexOf(Theme.successBg), Theme.success, 11f))
            else row.addView(pillView("● In progress · ${shortDay(s.date)}", hexOf(Theme.warnBg), Theme.warn, 11f))
            root.addView(row)
        }
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
    }
}
