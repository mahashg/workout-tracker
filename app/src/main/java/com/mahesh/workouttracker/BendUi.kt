package com.mahesh.workouttracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * v3.13 Bend-style shared pieces (approved mock bend-style-players-mock-r4):
 * the calm system as reusable views — a thin draining ring, a 3dp
 * hairline progress, circular photo frames, soft/hero round controls
 * and the quiet type styles. Colors come from the active Palette
 * (Theme / Palettes.current), so Material and Mist both carry the
 * system. Screens compose these; behavior stays in the screens.
 */

/** Thin ring drawn OVER a circular photo (the Bend circle's edge).
 *  [fraction] is the REMAINING fraction (1 = full ring). Set
 *  [amberUnderSec] >= 0 plus [remainingSec] for the last-seconds
 *  amber rule. Draws nothing but the ring — pair it with
 *  [bendCircleFrame] in a FrameLayout of the same size. */
class BendRingView(context: Context) : View(context) {
    @set:JvmName("setFractionProp")
    var fraction: Float = 1f
        set(value) { field = value; invalidate() }
    var arcColor: Int = Theme.primary
    var trackColor: Int = Theme.surfaceVariant
    var strokeDp: Float = 4f
    var amberUnderSec: Int = -1
    var remainingSec: Int = Int.MAX_VALUE

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val arc = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val strokePx = strokeDp * resources.displayMetrics.density
        trackPaint.strokeWidth = strokePx; trackPaint.color = trackColor
        arcPaint.strokeWidth = strokePx
        val pad = strokePx / 2f + 1f
        arc.set(pad, pad, width - pad, height - pad)
        canvas.drawArc(arc, 0f, 360f, false, trackPaint)
        val f = fraction.coerceIn(0f, 1f)
        arcPaint.color = if (amberUnderSec >= 0 && remainingSec <= amberUnderSec && f < 1f)
            0xFFD97706.toInt() else arcColor
        if (f > 0f) canvas.drawArc(arc, -90f, 360f * f, false, arcPaint)
    }

    fun setFraction(f: Float) { fraction = f; invalidate() }
}

/** The 3dp hairline progress (r4: one progress language everywhere). */
class BendHairlineView(context: Context) : View(context) {
    @set:JvmName("setFractionProp")
    var fraction: Float = 0f
        set(value) { field = value; invalidate() }
    var trackColor: Int = Theme.surfaceVariant
    var fillColor: Int = Theme.primary
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(3))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = height / 2f
        paint.color = trackColor
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
        val w = width * fraction.coerceIn(0f, 1f)
        if (w > 0f) {
            paint.color = fillColor
            canvas.drawRoundRect(0f, 0f, w, height.toFloat(), r, r, paint)
        }
    }

    fun setFraction(f: Float) { fraction = f; invalidate() }
}

/** A square FrameLayout clipped to a circle (the photo's home).
 *  Add MATCH_PARENT ImageViews / a centered BodyMapView as children,
 *  then a same-size [BendRingView] on top. */
fun Context.bendCircleFrame(sizeDp: Int): FrameLayout {
    val f = FrameLayout(this)
    f.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    f.setBackgroundColor(Theme.surfaceVariant)
    f.outlineProvider = object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }
    f.clipToOutline = true
    return f
}

/** Letterspaced micro label (10.5sp, bold, tertiary by default). */
fun Context.bendMicro(text: String, color: Int = Theme.textTertiary): TextView =
    TextView(this).apply {
        this.text = text; textSize = 10.5f; setTextColor(color)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.14f
    }

/** Screen name for the calm top rows (15sp semibold). */
fun Context.bendScreenName(text: String): TextView = makeText(text, 15f, true)

/** The big centered exercise/movement name (24sp bold). */
fun Context.bendName(text: String): TextView = makeText(text, 24f, true).apply {
    gravity = Gravity.CENTER
    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
}

/** Quiet centered meta line (13.5sp secondary). */
fun Context.bendMeta(text: String, color: Int = Theme.textSecondary): TextView =
    makeText(text, 13.5f, false, color).apply { gravity = Gravity.CENTER }

/** The ONE italic cue line (13.5sp italic secondary, centered). */
fun Context.bendCue(text: String): TextView = TextView(this).apply {
    this.text = text; textSize = 13.5f; setTextColor(Theme.textSecondary)
    typeface = Typeface.create("sans-serif", Typeface.ITALIC)
    gravity = Gravity.CENTER
    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
}

/** Plain "▶ Video" text line (13sp bold primary, centered). */
fun Context.bendWatchLine(): TextView = TextView(this).apply {
    text = "▶ Video"; textSize = 13f; setTextColor(Theme.primary)
    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    gravity = Gravity.CENTER
    setPadding(dp(12), dp(6), dp(12), dp(6))
}

/** The huge light-weight timer (64sp sans-serif-light, tabular). */
fun Context.bendHuge(text: String = ""): TextView = TextView(this).apply {
    this.text = text; textSize = 64f; setTextColor(Theme.textPrimary)
    typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    setFontFeatureSettings("tnum")
    gravity = Gravity.CENTER
    maxLines = 1
}

/** Soft round control (stepper-tint fill, deep-primary glyph). */
fun Context.bendSoftCircle(glyph: String, sizeDp: Int = 52, glyphSp: Float = 18f): TextView =
    TextView(this).apply {
        text = glyph; textSize = glyphSp; gravity = Gravity.CENTER
        setTextColor(Theme.primaryDark)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(Palettes.current.stepperBg)
        }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

/** The one hero disc per screen (solid fill, white glyph). */
fun Context.bendHeroCircle(glyph: String, fill: Int, sizeDp: Int = 70, glyphSp: Float = 25f): TextView =
    TextView(this).apply {
        text = glyph; textSize = glyphSp; gravity = Gravity.CENTER
        setTextColor(android.graphics.Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

/** A round control + its micro label, as one tappable column. */
fun Context.bendControl(circle: TextView, label: String, labelSp: Float = 10.5f, onClick: () -> Unit): LinearLayout {
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        isClickable = true
        setOnClickListener { onClick() }
    }
    circle.isClickable = false
    col.addView(circle)
    col.addView(TextView(this).apply {
        text = label; textSize = labelSp; setTextColor(Theme.textTertiary)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(6), 0, 0) }
    })
    return col
}

/** Borderless white panel (radius 24, 18dp padding) — the r4 surface. */
fun Context.bendPanel(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = GradientDrawable().apply {
        setColor(Palettes.current.sheetRow); cornerRadius = dp(24).toFloat()
    }
    setPadding(dp(18), dp(18), dp(18), dp(18))
}

/** Soft auto-width pill (rest-screen controls: "+15s", "Skip rest"). */
fun Context.bendSoftPill(text: String): TextView = TextView(this).apply {
    this.text = text; textSize = 14f; gravity = Gravity.CENTER
    setTextColor(Theme.primaryDark)
    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    background = GradientDrawable().apply {
        setColor(Palettes.current.stepperBg); cornerRadius = dp(999).toFloat()
    }
    setPadding(dp(20), dp(13), dp(20), dp(13))
}

/** Quiet text action ("Skip exercise", "Do later" — 13.5sp semibold). */
fun Context.bendTextAction(text: String, onClick: () -> Unit): TextView =
    TextView(this).apply {
        this.text = text; textSize = 13.5f; setTextColor(Theme.textSecondary)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(8), dp(10), dp(8))
        isClickable = true
        setOnClickListener { onClick() }
    }
