package com.mahesh.workouttracker

import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * v3.4: Health Connect permissions rationale. Health Connect opens this
 * screen from its permission flow and from its privacy settings so the
 * user can read why Workout Tracker asks to write exercise data. Plain
 * Mist styling; no actions here — the toggle lives in Settings.
 */
class HealthRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = fitRoot()
        setContentView(root)
        root.addView(topBar("Health Connect", "Why Workout Tracker uses it"))
        val card = cardLayout()
        card.setPadding(dp(14), dp(12), dp(14), dp(12))
        fun line(text: String, bold: Boolean = false) {
            val tv = bodyText(text)
            if (bold) tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
            tv.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, dp(4)) }
            card.addView(tv)
        }
        line("When you finish a workout, Workout Tracker writes it to Health Connect on this device:", true)
        line("• the workout's start time, duration and title (the body parts trained, e.g. \"Back + Biceps\")")
        line("• one entry per exercise you completed, with your total reps and your heaviest set")
        line("This happens only when the Health Connect toggle in Settings is on, and only for workouts you finish after turning it on (plus any past workouts you choose to sync from Settings).")
        line("Nothing is sent to the Workout Tracker developers — there is no account and no server. The data stays in Health Connect on this phone; apps you allow in Health Connect (for example Google Fit) can then show your workouts.")
        line("You can turn sync off any time in Settings, and you can remove Workout Tracker's data from inside the Health Connect app.")
        root.addView(card)
    }
}
