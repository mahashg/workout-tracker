package com.mahesh.workouttracker

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.ceil

/**
 * v3 "Rest Ring" (S5): a code-drawn circular countdown that replaces the
 * plain rest dialog everywhere. The ring drains as seconds pass, turns warm
 * amber in the final 10 seconds, and pops a green "GO" + haptic at zero.
 * Same hand-drawn view style as ConfettiView — no image assets.
 */
class RestRingView(context: Context) : View(context) {
    var totalMs: Long = 60_000
    var remainingMs: Long = 60_000
    var goPop: Boolean = false
    // v3.2 polish: stroke width is density-correct (10dp) — it was a raw
    // 26px, which rendered thinner on high-density screens than designed.
    private val strokePx = context.dp(10).toFloat()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = strokePx; color = 0xFFE2E8F0.toInt(); strokeCap = Paint.Cap.ROUND }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = strokePx; strokeCap = Paint.Cap.ROUND }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val arc = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 20f
        arc.set(pad, pad, width - pad, height - pad)
        canvas.drawArc(arc, 0f, 360f, false, trackPaint)
        val frac = if (totalMs > 0) (remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f) else 0f
        val remainSec = ceil(remainingMs / 1000.0).toInt()
        ringPaint.color = when {
            goPop -> 0xFF16A34A.toInt()
            remainSec <= 10 -> 0xFFD97706.toInt() // warm amber, final 10s
            else -> 0xFF2563EB.toInt() // primary blue
        }
        if (!goPop && frac > 0f) canvas.drawArc(arc, -90f, 360f * frac, false, ringPaint)
        if (goPop) canvas.drawArc(arc, 0f, 360f, false, ringPaint)
        textPaint.color = ringPaint.color
        val label = if (goPop) "GO" else remainSec.toString()
        textPaint.textSize = if (goPop) height * 0.26f else height * 0.32f
        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, width / 2f, baseline, textPaint)
    }

    fun setRemaining(ms: Long) { remainingMs = ms; invalidate() }
}

object RestTimer {
    /**
     * Shows the rest ring as a non-blocking dialog. [nextLabel] is the S2
     * next-target line ("Next · Set 3 — last time 15×10" or the next
     * exercise's name); [cues] rotate under it every ~5s. [onDone] fires
     * exactly once — on GO, Skip rest, or dismiss.
     */
    fun show(activity: AppCompatActivity, seconds: Int, nextLabel: String, cues: List<String>, onDone: () -> Unit) {
        if (seconds <= 0) { onDone(); return }
        if (activity.isFinishing || activity.isDestroyed) { onDone(); return }
        var fired = false
        fun fireOnce() { if (!fired) { fired = true; onDone() } }
        val ring = RestRingView(activity).apply {
            totalMs = seconds * 1000L; remainingMs = totalMs
            layoutParams = LinearLayout.LayoutParams(activity.dp(190), activity.dp(190)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        val nextTv = TextView(activity).apply {
            text = nextLabel; textSize = 15f; setTextColor(Theme.textPrimary); gravity = Gravity.CENTER
            setPadding(0, activity.dp(10), 0, 0); visibility = if (nextLabel.isBlank()) View.GONE else View.VISIBLE
        }
        val cleanCues = cues.map { it.trim() }.filter { it.isNotBlank() }
        val cueTv = TextView(activity).apply {
            textSize = 13f; setTextColor(Theme.textSecondary); gravity = Gravity.CENTER
            setPadding(activity.dp(8), activity.dp(4), activity.dp(8), 0)
            visibility = if (cleanCues.isEmpty()) View.GONE else View.VISIBLE
            if (cleanCues.isNotEmpty()) text = "While you rest — ${cleanCues[0]}"
        }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(activity.dp(20), activity.dp(18), activity.dp(20), activity.dp(16))
            addView(ring); addView(nextTv); addView(cueTv)
        }
        val dialog = AlertDialog.Builder(activity).setView(col)
            .setPositiveButton("+15s", null)
            .setNegativeButton("Skip rest", null)
            .create()
        var timer: CountDownTimer? = null
        var total = seconds * 1000L
        var startedAt = System.currentTimeMillis()
        fun startTimer(ms: Long) {
            timer?.cancel()
            startedAt = System.currentTimeMillis()
            timer = object : CountDownTimer(ms, 50) {
                override fun onTick(left: Long) {
                    ring.setRemaining(left)
                    if (cleanCues.size > 1) {
                        val idx = (((ms - left) / 5000).toInt()) % cleanCues.size
                        val t = "While you rest — ${cleanCues[idx]}"
                        if (cueTv.text.toString() != t) cueTv.text = t
                    }
                }
                override fun onFinish() {
                    ring.goPop = true; ring.setRemaining(0)
                    buzz(activity)
                    ring.animate().scaleX(1.12f).scaleY(1.12f).setDuration(160).withEndAction {
                        ring.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                    }.start()
                    ring.postDelayed({
                        if (dialog.isShowing) dialog.dismiss()
                        fireOnce()
                    }, 950)
                }
            }.start()
        }
        dialog.setOnShowListener {
            // v3.2 polish: the system dialog buttons take the app palette
            // (primary for +15s, secondary for Skip rest) at a readable size.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply { setTextColor(Theme.primary); textSize = 15f }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply { setTextColor(Theme.textSecondary); textSize = 15f }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val elapsed = System.currentTimeMillis() - startedAt
                val left = (total - elapsed).coerceAtLeast(0) + 15_000
                total = left; ring.totalMs = left; ring.setRemaining(left)
                startTimer(left)
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                timer?.cancel(); dialog.dismiss(); fireOnce()
            }
        }
        dialog.setOnCancelListener { timer?.cancel(); fireOnce() }
        dialog.setOnDismissListener { timer?.cancel() }
        try {
            dialog.show()
            startTimer(total)
        } catch (e: Exception) {
            fireOnce()
        }
    }

    private fun buzz(context: Context) {
        try {
            val vib = if (android.os.Build.VERSION.SDK_INT >= 31) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vib.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) { /* haptics are a nicety, never a crash */ }
    }
}
