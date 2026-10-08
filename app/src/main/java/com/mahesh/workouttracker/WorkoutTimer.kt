package com.mahesh.workouttracker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Workout elapsed timer (v2.3).
 *
 * Model: sessions.elapsedSec is the banked seconds. While running, live elapsed
 * = elapsedSec + (now - resumeTs)/1000, where resumeTs lives in SharedPreferences
 * as `timer_resume_<sessionId>` (epoch ms). Pausing folds the live delta back
 * into elapsedSec and clears the resume key. `timer_paused_<sessionId>` records
 * the paused state so reopening the app restores it faithfully.
 */
object WorkoutTimer {
    private const val PREFS = "workout_prefs"
    private fun resumeKey(sessionId: Long) = "timer_resume_$sessionId"
    private fun pausedKey(sessionId: Long) = "timer_paused_$sessionId"

    fun formatClock(totalSec: Int): String {
        val s = totalSec.coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }
    fun formatDuration(totalSec: Int): String {
        val s = totalSec.coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60
        return when {
            h > 0 -> "$h hr $m min"
            m > 0 -> "$m min"
            s > 0 -> "$s sec"
            else -> "0 min"
        }
    }

    fun isPaused(context: Context, sessionId: Long): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(pausedKey(sessionId), false)

    private fun resumeTs(context: Context, sessionId: Long): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(resumeKey(sessionId), 0L)

    /** Live (banked + running delta) elapsed seconds for a session. */
    fun liveElapsedSec(context: Context, session: SessionInfo): Int {
        if (session.completed || session.startedAt <= 0) return session.elapsedSec
        val rt = resumeTs(context, session.id)
        if (isPaused(context, session.id) || rt <= 0L) return session.elapsedSec
        val delta = ((System.currentTimeMillis() - rt) / 1000L).toInt()
        return session.elapsedSec + delta.coerceAtLeast(0)
    }

    /** Begin ticking for an in-progress session (idempotent). */
    fun ensureRunning(context: Context, db: DbHelper, session: SessionInfo) {
        if (session.completed || session.startedAt <= 0) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(resumeKey(session.id)) && !isPaused(context, session.id)) {
            prefs.edit().putLong(resumeKey(session.id), System.currentTimeMillis()).apply()
        }
    }

    /** Fold the running delta into elapsedSec and persist. Returns the new banked seconds. */
    fun fold(context: Context, db: DbHelper, session: SessionInfo): Int {
        val live = liveElapsedSec(context, session)
        db.updateSessionTimer(session.id, live)
        if (!session.completed && session.startedAt > 0 && !isPaused(context, session.id)) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(resumeKey(session.id), System.currentTimeMillis()).apply()
        }
        return live
    }

    fun pause(context: Context, db: DbHelper, session: SessionInfo): Int {
        val live = liveElapsedSec(context, session)
        db.updateSessionTimer(session.id, live)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(pausedKey(session.id), true).remove(resumeKey(session.id)).apply()
        return live
    }

    fun resume(context: Context, session: SessionInfo) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(pausedKey(session.id), false)
            .putLong(resumeKey(session.id), System.currentTimeMillis()).apply()
    }

    /** Finalize on Finish Workout: fold, stamp endedAt, stop the clock. */
    fun finish(context: Context, db: DbHelper, session: SessionInfo): Int {
        val live = liveElapsedSec(context, session)
        db.updateSessionTimer(session.id, live, System.currentTimeMillis())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(pausedKey(session.id)).remove(resumeKey(session.id)).apply()
        return live
    }

    fun clear(context: Context, sessionId: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(pausedKey(sessionId)).remove(resumeKey(sessionId)).apply()
    }
}

/** Ticking timer chip for session screens. Tap = pause/resume. */
class TimerChipView(context: Context) : LinearLayout(context) {
    private val label = TextView(context)
    private val icon = ImageView(context)
    private val handler = Handler(Looper.getMainLooper())
    private var session: SessionInfo? = null
    private var db: DbHelper? = null
    private var ticking = false
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            if (ticking) handler.postDelayed(this, 1000L)
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(10), context.dp(6), context.dp(12), context.dp(6))
        icon.setImageResource(R.drawable.ic_timer)
        icon.setColorFilter(Theme.primary)
        icon.layoutParams = LayoutParams(context.dp(18), context.dp(18))
        addView(icon)
        label.textSize = 14f
        label.setTextColor(Theme.primary)
        label.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        label.setPadding(context.dp(6), 0, 0, 0)
        addView(label)
        background = chipBg(context)
        isClickable = true
    }

    private fun chipBg(c: Context): android.graphics.drawable.Drawable {
        val gd = android.graphics.drawable.GradientDrawable().apply {
            setColor(Theme.surfaceVariant); cornerRadius = c.dp(20).toFloat(); setStroke(c.dp(1), Theme.stroke)
        }
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x222563EB), gd,
            android.graphics.drawable.GradientDrawable().apply { setColor(android.graphics.Color.WHITE); cornerRadius = c.dp(20).toFloat() })
    }

    fun bind(context: Context, db: DbHelper, session: SessionInfo, onToggle: () -> Unit) {
        this.db = db; this.session = session
        WorkoutTimer.ensureRunning(context, db, session)
        setOnClickListener {
            val s = this.session ?: return@setOnClickListener
            val fresh = db.getSession(s.id) ?: return@setOnClickListener
            if (WorkoutTimer.isPaused(context, s.id)) WorkoutTimer.resume(context, fresh)
            else WorkoutTimer.pause(context, db, fresh)
            this.session = db.getSession(s.id)
            refresh(); onToggle()
        }
        refresh()
    }

    fun refresh() {
        val s = session ?: return
        val ctx = context
        val fresh = db?.getSession(s.id) ?: s
        session = fresh
        val sec = WorkoutTimer.liveElapsedSec(ctx, fresh)
        val paused = WorkoutTimer.isPaused(ctx, fresh.id) && !fresh.completed
        label.text = (if (paused) "Paused  " else "") + WorkoutTimer.formatClock(sec)
    }

    fun startTicking() { if (!ticking) { ticking = true; handler.post(ticker) } }
    fun stopTicking() { ticking = false; handler.removeCallbacks(ticker) }

    /** Fold current elapsed into the DB (call from onPause of host screen). */
    fun foldNow() {
        val s = session ?: return; val d = db ?: return
        if (!s.completed) WorkoutTimer.fold(context, d, d.getSession(s.id) ?: s)
    }
}
