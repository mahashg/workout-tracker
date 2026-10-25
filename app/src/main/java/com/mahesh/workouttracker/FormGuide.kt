package com.mahesh.workouttracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * v3.19 Form Guide (approved mock form-guide-mock-r2): a read-only,
 * per-exercise visual guide patterned on a form-tips reel — a looping
 * two-photo "GIF" on the guide's cover, then one cue per step screen
 * with a yellow guide drawn on the photo, a per-step ✗ don't strip
 * where a precaution clearly belongs to that step, and a final
 * muscle + Do / Don't recap.
 *
 * Data lives in the bundled catalog JSON as a `formGuide` field on the
 * entry (runtime-read through [JsonSeed], exactly like `shortUrl` /
 * `hcType` / `photo` — so an upgraded install sees the guides the
 * moment the new APK lands; no DB column, no seed gate). Content is
 * built ONLY from the entry's own stored text: step "do" lines are its
 * cues verbatim, "dont" lines are its precautions verbatim, and the
 * annotations were placed by viewing each exercise's actual catalog
 * photos and QA'd as composites (a step whose annotation couldn't be
 * placed confidently ships caption-only).
 *
 * Scope (Mahesh, 2026-10-25): GYM mains only — entries in a part
 * file's `exercises` array with equipment != Bodyweight and a photo.
 * 119 exercises carry a guide; warm-ups, stretches, the Movie deck and
 * Circuits have none, and no guide UI renders where no guide exists.
 */
data class GuideAnno(
    val type: String,               // "line" | "polyline" | "arc" | "bar" | "circle"
    val points: List<Float>,        // normalized 0–1 against the full photo
    val dashed: Boolean = false,    // line: reference level
    val arrow: Boolean = false      // arc: arrowhead at the end point
)

data class GuideStep(
    val photo: Int,                 // 1 = start frame, 2 = finish frame
    val doText: String,             // a stored cue, verbatim
    val dont: String?,              // a stored precaution, verbatim, or null
    val anno: GuideAnno?
)

data class FormGuide(
    val feel: String,               // muscle focus line for the final screen
    val steps: List<GuideStep>
) {
    companion object {
        /** Parses the JSON `formGuide` object; null when absent or
         *  malformed (a bad guide must never crash a card — the entry
         *  simply behaves as v3.18, with no guide UI). */
        fun parse(o: JSONObject?): FormGuide? {
            if (o == null) return null
            val arr = o.optJSONArray("steps") ?: return null
            val steps = mutableListOf<GuideStep>()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: return null
                val doText = s.optString("do").trim()
                if (doText.isEmpty()) return null
                val annoObj = s.optJSONObject("anno")
                val anno = if (annoObj == null) null else {
                    val pts = mutableListOf<Float>()
                    val pa = annoObj.optJSONArray("points")
                    if (pa != null) for (j in 0 until pa.length()) pts.add(pa.optDouble(j, 0.0).toFloat())
                    val type = annoObj.optString("type")
                    val valid = when (type) {
                        "line", "bar" -> pts.size == 4
                        "arc" -> pts.size == 6
                        "circle" -> pts.size == 3
                        "polyline" -> pts.size >= 6 && pts.size % 2 == 0
                        else -> false
                    }
                    if (!valid) null else GuideAnno(
                        type, pts,
                        dashed = annoObj.optBoolean("dashed", false),
                        arrow = annoObj.optBoolean("arrow", false)
                    )
                }
                steps.add(
                    GuideStep(
                        photo = if (s.optInt("photo", 1) == 2) 2 else 1,
                        doText = doText,
                        dont = s.optString("dont").trim().ifEmpty { null },
                        anno = anno
                    )
                )
            }
            if (steps.isEmpty()) return null
            return FormGuide(o.optString("feel").trim(), steps)
        }
    }
}

/**
 * Draws one [GuideAnno] over its photo. The view must exactly overlay
 * the displayed photo (the guide screens size the photo container to
 * the bitmap's aspect ratio and use FIT_XY, so normalized coordinates
 * map linearly onto this view — the same mapping the PIL QA composites
 * used). Yellow #FFE81A, ~5dp stroke, round caps/joins, per mock r2.
 */
class AnnoView(context: Context, private val anno: GuideAnno) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE81A")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f || h == 0f) return
        paint.strokeWidth = 5f * resources.displayMetrics.density
        val p = anno.points
        fun x(i: Int) = p[i] * w
        fun y(i: Int) = p[i] * h
        when (anno.type) {
            "line" -> {
                paint.pathEffect = if (anno.dashed)
                    DashPathEffect(floatArrayOf(paint.strokeWidth * 2f, paint.strokeWidth * 1.4f), 0f) else null
                canvas.drawLine(x(0), y(1), x(2), y(3), paint)
                paint.pathEffect = null
            }
            "bar" -> {
                val x1 = x(0); val y1 = y(1); val x2 = x(2); val y2 = y(3)
                canvas.drawLine(x1, y1, x2, y2, paint)
                // End ticks: perpendicular, 0.05 of height each side
                // of the bar's ends (mock's measure-bar look).
                val dx = x2 - x1; val dy = y2 - y1
                val len = hypot(dx, dy)
                if (len > 0f) {
                    val px = -dy / len; val py = dx / len
                    val t = 0.05f * h
                    canvas.drawLine(x1 - px * t, y1 - py * t, x1 + px * t, y1 + py * t, paint)
                    canvas.drawLine(x2 - px * t, y2 - py * t, x2 + px * t, y2 + py * t, paint)
                }
            }
            "polyline" -> {
                val path = Path()
                path.moveTo(x(0), y(1))
                var i = 2
                while (i + 1 < p.size) { path.lineTo(x(i), y(i + 1)); i += 2 }
                canvas.drawPath(path, paint)
            }
            "arc" -> {
                val x1 = x(0); val y1 = y(1); val cx = x(2); val cy = y(3); val x2 = x(4); val y2 = y(5)
                val path = Path()
                path.moveTo(x1, y1)
                path.quadTo(cx, cy, x2, y2)
                canvas.drawPath(path, paint)
                if (anno.arrow) {
                    // Arrowhead at the end, along the curve's exit
                    // tangent (end − control point).
                    val ang = atan2(y2 - cy, x2 - cx)
                    val ah = paint.strokeWidth * 3.2f
                    val spread = 0.42f
                    val lx = x2 - ah * cos(ang - spread); val ly = y2 - ah * sin(ang - spread)
                    val rx = x2 - ah * cos(ang + spread); val ry = y2 - ah * sin(ang + spread)
                    canvas.drawLine(x2, y2, lx.toFloat(), ly.toFloat(), paint)
                    canvas.drawLine(x2, y2, rx.toFloat(), ry.toFloat(), paint)
                }
            }
            "circle" -> {
                canvas.drawCircle(x(0), y(1), p[2] * w, paint)
            }
        }
    }
}

/** The mock r2 "GIF" chip: small dark pill, white bold caps, shown
 *  only while a two-frame photo loop is actually animating. */
fun Context.gifChip(): TextView = TextView(this).apply {
    text = "GIF"; textSize = 11f; setTextColor(Color.WHITE)
    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    letterSpacing = 0.06f
    background = roundedBg(0x99000000.toInt(), 999)
    setPadding(dp(9), dp(4), dp(9), dp(4))
}
