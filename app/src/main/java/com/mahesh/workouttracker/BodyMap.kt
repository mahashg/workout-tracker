package com.mahesh.workouttracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class BodyMapView(context: Context, private val muscles: Set<String>, private val showLabels: Boolean = false) : View(context) {
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CBD5E1"); style = Paint.Style.FILL }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#94A3B8"); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F59E0B"); style = Paint.Style.FILL }
    private val hl2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0284C7"); style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#64748B"); textSize = 20f; textAlign = Paint.Align.CENTER }

    private fun has(k: String) = muscles.contains(k)
    private fun paintFor(k: String) = if (has(k)) hlPaint else bodyPaint

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w < 10 || h < 10) return
        // Two figures: front left, back right
        drawFigure(canvas, w*0.08f, h*0.06f, w*0.36f, h*0.82f, front = true)
        drawFigure(canvas, w*0.56f, h*0.06f, w*0.36f, h*0.82f, front = false)
        if (showLabels) {
            canvas.drawText("FRONT", w*0.26f, h*0.97f, labelPaint)
            canvas.drawText("BACK", w*0.74f, h*0.97f, labelPaint)
        } else {
            canvas.drawText("F", w*0.26f, h*0.98f, labelPaint)
            canvas.drawText("B", w*0.74f, h*0.98f, labelPaint)
        }
    }

    private fun drawFigure(canvas: Canvas, x: Float, y: Float, fw: Float, fh: Float, front: Boolean) {
        val cx = x + fw/2f
        val headR = fw*0.16f
        val headCy = y + headR + fh*0.01f
        // head
        canvas.drawCircle(cx, headCy, headR, bodyPaint)
        canvas.drawCircle(cx, headCy, headR, outlinePaint)
        val neckY = headCy + headR
        val shoulderY = neckY + fh*0.05f
        val torsoTop = shoulderY
        val torsoBottom = y + fh*0.52f
        val torsoLeft = x + fw*0.22f
        val torsoRight = x + fw*0.78f
        // torso base
        val torso = RectF(torsoLeft, torsoTop, torsoRight, torsoBottom)
        canvas.drawRoundRect(torso, fw*0.08f, fw*0.08f, bodyPaint)
        // traps (back/top)
        if (has("traps")) {
            val trap = RectF(cx-fw*0.24f, torsoTop, cx+fw*0.24f, torsoTop+fh*0.09f)
            canvas.drawRoundRect(trap, 6f, 6f, hlPaint)
        }
        // chest front / back back
        if (front && has("chest")) {
            canvas.drawRoundRect(RectF(torsoLeft+fw*0.03f, torsoTop+fh*0.03f, torsoRight-fw*0.03f, torsoTop+fh*0.20f), 8f, 8f, hlPaint)
        }
        if (!front && has("back")) {
            canvas.drawRoundRect(RectF(torsoLeft+fw*0.03f, torsoTop+fh*0.04f, torsoRight-fw*0.03f, torsoBottom-fh*0.06f), 8f, 8f, hlPaint)
        }
        // core front / lower back hint
        if (front && has("core")) {
            canvas.drawRoundRect(RectF(cx-fw*0.16f, torsoTop+fh*0.22f, cx+fw*0.16f, torsoBottom-fh*0.03f), 8f, 8f, hl2Paint)
        }
        // arms
        val armW = fw*0.15f
        val armTop = shoulderY + fh*0.01f
        val armBottom = torsoBottom + fh*0.04f
        // left/right arms
        for (side in listOf(-1, 1)) {
            val ax = if (side<0) torsoLeft-armW-fw*0.03f else torsoRight+fw*0.03f
            val upper = RectF(ax, armTop, ax+armW, armTop+fh*0.20f)
            // shoulders caps
            canvas.drawCircle(if(side<0) torsoLeft else torsoRight, shoulderY+fh*0.02f, fw*0.11f, paintFor("shoulders"))
            if (front) {
                canvas.drawRoundRect(upper, 8f, 8f, paintFor("biceps"))
                if (has("triceps")) {
                    // hint triceps on outer edge front as thin strip
                    canvas.drawRoundRect(RectF(upper.left, upper.top, upper.left+armW*0.35f, upper.bottom), 6f, 6f, hl2Paint)
                }
            } else {
                canvas.drawRoundRect(upper, 8f, 8f, paintFor("triceps"))
            }
            val fore = RectF(ax, armTop+fh*0.21f, ax+armW, armBottom)
            canvas.drawRoundRect(fore, 8f, 8f, bodyPaint)
        }
        // hips/glutes
        val hip = RectF(torsoLeft, torsoBottom-fh*0.02f, torsoRight, torsoBottom+fh*0.09f)
        canvas.drawRoundRect(hip, 8f, 8f, if (!front && has("glutes")) hlPaint else bodyPaint)
        // legs
        val legTop = torsoBottom+fh*0.08f
        val legBottom = y+fh
        val legW = fw*0.20f
        for (side in listOf(-1,1)) {
            val lx = if (side<0) cx-legW-fw*0.04f else cx+fw*0.04f
            val thigh = RectF(lx, legTop, lx+legW, legTop+fh*0.22f)
            val calf = RectF(lx+legW*0.12f, legTop+fh*0.24f, lx+legW*0.88f, legBottom)
            if (front) {
                canvas.drawRoundRect(thigh, 8f, 8f, paintFor("quads"))
            } else {
                canvas.drawRoundRect(thigh, 8f, 8f, paintFor("hamstrings"))
            }
            canvas.drawRoundRect(calf, 8f, 8f, paintFor("calves"))
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec)==MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else (w*1.25f).toInt()
        setMeasuredDimension(w, h)
    }
}

fun musclesLabel(muscles: Set<String>): String {
    if (muscles.isEmpty()) return "Full body"
    val names = mapOf("shoulders" to "Shoulders","traps" to "Traps","chest" to "Chest","triceps" to "Triceps","biceps" to "Biceps","back" to "Back","core" to "Core","quads" to "Quads","hamstrings" to "Hamstrings","glutes" to "Glutes","calves" to "Calves")
    return muscles.map { names[it] ?: it.replaceFirstChar { c->c.uppercase() } }.joinToString(", ")
}
