package com.mahesh.workouttracker

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * End-of-workout confetti (v2.7): ~90 lightweight particles (rounded rects +
 * circles, theme colors) burst from the top-center and rain down with
 * gravity, sway and rotation, fading out over ~2.8s. Framework animators
 * only, no dependencies. Non-interactive (clickable=false) so controls
 * beneath stay usable; stops itself at the end of the burst.
 */
class ConfettiView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private class Particle(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        val size: Float, val color: Int,
        val circle: Boolean,
        var rot: Float, val rotSpeed: Float,
        val swayPhase: Float, val swaySpeed: Float,
        val delay: Float
    )

    private val colors = intArrayOf(
        Color.parseColor("#2563EB"), // primary blue
        Color.parseColor("#1D4ED8"), // primary dark
        Color.parseColor("#0D9488"), // teal
        Color.parseColor("#D97706"), // amber
        Color.parseColor("#15803D"), // green
        Color.parseColor("#EC4899"), // pink
        Color.parseColor("#F59E0B")  // warm amber
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var particles: List<Particle> = emptyList()
    private var animator: ValueAnimator? = null
    private var startedAt = 0L
    private var lastFrameAt = 0L

    init {
        isClickable = false
        isFocusable = false
    }

    /** (Re)starts the burst. Safe to call before layout — particles are (re)seeded on size. */
    fun burst() {
        stopBurst()
        if (width <= 0 || height <= 0) { post { burst() }; return }
        seed()
        startedAt = System.currentTimeMillis()
        lastFrameAt = startedAt
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                val now = System.currentTimeMillis()
                val dt = ((now - lastFrameAt).coerceIn(0, 50)) / 1000f
                lastFrameAt = now
                step(dt, (now - startedAt) / 1000f)
                invalidate()
                if (it.animatedFraction >= 1f) stopBurst()
            }
            start()
        }
    }

    private fun stopBurst() {
        animator?.cancel(); animator = null
        particles = emptyList()
        invalidate()
    }

    private fun seed() {
        val rnd = Random(System.nanoTime())
        val cx = width / 2f
        val cy = -10f
        particles = List(COUNT) {
            // Two cones: a tight fountain plus a wider spray.
            val angle = (-Math.PI / 2 + rnd.nextFloat() * 1.5 - 0.75).toFloat()
            val speed = (height * (0.35f + rnd.nextFloat() * 0.55f))
            Particle(
                x = cx + (rnd.nextFloat() - 0.5f) * width * 0.22f,
                y = cy - rnd.nextFloat() * height * 0.08f,
                vx = cos(angle) * speed * 0.55f,
                vy = sin(angle) * speed * 0.45f,
                size = 8f + rnd.nextFloat() * 12f,
                color = colors[rnd.nextInt(colors.size)],
                circle = rnd.nextBoolean(),
                rot = rnd.nextFloat() * 360f,
                rotSpeed = (rnd.nextFloat() - 0.5f) * 540f,
                swayPhase = rnd.nextFloat() * (Math.PI * 2).toFloat(),
                swaySpeed = 2f + rnd.nextFloat() * 3.5f,
                delay = rnd.nextFloat() * 0.35f
            )
        }
    }

    private fun step(dt: Float, elapsed: Float) {
        if (dt <= 0f) return
        val gravity = height * 0.55f        // px/s^2
        val drag = 0.35f                    // light air resistance on vy
        for (p in particles) {
            if (elapsed < p.delay) continue
            p.vy += gravity * dt
            p.vy *= (1f - drag * dt * 0.35f)
            // Terminal fall speed so pieces flutter instead of streaking.
            val maxFall = height * 0.42f
            if (p.vy > maxFall) p.vy = maxFall
            p.x += p.vx * dt + sin((elapsed * p.swaySpeed + p.swayPhase).toDouble()).toFloat() * 34f * dt
            p.y += p.vy * dt
            p.vx *= (1f - 0.6f * dt)
            p.rot += p.rotSpeed * dt
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (particles.isEmpty()) return
        val elapsed = (System.currentTimeMillis() - startedAt) / 1000f
        // Fade everything out over the last 0.9s.
        val fade = when {
            elapsed < DURATION_MS / 1000f - 0.9f -> 1f
            else -> ((DURATION_MS / 1000f - elapsed) / 0.9f).coerceIn(0f, 1f)
        }
        for (p in particles) {
            if (elapsed < p.delay || p.y > height + 30f) continue
            paint.color = p.color
            paint.alpha = (255 * fade).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.translate(p.x, p.y)
            canvas.rotate(p.rot)
            // Fake 3D flip: squash width by the rotation phase.
            val squash = (0.35f + 0.65f * kotlin.math.abs(cos(Math.toRadians(p.rot.toDouble())))).toFloat()
            if (p.circle) {
                canvas.drawCircle(0f, 0f, p.size * 0.42f, paint)
            } else {
                rect.set(-p.size / 2f * squash, -p.size * 0.32f, p.size / 2f * squash, p.size * 0.32f)
                canvas.drawRoundRect(rect, 2.5f, 2.5f, paint)
            }
            canvas.restore()
        }
    }

    override fun onDetachedFromWindow() {
        stopBurst()
        super.onDetachedFromWindow()
    }

    companion object {
        const val COUNT = 90
        const val DURATION_MS = 2800L
    }
}
