package com.mahesh.workouttracker

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/** v3.9 Home = the CHOOSER (approved mock r4, frame 1): "What are we
 *  doing today?" over two doors and nothing else. The Movements
 *  door's body opens its page (AtHomeActivity) and its plain ▶ starts
 *  the deck directly. v3.9.1: the WORKOUT door no longer opens an
 *  intermediate page — Mahesh found the Workout page useless — BOTH
 *  its body and its ▶ go straight to the start-workout flow:
 *  PickPartsActivity when idle, the resume dialog (Resume workout /
 *  View all exercises / Discard & start new, the pre-v3.9.1 Start
 *  semantics) while a gym session is in progress. WorkoutActivity is
 *  retired. The week line lives on the chooser sub-title ("Week N ·
 *  X of 10 parts done"). The v3.4 Health Connect sync row is GONE
 *  (sync UI lives in Settings only, per Mahesh's round-2 note;
 *  finished workouts still auto-sync). */
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
            onWorkoutTap()
        }
        (gymDoor.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(14), 0, 0)
        gymDoor.addView(doorIcon("🏋️", IconTints.BLUE))
        gymDoor.addView(doorTextCol("Workout", gymDesc, gymMeta))
        gymDoor.addView(playGlyph {
            // ▶ does exactly what the door body does (v3.9.1): the
            // start-workout flow, no intermediate page.
            onWorkoutTap()
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

    /** The Workout door tap (body and ▶ alike, v3.9.1): idle -> the
     *  body-part picker (the start-workout page); a gym session in
     *  progress -> the three-way resume dialog, semantics identical
     *  to the retired Workout page's Start row (Resume lands on the
     *  first pending card, v3.8.2; View all = the hub overview;
     *  Discard deletes the session and opens the picker). */
    private fun onWorkoutTap() {
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
}
