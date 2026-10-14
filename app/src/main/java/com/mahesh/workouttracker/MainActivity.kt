package com.mahesh.workouttracker

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/** v3.9 Home = the CHOOSER (approved mock r4, frame 1): "What are we
 *  doing today?" over two doors and nothing else. A door's body opens
 *  its page (WorkoutActivity / AtHomeActivity); its plain ▶ starts
 *  that side's flow directly — PickPartsActivity for the gym, the
 *  deck for Movements — and becomes Resume-in-place while a session
 *  is in progress (the desc says so). The week scoreboard, parts
 *  grid and Start button moved to the Workout page; the v3.4 Health
 *  Connect sync row is GONE (sync UI lives in Settings only, per
 *  Mahesh's round-2 note; finished workouts still auto-sync). */
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

    /** The gym in-progress session, if any. A Movie Movements session
     *  is also "in progress" in the sessions table, but it belongs to
     *  the home door — never to the Workout door. */
    private fun gymInProgress(): SessionInfo? =
        db.getSessions().firstOrNull { !it.completed && it.routineName != MovieDeck.ROUTINE_NAME }

    private fun render() {
        val wm = WeekManager.reconcile(this, db)
        val compact = isCompactScreen()
        val root = tabScaffold("Home")
        val counts = db.partCountsInWeek(wm.weekStart)
        val nonRepeatable = BodyParts.all.filter { !it.repeatable }
        val doneCount = nonRepeatable.count { counts.containsKey(it.id) }

        val title = makeText("What are we doing today?", 21f, true)
        (title.layoutParams as LinearLayout.LayoutParams).apply { height = dp(if (compact) 32 else 38); setMargins(0, dp(2), 0, 0) }
        title.gravity = Gravity.CENTER_VERTICAL
        root.addView(title)
        val sub = caption("Week ${wm.weekNumber} · $doneCount of ${nonRepeatable.size} parts done")
        (sub.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        root.addView(sub)

        // ---- Workout door ----
        val gym = gymInProgress()
        val gymDesc: String
        val gymMeta: String
        if (gym != null) {
            val exs = db.getSessionExercises(gym.id)
            val done = exs.count { db.effectiveStatus(it) != "pending" }
            gymDesc = "Workout in progress · $done of ${exs.size} done"
            gymMeta = gym.routineName
        } else {
            gymDesc = "Gym day — pick parts, follow the cards."
            val lastGym = db.getSessions().firstOrNull { it.completed && it.routineName != MovieDeck.ROUTINE_NAME }
            gymMeta = if (lastGym != null) "Last: ${lastGym.routineName} · ${shortDay(lastGym.date)}" else "No workouts yet"
        }
        val gymDoor = doorCard(Palettes.current.doorGymBg, Palettes.current.doorGymBorder) {
            startActivity(Intent(this, WorkoutActivity::class.java))
        }
        (gymDoor.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(14), 0, 0)
        gymDoor.addView(doorIcon("🏋️", IconTints.BLUE))
        gymDoor.addView(doorTextCol("Workout", gymDesc, gymMeta))
        gymDoor.addView(playGlyph {
            // ▶ = the start flow directly: fresh -> the picker;
            // in progress -> resume on the first pending card
            // (v3.8.2 semantics, the same intent Home resume used).
            if (gym != null) startActivity(firstPendingCardIntent(db, gym.id))
            else startActivity(Intent(this, PickPartsActivity::class.java))
        })
        root.addView(gymDoor)

        // ---- Movements at Home door ----
        val movie = db.inProgressSessions().firstOrNull { it.routineName == MovieDeck.ROUTINE_NAME }
        val homeDesc: String
        val homeMeta: String
        if (movie != null) {
            homeDesc = "In progress — pick up where you left off."
            val doneSoFar = db.getSessionExercises(movie.id).count { db.effectiveStatus(it) == "done" }
            homeMeta = "$doneSoFar movements done so far"
        } else {
            homeDesc = "The endless couch deck + home workouts."
            val lastMovie = db.getSessions().firstOrNull { it.completed && it.routineName == MovieDeck.ROUTINE_NAME }
            homeMeta = if (lastMovie != null) {
                val n = db.getSessionExercises(lastMovie.id).count { db.effectiveStatus(it) == "done" }
                "Last: $n movements · ${shortDay(lastMovie.date)}"
            } else "Not tried yet"
        }
        val homeDoor = doorCard(Palettes.current.doorHomeBg, Palettes.current.doorHomeBorder) {
            startActivity(Intent(this, AtHomeActivity::class.java))
        }
        (homeDoor.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(12), 0, 0)
        homeDoor.addView(doorIcon("🎬", IconTints.WARM))
        homeDoor.addView(doorTextCol("Movements at Home", homeDesc, homeMeta))
        homeDoor.addView(playGlyph {
            // The deck activity itself resumes an in-progress session.
            startActivity(Intent(this, MovieDeckActivity::class.java))
        })
        root.addView(homeDoor)

        // Nothing else on the chooser — the rest of the screen is air.
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
    }
}
