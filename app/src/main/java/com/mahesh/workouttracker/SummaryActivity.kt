package com.mahesh.workouttracker

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class SummaryActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var sessionId: Long = -1
    private var celebrate = false
    private val autoHomeHandler = Handler(Looper.getMainLooper())
    private var autoHomePending: Runnable? = null
    private var confetti: ConfettiView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); db = DbHelper(this); sessionId = intent.getLongExtra("sessionId", -1); if (sessionId < 0) { finish(); return }
        // Celebration fires ONLY on a fresh completion (both finish sites pass
        // celebrate=true). Opening a summary from History does not celebrate.
        celebrate = intent.getBooleanExtra("celebrate", false)
        val session = db.getSession(sessionId) ?: run { finish(); return }
        val exs = db.getSessionExercises(sessionId)

        // Root: FrameLayout { existing fixed column, ConfettiView overlay }.
        // The column keeps every existing height budget (no-scroll invariant).
        val frame = FrameLayout(this).apply { setBackgroundColor(Theme.bg) }
        val root = fitRoot()
        frame.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        root.addView(topBar("Workout Summary", "${DateUtil.display(session.date)} • Week ${session.weekNumber}"))

        // Explicit budgets (v2.5.1): hero 84 + stats 148 + by-section 92 +
        // buttons 48 (+ fixed top bar 56) leaves a flexible filler. When
        // celebrating, the hero grows by the popper line (110) and the
        // filler absorbs the difference, so the stack still fits one page.
        val celeb = heroCard()
        celeb.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(if (celebrate) 110 else 84)).apply { setMargins(0, 0, 0, 0) }
        celeb.setPadding(dp(12), dp(8), dp(12), dp(8))
        if (celebrate) {
            val popper = makeText("🎉", 40f, true, android.graphics.Color.WHITE).apply {
                gravity = Gravity.CENTER
                (layoutParams as LinearLayout.LayoutParams).apply { height = dp(46); setMargins(0, 0, 0, 0) }
            }
            celeb.addView(popper)
            // Gentle bounce: 0.6 -> 1.1 -> 1.0 over ~400ms.
            popper.scaleX = 0.6f; popper.scaleY = 0.6f
            val bounce = ObjectAnimator.ofFloat(popper, "scaleX", 0.6f, 1.1f, 1.0f)
            val bounceY = ObjectAnimator.ofFloat(popper, "scaleY", 0.6f, 1.1f, 1.0f)
            for (a in listOf(bounce, bounceY)) { a.duration = 420; a.interpolator = OvershootInterpolator(1.2f); a.start() }
        }
        celeb.addView(makeText(if (celebrate) "🎉 Workout complete!" else "Workout complete!", if (celebrate) 21f else 23f, true, android.graphics.Color.WHITE).apply { gravity = Gravity.CENTER; (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0) })
        // v2.6.1 copy audit: no "— nice work showing up…" filler subtitle.
        root.addView(celeb)

        val done = exs.count { db.effectiveStatus(it) == "done" }; val skipped = exs.count { db.effectiveStatus(it) == "skipped" }; val setsDone = exs.sumOf { db.getSets(it.id).count { s -> s.isDone } }; val setsTotal = exs.sumOf { db.getSets(it.id).size }
        val muscles = linkedSetOf<String>(); for (ex in exs) if (db.effectiveStatus(ex) == "done") muscles.addAll(DbHelper.parseMuscles(ex.targetMuscles, ex.name))
        val card = cardLayout("#15803D")
        card.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(148)).apply { setMargins(0, 0, 0, 0) }
        card.setPadding(dp(12), dp(8), dp(12), dp(8))
        card.addView(overline("Today you trained"))
        card.addView(cardTitle(musclesLabel(muscles)))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        mid.addView(BodyMapView(this, muscles, true).apply { layoutParams = LinearLayout.LayoutParams(dp(84), dp(104)) })
        val stats = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(12), 0, 0, 0) }
        fun statRow(v: String, l: String) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(makeText(v, 20f, true, Theme.success).apply { layoutParams = LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT) })
            r.addView(caption(l))
            stats.addView(r)
        }
        statRow("$done", "exercises done")
        statRow("$setsDone/$setsTotal", "sets done")
        statRow("$skipped", "skipped")
        if (session.startedAt > 0) statRow(WorkoutTimer.formatDuration(session.elapsedSec), "time")
        mid.addView(stats)
        card.addView(mid)
        root.addView(card)

        val perCard = cardLayout()
        perCard.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(92)).apply { setMargins(0, 0, 0, 0) }
        perCard.setPadding(dp(12), dp(8), dp(12), dp(8))
        perCard.addView(overline("By section"))
        for (label in listOf("Warm Up" to "Warmup", "Exercise" to "Main", "Cool Down" to "Stretch")) {
            val list = exs.filter { it.type.equals(label.second, true) }; if (list.isEmpty()) continue
            perCard.addView(bodyText("${label.first}: ${list.count { db.effectiveStatus(it) == "done" }} done, ${list.count { db.effectiveStatus(it) == "skipped" }} skipped, ${list.count { db.effectiveStatus(it) == "pending" }} not done"))
        }
        root.addView(perCard)

        // Filler keeps the buttons pinned to the bottom of the fixed screen.
        // v2.6.1 copy audit: "Every workout counts — see you next session." filler removed; stats only.
        root.addView(LinearLayout(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) })
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (celebrate) {
            val homeBtn = makeButton("Back to Home") { goHome() }
            (homeBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48); setMargins(0, dp(4), dp(4), 0) }
            btnRow.addView(homeBtn)
            val listBtn = makeSecondaryButton("View as list") { cancelAutoHome(); startActivity(Intent(this, SessionActivity::class.java).apply { putExtra("sessionId", sessionId) }); finish() }
            (listBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48); setMargins(dp(4), dp(4), 0, 0) }
            btnRow.addView(listBtn)
        } else {
            val doneBtn = makeButton("Done") { finish() }
            (doneBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48); setMargins(0, dp(4), dp(4), 0) }
            val listBtn = makeSecondaryButton("View as list") { startActivity(Intent(this, SessionActivity::class.java).apply { putExtra("sessionId", sessionId) }); finish() }
            (listBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48); setMargins(dp(4), dp(4), 0, 0) }
            btnRow.addView(doneBtn); btnRow.addView(listBtn)
        }
        root.addView(btnRow)

        if (celebrate) {
            val overlay = ConfettiView(this)
            overlay.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            frame.addView(overlay)
            confetti = overlay
            popHaptic()
            overlay.post { overlay.burst() }
            scheduleAutoHome()
        }
        setContentView(frame)
    }

    /** Any user interaction cancels the pending auto-return to Home. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        cancelAutoHome()
    }

    override fun onPause() {
        super.onPause()
        cancelAutoHome()
    }

    private fun scheduleAutoHome() {
        cancelAutoHome()
        // Confetti runs ~2.8s; Mahesh wants to end back on Home, so the
        // summary auto-returns after ~4.5s total unless he touches anything.
        val r = Runnable { if (!isFinishing && !isDestroyed) goHome() }
        autoHomePending = r
        autoHomeHandler.postDelayed(r, AUTO_HOME_MS)
    }

    private fun cancelAutoHome() {
        autoHomePending?.let { autoHomeHandler.removeCallbacks(it) }
        autoHomePending = null
    }

    private fun goHome() {
        cancelAutoHome()
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun popHaptic() {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") vib.vibrate(80)
        } catch (e: Exception) { /* haptics are a garnish, never a crash */ }
    }

    companion object { const val AUTO_HOME_MS = 4500L }
}
