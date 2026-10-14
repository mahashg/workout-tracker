package com.mahesh.workouttracker

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
    // ---- v3.7: observable rest state ----------------------------------
    // Before v3.7 all rest state lived inside show()'s dialog: dismissing
    // the ring ended the rest. The top-bar REST chip (hub + card) needs a
    // rest that outlives its dialog, so the countdown is now an engine:
    // show() starts (or replaces) the one active rest and opens the ring
    // on top; dismissing the ring with back / outside-touch only HIDES
    // it — the countdown keeps running, the chip keeps ticking, and
    // tapping the chip reopens the ring on the same rest. Skip rest and
    // GO still end the rest and fire [onDone] exactly once. Ring visuals,
    // cue rotation, +15s and the GO pop + haptic are unchanged.
    @Volatile private var endsAtMs: Long = 0L
    @Volatile private var ringTotalMs: Long = 0L // ring fraction basis; grows on +15s, exactly like the pre-v3.7 dialog
    @Volatile private var nextLabel: String = ""
    @Volatile private var cleanCues: List<String> = emptyList()
    private var onDoneCb: (() -> Unit)? = null
    private var host: AppCompatActivity? = null
    private var fired = false
    private var restId = 0 // increments per show()/end; stale async work checks it

    private var ticker: CountDownTimer? = null
    private var dialog: AlertDialog? = null
    private var ring: RestRingView? = null
    private var cueTv: TextView? = null

    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) { synchronized(listeners) { if (l !in listeners) listeners.add(l) } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    private fun notifyListeners() {
        val copy = synchronized(listeners) { listeners.toList() }
        for (l in copy) try { l() } catch (e: Exception) { /* a dead chip never breaks a rest */ }
    }

    /** True while a rest is counting down (ring visible or not). */
    fun isActive(): Boolean = onDoneCb != null && endsAtMs > System.currentTimeMillis()

    /** Seconds left on the active rest (0 when none). */
    fun remainingSec(): Int {
        if (onDoneCb == null) return 0
        val left = endsAtMs - System.currentTimeMillis()
        return if (left <= 0) 0 else ceil(left / 1000.0).toInt()
    }

    /**
     * Starts a rest and shows the rest ring as a non-blocking dialog.
     * [nextLabel] is the S2 next-target line ("Next · Set 3 — last time
     * 15×10" or the next exercise's name); [cues] rotate under it every
     * ~5s. [onDone] fires exactly once — on GO, or on Skip rest. Hiding
     * the ring (back / outside touch) does NOT fire it: the rest keeps
     * counting behind the REST chip until it ends or is skipped there.
     * Calling show() while a rest is active replaces it silently (the
     * old rest's onDone is dropped, never fired).
     */
    fun show(activity: AppCompatActivity, seconds: Int, nextLabel: String, cues: List<String>, onDone: () -> Unit) {
        if (seconds <= 0) { onDone(); return }
        if (activity.isFinishing || activity.isDestroyed) { onDone(); return }
        endInternal(fire = false)
        restId++
        host = activity
        this.nextLabel = nextLabel
        cleanCues = cues.map { it.trim() }.filter { it.isNotBlank() }
        onDoneCb = onDone
        fired = false
        ringTotalMs = seconds * 1000L
        endsAtMs = System.currentTimeMillis() + ringTotalMs
        startTicker(ringTotalMs)
        showDialog(activity)
        notifyListeners()
    }

    /** Reopens the full ring for the currently active rest (chip tap). */
    fun reshow(activity: AppCompatActivity) {
        if (!isActive()) return
        if (dialog?.isShowing == true) return
        host = activity
        showDialog(activity)
    }

    /** Ends the active rest as if Skip rest was tapped (fires onDone). */
    fun skipActive() { if (onDoneCb != null) endInternal(fire = true) }

    private fun startTicker(ms: Long) {
        ticker?.cancel()
        val myId = restId
        ticker = object : CountDownTimer(ms, 250) {
            override fun onTick(left: Long) {
                if (myId != restId) return
                ring?.setRemaining(left)
                if (cleanCues.size > 1) {
                    val idx = (((ringTotalMs - left) / 5000).toInt()) % cleanCues.size
                    val t = "While you rest — ${cleanCues[idx]}"
                    val tv = cueTv
                    if (tv != null && tv.text.toString() != t) tv.text = t
                }
                notifyListeners()
            }
            override fun onFinish() {
                if (myId != restId) return
                val r = ring
                val h = host
                if (r != null && dialog?.isShowing == true) {
                    // Ring on screen: the classic GO pop, then end.
                    r.goPop = true; r.setRemaining(0)
                    if (h != null) buzz(h)
                    r.animate().scaleX(1.12f).scaleY(1.12f).setDuration(160).withEndAction {
                        r.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                    }.start()
                    r.postDelayed({ if (myId == restId) endInternal(fire = true) }, 950)
                } else {
                    if (h != null) buzz(h)
                    endInternal(fire = true)
                }
            }
        }.start()
    }

    private fun showDialog(activity: AppCompatActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val leftMs = (endsAtMs - System.currentTimeMillis()).coerceAtLeast(0)
        val r = RestRingView(activity).apply {
            totalMs = ringTotalMs; remainingMs = leftMs
            layoutParams = LinearLayout.LayoutParams(activity.dp(190), activity.dp(190)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        val nextTv = TextView(activity).apply {
            text = nextLabel; textSize = 15f; setTextColor(Theme.textPrimary); gravity = Gravity.CENTER
            setPadding(0, activity.dp(10), 0, 0); visibility = if (nextLabel.isBlank()) View.GONE else View.VISIBLE
        }
        val ct = TextView(activity).apply {
            textSize = 13f; setTextColor(Theme.textSecondary); gravity = Gravity.CENTER
            setPadding(activity.dp(8), activity.dp(4), activity.dp(8), 0)
            visibility = if (cleanCues.isEmpty()) View.GONE else View.VISIBLE
            if (cleanCues.isNotEmpty()) text = "While you rest — ${cleanCues[0]}"
        }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(activity.dp(20), activity.dp(18), activity.dp(20), activity.dp(16))
            addView(r); addView(nextTv); addView(ct)
        }
        val d = AlertDialog.Builder(activity).setView(col)
            .setPositiveButton("+15s", null)
            .setNegativeButton("Skip rest", null)
            .create()
        d.setOnShowListener {
            // v3.2 polish: the system dialog buttons take the app palette
            // (primary for +15s, secondary for Skip rest) at a readable size.
            d.getButton(AlertDialog.BUTTON_POSITIVE)?.apply { setTextColor(Theme.primary); textSize = 15f }
            d.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply { setTextColor(Theme.textSecondary); textSize = 15f }
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // Same arithmetic as the pre-v3.7 dialog: remaining + 15s
                // becomes the ring's new total and it restarts full.
                val left = (endsAtMs - System.currentTimeMillis()).coerceAtLeast(0) + 15_000
                ringTotalMs = left
                endsAtMs = System.currentTimeMillis() + left
                r.totalMs = left; r.setRemaining(left)
                startTicker(left)
                notifyListeners()
            }
            d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { endInternal(fire = true) }
        }
        // Back / outside-touch: hide the ring ONLY. The engine keeps the
        // rest counting (REST chip); onDone is not fired. Any dismiss —
        // hide, skip, finish — just drops the dialog view references.
        d.setOnDismissListener {
            if (dialog === d) { dialog = null; ring = null; cueTv = null }
        }
        try {
            d.show()
            dialog = d; ring = r; cueTv = ct
        } catch (e: Exception) {
            // A dead window must never strand the workout: end the rest.
            endInternal(fire = true)
        }
    }

    private fun endInternal(fire: Boolean) {
        restId++
        ticker?.cancel(); ticker = null
        val cb = onDoneCb
        onDoneCb = null; endsAtMs = 0L; ringTotalMs = 0L
        val d = dialog
        dialog = null; ring = null; cueTv = null
        try { if (d?.isShowing == true) d.dismiss() } catch (e: Exception) {}
        notifyListeners()
        if (fire && cb != null && !fired) { fired = true; cb() }
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

/**
 * v3.7 top-bar REST chip (approved mock): an amber pill — #FEF3C7 fill,
 * #F59E0B 1dp border, #92400E ink — labeled "REST m:ss", shown next to
 * the elapsed-time chip on the card screen and the hub while a rest is
 * active. It ticks with the RestTimer engine and disappears the moment
 * the rest ends or is skipped; tapping it reopens the full Rest Ring
 * for the same rest.
 */
class RestChipView(context: Context) : TextView(context) {
    private val listener: () -> Unit = { refresh() }
    private var host: AppCompatActivity? = null

    init {
        textSize = 12.5f
        setTextColor(Color.parseColor("#92400E"))
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#FEF3C7"))
            cornerRadius = context.dp(999).toFloat()
            setStroke(context.dp(1), Color.parseColor("#F59E0B"))
        }
        setPadding(context.dp(11), context.dp(3), context.dp(11), context.dp(3))
        visibility = View.GONE
        isClickable = true
        setOnClickListener { host?.let { RestTimer.reshow(it) } }
    }

    fun bind(activity: AppCompatActivity) { host = activity }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        RestTimer.addListener(listener)
        refresh()
    }

    override fun onDetachedFromWindow() {
        RestTimer.removeListener(listener)
        super.onDetachedFromWindow()
    }

    fun refresh() {
        if (RestTimer.isActive()) {
            visibility = View.VISIBLE
            text = "REST " + WorkoutTimer.formatClock(RestTimer.remainingSec())
        } else {
            visibility = View.GONE
        }
    }
}
