package com.mahesh.workouttracker

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
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
    // v3.13: when false, onDraw draws track + arc only (no number / GO
    // text) — the Bend rest dialog carries its countdown as huge text
    // below the circle instead.
    var showCenterText: Boolean = true
    // v3.2 polish: stroke width is density-correct (10dp) — it was a raw
    // 26px, which rendered thinner on high-density screens than designed.
    private val strokePx = context.dp(10).toFloat()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = strokePx; color = Theme.surfaceVariant; strokeCap = Paint.Cap.ROUND }
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
            else -> Theme.primary
        }
        if (!goPop && frac > 0f) canvas.drawArc(arc, -90f, 360f * frac, false, ringPaint)
        if (goPop) canvas.drawArc(arc, 0f, 360f, false, ringPaint)
        if (showCenterText) {
            textPaint.color = ringPaint.color
            val label = if (goPop) "GO" else remainSec.toString()
            textPaint.textSize = if (goPop) height * 0.26f else height * 0.32f
            val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(label, width / 2f, baseline, textPaint)
        }
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

    // ---- v3.12: pause + persistence --------------------------------
    // Pausing (via the timer chip) freezes the countdown in place:
    // pausedRemainingMs >= 0 means paused with that much left; the
    // wall-clock deadline is meaningless while paused.
    @Volatile private var pausedRemainingMs: Long = -1L
    // The active rest is mirrored into SharedPreferences ("workout_prefs",
    // restp_* keys) so rotation — and even process death — can re-attach
    // to it via attach(). Only rests started with a sessionId persist;
    // legacy no-session rests (SessionActivity) stay memory-only.
    private var appCtx: Context? = null
    @Volatile private var pSessionId: Long = -1
    @Volatile private var pKind: String = ""
    @Volatile private var pTargetId: Long = -1
    @Volatile private var pFromId: Long = -1
    private const val PREFS = "workout_prefs"
    private val RESTP_KEYS = listOf(
        "restp_deadline", "restp_total", "restp_label", "restp_cues", "restp_kind",
        "restp_session", "restp_target", "restp_from", "restp_ring", "restp_paused_rem"
    )

    /** v3.12: mirror the current rest state into prefs (or clear every
     *  restp_* key when this rest carries no session). The deadline is
     *  stored as 0 while paused — pausedRemainingMs is the truth then. */
    private fun persist() {
        val ctx = appCtx ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (pSessionId <= 0) { clearPersisted(prefs); return }
        prefs.edit()
            .putLong("restp_deadline", if (pausedRemainingMs >= 0) 0L else endsAtMs)
            .putLong("restp_total", ringTotalMs)
            .putString("restp_label", nextLabel)
            .putString("restp_cues", cleanCues.joinToString("\u0001"))
            .putString("restp_kind", pKind)
            .putLong("restp_session", pSessionId)
            .putLong("restp_target", pTargetId)
            .putLong("restp_from", pFromId)
            .putInt("restp_ring", if (dialog?.isShowing == true) 1 else 0)
            .putLong("restp_paused_rem", pausedRemainingMs)
            .apply()
    }

    private fun clearPersisted(prefs: android.content.SharedPreferences) {
        val e = prefs.edit()
        for (k in RESTP_KEYS) e.remove(k)
        e.apply()
    }

    private fun clearPersisted() {
        val ctx = appCtx ?: return
        clearPersisted(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }

    /** v3.12: public persist hook — hosts call it from onPause so the
     *  ring-visible flag is captured before rotation teardown. */
    fun persistNow() { persist() }

    private var ticker: CountDownTimer? = null
    private var dialog: AlertDialog? = null
    private var ring: RestRingView? = null
    private var cueTv: TextView? = null
    // v3.13 Bend rest dialog: huge countdown, top-row elapsed, the circle
    // container (photo frame + ring) and the dialog's DbHelper. dlgDb is
    // dropped (never closed) on dismiss / endInternal.
    private var bigCountTv: TextView? = null
    private var elapsedTv: TextView? = null
    private var circleBox: View? = null
    private var dlgDb: DbHelper? = null

    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) { synchronized(listeners) { if (l !in listeners) listeners.add(l) } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    private fun notifyListeners() {
        val copy = synchronized(listeners) { listeners.toList() }
        for (l in copy) try { l() } catch (e: Exception) { /* a dead chip never breaks a rest */ }
    }

    /** True while a rest is counting down (ring visible or not).
     *  v3.12: a PAUSED rest is still active — it holds its remaining
     *  time frozen instead of counting against the wall clock. */
    fun isActive(): Boolean = onDoneCb != null && (pausedRemainingMs >= 0 || endsAtMs > System.currentTimeMillis())

    /** v3.12: true while a rest is frozen by pauseRest(). */
    fun isPaused(): Boolean = pausedRemainingMs >= 0 && onDoneCb != null

    /** Seconds left on the active rest (0 when none). */
    fun remainingSec(): Int {
        if (onDoneCb == null) return 0
        if (pausedRemainingMs >= 0) return ceil(pausedRemainingMs / 1000.0).toInt()
        val left = endsAtMs - System.currentTimeMillis()
        return if (left <= 0) 0 else ceil(left / 1000.0).toInt()
    }

    /** v3.12: freeze the active rest where it stands (timer-chip
     *  pause). The ticker stops; the ring, if up, shows the frozen
     *  remainder. No-op when no rest is active or it is already paused. */
    fun pauseRest() {
        if (onDoneCb == null || pausedRemainingMs >= 0) return
        pausedRemainingMs = (endsAtMs - System.currentTimeMillis()).coerceAtLeast(0)
        ticker?.cancel(); ticker = null
        ring?.setRemaining(pausedRemainingMs)
        persist()
        notifyListeners()
    }

    /** v3.12: thaw a frozen rest — the deadline re-bases to now + the
     *  frozen remainder and the ticker restarts. ringTotalMs is left
     *  unchanged (startTicker's cue indexing keys off it). No-op when
     *  no rest is paused. */
    fun resumeRest() {
        if (onDoneCb == null || pausedRemainingMs < 0) return
        val rem = pausedRemainingMs
        endsAtMs = System.currentTimeMillis() + rem
        pausedRemainingMs = -1L
        ring?.setRemaining(rem)
        startTicker(rem)
        persist()
        notifyListeners()
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
     *
     * v3.12: [sessionId]/[kind]/[targetId]/[fromId] are the rest's
     * persistence identity (defaults keep legacy call sites memory-only).
     */
    fun show(activity: AppCompatActivity, seconds: Int, nextLabel: String, cues: List<String>,
             sessionId: Long = -1, kind: String = "", targetId: Long = -1, fromId: Long = -1,
             onDone: () -> Unit) {
        if (seconds <= 0) { onDone(); return }
        if (activity.isFinishing || activity.isDestroyed) { onDone(); return }
        endInternal(fire = false)
        restId++
        host = activity
        appCtx = activity.applicationContext
        this.nextLabel = nextLabel
        cleanCues = cues.map { it.trim() }.filter { it.isNotBlank() }
        onDoneCb = onDone
        fired = false
        pausedRemainingMs = -1L
        // v3.12: persistence identity — who this rest belongs to and
        // what its completion means (attach() rebuilds onDone from
        // these via its factory after rotation / process death).
        pSessionId = sessionId; pKind = kind; pTargetId = targetId; pFromId = fromId
        ringTotalMs = seconds * 1000L
        endsAtMs = System.currentTimeMillis() + ringTotalMs
        startTicker(ringTotalMs)
        showDialog(activity)
        persist()
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

    /** v3.12: ends the active rest WITHOUT firing onDone — the
     *  finish-workout path, where a mid-flight rest's completion
     *  callback must never advance a session that is already over. */
    fun cancelActive() { if (onDoneCb != null) endInternal(fire = false) }

    /**
     * v3.12: re-attach a screen to the active rest after rotation or
     * process death. Returns true when a rest for [sessionId] was
     * live (in memory) or rebuilt from prefs; false when there is
     * nothing to re-attach to.
     *
     * In-memory engine still alive (plain rotation): just reshow the
     * ring if it was up when the old screen went away. Otherwise the
     * persisted state is rebuilt and [factory] supplies the fresh
     * onDone for (kind, targetId, fromId) — the rebuilding screen owns
     * what completion means now. A rest whose deadline passed while
     * away completes immediately through that rebuilt callback
     * (exactly once), which the card's stale-target guard keeps safe.
     */
    fun attach(activity: AppCompatActivity, sessionId: Long, factory: (kind: String, targetId: Long, fromId: Long) -> () -> Unit): Boolean {
        appCtx = activity.applicationContext
        host = activity
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (isActive()) {
            if (prefs.getInt("restp_ring", 0) == 1) reshow(activity)
            return true
        }
        if (!prefs.contains("restp_total")) return false
        if (prefs.getLong("restp_session", -1L) != sessionId) return false
        nextLabel = prefs.getString("restp_label", "") ?: ""
        cleanCues = (prefs.getString("restp_cues", "") ?: "").split("\u0001").map { it.trim() }.filter { it.isNotBlank() }
        ringTotalMs = prefs.getLong("restp_total", 0L)
        pSessionId = prefs.getLong("restp_session", -1L)
        pKind = prefs.getString("restp_kind", "") ?: ""
        pTargetId = prefs.getLong("restp_target", -1L)
        pFromId = prefs.getLong("restp_from", -1L)
        onDoneCb = factory(pKind, pTargetId, pFromId)
        fired = false
        restId++
        val ringFlag = prefs.getInt("restp_ring", 0)
        val pausedRem = prefs.getLong("restp_paused_rem", -1L)
        if (pausedRem >= 0) {
            pausedRemainingMs = pausedRem
            endsAtMs = 0L
            notifyListeners()
            if (ringFlag == 1) showDialog(activity)
            return true
        }
        pausedRemainingMs = -1L
        val deadline = prefs.getLong("restp_deadline", 0L)
        val remaining = deadline - System.currentTimeMillis()
        if (remaining > 0) {
            endsAtMs = deadline
            startTicker(remaining)
            notifyListeners()
            if (ringFlag == 1) showDialog(activity)
            return true
        }
        // Deadline passed while away: fire the rebuilt onDone once.
        endInternal(fire = true)
        return true
    }

    private fun startTicker(ms: Long) {
        ticker?.cancel()
        val myId = restId
        ticker = object : CountDownTimer(ms, 250) {
            override fun onTick(left: Long) {
                if (myId != restId) return
                ring?.setRemaining(left)
                // v3.13: the Bend dialog's huge countdown + top-row elapsed.
                val leftSec = ceil(left / 1000.0).toInt()
                bigCountTv?.let {
                    it.text = WorkoutTimer.formatClock(leftSec)
                    it.setTextColor(if (leftSec <= 10) 0xFFD97706.toInt() else Theme.textPrimary)
                }
                elapsedTv?.let { tv ->
                    val db = dlgDb
                    if (db != null && pSessionId > 0) {
                        val sess = db.getSession(pSessionId)
                        if (sess != null && !sess.completed) {
                            tv.text = WorkoutTimer.formatClock(WorkoutTimer.liveElapsedSec(tv.context, sess))
                            if (tv.visibility != View.VISIBLE) tv.visibility = View.VISIBLE
                        } else {
                            tv.text = ""
                        }
                    }
                }
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
                // v3.18: a set/exercise rest that runs out NATURALLY
                // buzzes for a full 2 seconds; legacy "" rests keep
                // the 120ms tick. This onFinish is the only place a
                // long buzz can be requested — Skip, +15s, cancel and
                // the expired-while-away path never reach it.
                val buzzMs = if (pKind == "set" || pKind == "exercise") 2000L else 120L
                if (r != null && dialog?.isShowing == true) {
                    if (!r.showCenterText) {
                        // v3.13 Bend dialog: GO lands on the huge text and
                        // the whole circle container pops, then end.
                        bigCountTv?.let { it.text = "GO"; it.setTextColor(Theme.success) }
                        if (h != null) buzz(h, buzzMs)
                        val box = circleBox ?: r
                        box.animate().scaleX(1.12f).scaleY(1.12f).setDuration(160).withEndAction {
                            box.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                        }.start()
                        box.postDelayed({ if (myId == restId) endInternal(fire = true) }, 950)
                    } else {
                        // Ring on screen: the classic GO pop, then end.
                        r.goPop = true; r.setRemaining(0)
                        if (h != null) buzz(h, buzzMs)
                        r.animate().scaleX(1.12f).scaleY(1.12f).setDuration(160).withEndAction {
                            r.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                        }.start()
                        r.postDelayed({ if (myId == restId) endInternal(fire = true) }, 950)
                    }
                } else {
                    if (h != null) buzz(h, buzzMs)
                    endInternal(fire = true)
                }
            }
        }.start()
    }

    private fun showDialog(activity: AppCompatActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        appCtx = activity.applicationContext
        // v3.12: a paused rest has no live deadline — the ring opens on
        // the frozen remainder instead.
        val leftMs = if (pausedRemainingMs >= 0) pausedRemainingMs else (endsAtMs - System.currentTimeMillis()).coerceAtLeast(0)
        // v3.13 Bend dialog (mock r4 Screen 3): calm layout — top row,
        // circular photo + text-less ring, name / meta, paused micro,
        // huge countdown, cue, and in-layout pill controls. No dialog
        // buttons; dismissal semantics are unchanged (see below).
        val db = DbHelper(activity)
        dlgDb = db
        val target: SessionExercise? = if (pSessionId > 0)
            db.getSessionExercises(pSessionId).firstOrNull { it.id == pTargetId } else null

        // 1. Top row: micro kind label (left) + plain elapsed (right).
        val kindTv = TextView(activity).apply {
            text = when (pKind) {
                "set" -> "REST · BETWEEN SETS"
                "exercise" -> "REST · BETWEEN EXERCISES"
                else -> "REST"
            }
            textSize = 12f; setTextColor(Theme.textTertiary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.14f
        }
        val elapsedText = if (pSessionId > 0) {
            val sess = db.getSession(pSessionId)
            if (sess != null && !sess.completed)
                WorkoutTimer.formatClock(WorkoutTimer.liveElapsedSec(activity, sess))
            else ""
        } else ""
        val elapsed = TextView(activity).apply {
            text = elapsedText; textSize = 14f; setTextColor(Theme.textSecondary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            visibility = if (elapsedText.isBlank()) View.GONE else View.VISIBLE
        }
        elapsedTv = elapsed
        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(kindTv); addView(elapsed)
        }

        // 2. Circle block: circular photo frame + text-less ring over it.
        val sizeDp = if (activity.isCompactScreen()) 232 else 272
        val sizePx = activity.dp(sizeDp)
        val frame = activity.bendCircleFrame(sizeDp)
        frame.layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
        if (target != null) {
            val asset = PhotoLib.assetForSessionExercise(activity, db, target)
            val bmp = asset?.let { PhotoLib.bitmap(activity, it) }
            if (bmp != null) {
                frame.addView(ImageView(activity).apply {
                    setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                })
            } else {
                frame.addView(BodyMapView(activity, DbHelper.parseMuscles(target.targetMuscles, target.name)).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        activity.dp((sizeDp * 0.40).toInt()), activity.dp((sizeDp * 0.52).toInt()), Gravity.CENTER)
                })
            }
        }
        val r = RestRingView(activity).apply {
            showCenterText = false
            totalMs = ringTotalMs; remainingMs = leftMs
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
        }
        val circle = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, activity.dp(20), 0, 0)
            }
            addView(frame); addView(r)
        }
        circleBox = circle

        // 3. Name: the target's name, else nextLabel stripped of its
        // "Next · " prefix and any " — last time …" suffix.
        var fallback = nextLabel
        if (fallback.startsWith("Next · ")) fallback = fallback.substring("Next · ".length)
        val cutIdx = fallback.indexOf(" — last time")
        if (cutIdx >= 0) fallback = fallback.substring(0, cutIdx)
        fallback = fallback.trim()
        val rawName = target?.name ?: fallback
        val nameTv = activity.bendName(if (rawName.isBlank()) "Rest" else rawName).apply {
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, activity.dp(18), 0, 0) }
        }

        // 4. Meta: next-set position (+ last time) derived from the target.
        var metaText = ""
        if (target != null) {
            try {
                val sets = db.getSets(target.id)
                val last = db.lastPerformedForExercise(target.name, pSessionId)
                when (pKind) {
                    "set" -> {
                        val nextSet = sets.firstOrNull { !it.isDone }
                        val pos = if (nextSet != null) sets.indexOf(nextSet) else -1
                        var base = if (nextSet != null) "Set ${nextSet.setNumber} of ${sets.size}" else "All sets done"
                        val ls = last?.sets?.getOrNull(pos)
                        if (ls != null) base += " · last time ${setText(ls)}"
                        metaText = base
                    }
                    "exercise" -> {
                        var base = if (sets.isNotEmpty()) "Set 1 of ${sets.size}" else ""
                        val fls = last?.sets?.firstOrNull()
                        if (fls != null) base = if (base.isEmpty()) "last time ${setText(fls)}"
                            else base + " · last time ${setText(fls)}"
                        metaText = base
                    }
                }
            } catch (e: Exception) { metaText = "" }
        }
        val metaTv = activity.bendMeta(metaText).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, activity.dp(4), 0, 0) }
            visibility = if (metaText.isBlank()) View.GONE else View.VISIBLE
        }

        // 5. Paused micro (set at show time; resume rebuilds via attach).
        val pausedTv = activity.bendMicro("⏸ PAUSED", Theme.primary).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, activity.dp(6), 0, 0) }
            visibility = if (pausedRemainingMs >= 0) View.VISIBLE else View.GONE
        }

        // 6. Huge countdown (the ticker keeps its text / color current).
        val initialSec = ceil(leftMs / 1000.0).toInt()
        val bigTv = activity.bendHuge(WorkoutTimer.formatClock(initialSec)).apply {
            setTextColor(if (initialSec <= 10) 0xFFD97706.toInt() else Theme.textPrimary)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, activity.dp(8), 0, 0) }
        }
        bigCountTv = bigTv

        // 7. Cue line: the existing rotating cue, restyled quiet + italic.
        val ct = TextView(activity).apply {
            textSize = 12.5f; setTextColor(Theme.textTertiary); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.ITALIC)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, activity.dp(10), 0, 0) }
            visibility = if (cleanCues.isEmpty()) View.GONE else View.VISIBLE
            if (cleanCues.isNotEmpty()) text = "While you rest — ${cleanCues[0]}"
        }

        // 8. Controls: in-layout soft pills (+15s reproduces the old
        // positive-button body exactly, then refreshes the huge text).
        // v3.18 Simple Mode: the +15s control is not built at all (rest
        // length is a Settings choice), and Skip rest becomes one fat
        // pill under the same "Go now" bendControl with the same
        // handler. Full mode builds both controls exactly as before.
        val simple = SimpleMode.isOn(activity)
        val plusControl = if (simple) null else activity.bendControl(activity.bendSoftPill("+15s"), "More rest") {
            if (pausedRemainingMs >= 0) {
                // v3.12: +15s while paused grows the FROZEN remainder
                // (which becomes the ring's new total, mirroring the
                // running arithmetic below); the ticker stays stopped.
                pausedRemainingMs += 15_000
                ringTotalMs = pausedRemainingMs
                r.totalMs = pausedRemainingMs; r.setRemaining(pausedRemainingMs)
                persist()
                notifyListeners()
            } else {
                // Same arithmetic as the pre-v3.7 dialog: remaining + 15s
                // becomes the ring's new total and it restarts full.
                val left = (endsAtMs - System.currentTimeMillis()).coerceAtLeast(0) + 15_000
                ringTotalMs = left
                endsAtMs = System.currentTimeMillis() + left
                r.totalMs = left; r.setRemaining(left)
                startTicker(left)
                notifyListeners()
                persist()
            }
            bigCountTv?.let {
                val s = remainingSec()
                it.text = WorkoutTimer.formatClock(s)
                it.setTextColor(if (s <= 10) 0xFFD97706.toInt() else Theme.textPrimary)
            }
        }
        val skipControl = if (simple) {
            val fatPill = TextView(activity).apply {
                text = "Skip rest"; textSize = 17f; setTextColor(Theme.textPrimary)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Theme.surface); cornerRadius = activity.dp(999).toFloat()
                    setStroke(activity.dp(1), Theme.stroke)
                }
                setPadding(activity.dp(30), activity.dp(15), activity.dp(30), activity.dp(15))
            }
            activity.bendControl(fatPill, "Go now") {
                endInternal(fire = true)
            }
        } else {
            activity.bendControl(activity.bendSoftPill("Skip rest"), "Go now") {
                endInternal(fire = true)
            }
        }
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, activity.dp(16), 0, 0) }
            if (plusControl != null) {
                addView(plusControl)
                addView(View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(activity.dp(26), 1)
                })
            }
            addView(skipControl)
        }

        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(24), activity.dp(20), activity.dp(24), activity.dp(20))
            addView(topRow); addView(circle); addView(nameTv); addView(metaTv)
            addView(pausedTv); addView(bigTv); addView(ct); addView(controls)
        }
        val d = AlertDialog.Builder(activity).setView(col).create()
        // Back / outside-touch: hide the ring ONLY. The engine keeps the
        // rest counting (REST chip); onDone is not fired. Any dismiss —
        // hide, skip, finish — just drops the dialog view references.
        d.setOnDismissListener {
            if (dialog === d) {
                dialog = null; ring = null; cueTv = null
                bigCountTv = null; elapsedTv = null; circleBox = null; dlgDb = null
            }
            // v3.12: the ring is no longer showing — persist that, so a
            // later attach() doesn't resurrect a ring the user hid.
            // Teardown dismissals (rotation destroying the host) are
            // exempt: onPause's persistNow() already captured the
            // truthful flag, and overwriting it here would lose the
            // ring across the recreation.
            val h = host
            if (h == null || (!h.isFinishing && !h.isDestroyed)) persist()
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
        pausedRemainingMs = -1L
        val cb = onDoneCb
        onDoneCb = null; endsAtMs = 0L; ringTotalMs = 0L
        // v3.12: the rest is over — its persisted mirror goes with it.
        pSessionId = -1; pKind = ""; pTargetId = -1; pFromId = -1
        clearPersisted()
        val d = dialog
        dialog = null; ring = null; cueTv = null
        bigCountTv = null; elapsedTv = null; circleBox = null; dlgDb = null
        try { if (d?.isShowing == true) d.dismiss() } catch (e: Exception) {}
        notifyListeners()
        if (fire && cb != null && !fired) { fired = true; cb() }
    }

    /** One-shot buzz, [ms] long (120ms default — the pre-v3.18 tick).
     *  v3.18: natural completion of a set/exercise rest passes 2000ms
     *  (see startTicker's onFinish — the ONLY caller that may pass a
     *  long duration). A long buzz on a device with no vibrator is a
     *  silent no-op. */
    private fun buzz(context: Context, ms: Long = 120) {
        try {
            val vib = if (android.os.Build.VERSION.SDK_INT >= 31) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (ms > 120 && !vib.hasVibrator()) return
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vib.vibrate(ms)
            }
        } catch (e: Exception) { /* haptics are a nicety, never a crash */ }
    }
}

/**
 * v3.7 top-bar REST chip, labeled "REST m:ss", shown next to the
 * elapsed-time chip on the card screen and the hub while a rest is
 * active. It ticks with the RestTimer engine and disappears the moment
 * the rest ends or is skipped; tapping it reopens the full Rest Ring
 * for the same rest. v3.13 Bend reskin: calm soft pill — Theme.warnBg
 * fill with no stroke, Theme.warn ink.
 */
class RestChipView(context: Context) : TextView(context) {
    private val listener: () -> Unit = { refresh() }
    private var host: AppCompatActivity? = null

    init {
        textSize = 12.5f
        // v3.13 Bend reskin: calm soft pill — warnBg fill, no stroke, warn ink.
        setTextColor(Theme.warn)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        background = context.roundedBg(Theme.warnBg, 999)
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
            // v3.12: a frozen rest says so — "REST ⏸ m:ss".
            text = if (RestTimer.isPaused()) "REST ⏸ " + WorkoutTimer.formatClock(RestTimer.remainingSec())
                   else "REST " + WorkoutTimer.formatClock(RestTimer.remainingSec())
        } else {
            visibility = View.GONE
        }
    }
}
