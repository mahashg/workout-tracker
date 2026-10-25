package com.mahesh.workouttracker

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Form Guide viewer (v3.19, approved mock form-guide-mock-r2;
 * v3.20 interaction pass, approved mock form-guide-mock-r3). Opened
 * from the gym exercise card's "📖 How to do it" line and the Library
 * detail's FORM GUIDE row — nowhere else (no Movie deck, no
 * Circuits). Strictly read-only: it touches no session, set, rest or
 * timer state itself. (v3.20: the CARD auto-pauses the workout around
 * the trip — see WorkoutTimer.autoPause — the guide stays oblivious.)
 *
 * Pages: 0 = cover (the two-photo GIF loop + what's-inside strips +
 * Start steps), 1..N = one stored cue per screen, N+1 = final
 * (body-part logo + "You should feel it here" + the full Do / Don't
 * recap from the entry's cues and precautions).
 *
 * v3.20 navigation: step pages move by horizontal SWIPE only (no
 * Back/Next buttons; the final screen keeps its single "✓ Got it").
 * A step page is a header band ABOVE the photo — "STEP n OF N" chip
 * (left) + ✕ (right), then the ✓ caption in bold on the page surface —
 * so the photo carries ONLY the yellow annotation, never text. Under
 * the photo: the step's ✗ don't strip (when a precaution clearly
 * belongs to it), the progress dots, and a quiet "‹ Swipe ›" hint.
 * The viewer renders identically in Simple and full mode.
 *
 * Photo/annotation alignment: the photo container is sized to the
 * bitmap's own aspect ratio and the image fills it (FIT_XY with a
 * matching aspect = no crop, no distortion), so the annotation view
 * laid over it shares the photo's exact coordinate space — the same
 * normalized space the QA composites were checked in.
 */
class FormGuideActivity : AppCompatActivity() {
    private var entry: JsonSeed.Entry? = null
    private var guide: FormGuide? = null
    private var partId: String = ""
    private var page = 0
    private var looper: PhotoLooper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("guideEntryId") ?: ""
        val e = JsonSeed.entryById(this, id)
        if (e == null || e.formGuide == null) { finish(); return }
        entry = e
        guide = e.formGuide
        partId = JsonSeed.partIdForEntry(this, id) ?: ""
        // v3.20: rotation mid-guide keeps the current page.
        page = savedInstanceState?.getInt("fg_page", 0) ?: 0
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("fg_page", page)
    }

    // ---- v3.20: swipe navigation between step pages ----
    // Watched at dispatch level so the gesture works anywhere on the
    // step body (caption band, photo, strips) without consuming taps
    // or fighting the ScrollView's vertical scrolling: a page turn
    // needs >= 48dp horizontal travel, clearly horizontal
    // (|dx| >= 1.6 x |dy|), measured down-to-up.
    private var swipeDownX = 0f
    private var swipeDownY = 0f

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { swipeDownX = ev.x; swipeDownY = ev.y }
            android.view.MotionEvent.ACTION_UP -> {
                val dx = ev.x - swipeDownX
                val dy = ev.y - swipeDownY
                if (kotlin.math.abs(dx) >= dp(48) && kotlin.math.abs(dx) >= 1.6f * kotlin.math.abs(dy)) {
                    onSwipeTurn(if (dx < 0) -1 else 1)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** dir -1 = swiped left (next), +1 = swiped right (previous).
     *  The cover is not a step (its Start button leads in); step 1
     *  swiping right does nothing; the final page swiping left does
     *  nothing — ✓ Got it and ✕ are its exits. */
    private fun onSwipeTurn(dir: Int) {
        val g = guide ?: return
        if (page == 0) return
        if (dir < 0) {
            if (page <= g.steps.size) { page++; render() }
        } else {
            if (page > 1) { page--; render() }
        }
    }

    override fun onResume() { super.onResume(); if (page == 0) looper?.start() }
    override fun onPause() { super.onPause(); looper?.stop() }
    override fun onDestroy() { super.onDestroy(); looper?.stop() }

    private fun cues(): List<String> =
        (entry?.cues ?: "").split(";").map { it.trim() }.filter { it.isNotEmpty() }

    private fun precautions(): List<String> =
        (entry?.precautions ?: "").split(";").map { it.trim() }.filter { it.isNotEmpty() }

    private fun render() {
        val e = entry ?: return
        val g = guide ?: return
        looper?.stop(); looper = null
        val root = fitRoot(); setContentView(root)
        root.addView(topBar("Form Guide", e.name))

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        scroll.addView(col)
        root.addView(scroll)

        when {
            page == 0 -> renderCover(col, e, g)
            page <= g.steps.size -> renderStep(col, e, g, page - 1)
            else -> renderFinal(col, e, g)
        }
    }

    // ---------- shared pieces ----------

    /** The photo at its own aspect ratio, corners clipped to 18dp,
     *  with caller-added overlays. [animate] runs the two-frame GIF
     *  loop (cover only) and shows the GIF chip. */
    private fun photoFrame(base: Bitmap, second: Bitmap?, animate: Boolean, addOverlays: (FrameLayout) -> Unit): FrameLayout {
        val frame = FrameLayout(this)
        val widthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt() -
            2 * (if (isCompactScreen()) 14 else 20)
        val heightDp = (widthDp * base.height.toFloat() / base.width.toFloat()).toInt().coerceAtLeast(120)
        frame.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)).apply { setMargins(0, dp(12), 0, 0) }
        frame.setBackgroundColor(Color.parseColor("#111111"))
        frame.roundedClip(18)
        frame.addView(ImageView(this).apply {
            setImageBitmap(base); scaleType = ImageView.ScaleType.FIT_XY
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        })
        if (second != null && animate) {
            val iv2 = ImageView(this).apply {
                setImageBitmap(second); scaleType = ImageView.ScaleType.FIT_XY; alpha = 0f
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
            frame.addView(iv2)
            looper = PhotoLooper(iv2)
            if (page == 0) looper?.start()
            // No GIF chip on the cover — mock r2 screen 2 shows the bare
            // loop + name; the chip lives on the card and Library band.
        }
        addOverlays(frame)
        return frame
    }

    private fun captionText(text: String): TextView = TextView(this).apply {
        this.text = text; textSize = 16.5f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), 0xD9000000.toInt())
        setLineSpacing(0f, 1.3f)
    }

    /** v3.20 header band (mock r3): the step chip on the page surface
     *  at the left, the ✕ close disc at the right — a weighted spacer
     *  between them is the row's only weighted child, so neither the
     *  chip nor the disc can be starved. [chipLabel] null (the final
     *  screen) leaves just the ✕. */
    private fun headerRow(chipLabel: String?): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, 0) }
        }
        if (chipLabel != null) {
            row.addView(TextView(this).apply {
                text = chipLabel; textSize = 11f; setTextColor(Theme.primaryDark)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = roundedBg(Theme.surfaceVariant, 999)
                setPadding(dp(10), dp(5), dp(10), dp(5))
            })
        }
        row.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        val x = bendSoftCircle("✕", 34, 15f)
        x.isClickable = true
        x.setOnClickListener { finish() }
        row.addView(x)
        return row
    }

    /** v3.20: the step's ✓ Do caption — bold ~18sp on the page surface
     *  ABOVE the photo (mock r3), green tick, never overlaid. */
    private fun stepCaption(doText: String): TextView {
        val sb = SpannableStringBuilder("✓ $doText")
        sb.setSpan(ForegroundColorSpan(Color.parseColor("#1D7A35")), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return TextView(this).apply {
            text = sb; textSize = 18f; setTextColor(Theme.textPrimary)
            setLineSpacing(0f, 1.3f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(10), 0, 0) }
        }
    }

    private fun strip(text: SpannableStringBuilder, bg: String, stroke: String, ink: String): TextView = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(Color.parseColor(ink))
        setLineSpacing(0f, 1.4f)
        background = roundedBg(Color.parseColor(bg), 14, Color.parseColor(stroke))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(10), 0, 0) }
    }

    private fun boldLead(lead: String, rest: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder(lead + rest)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, lead.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun bigCta(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label; textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        background = roundedBg(Theme.primary, 999)
        setPadding(0, dp(16), 0, dp(16))
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(12), 0, 0) }
    }

    private fun dots(count: Int, current: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(12), 0, 0) }
        }
        for (i in 0 until count) {
            val on = i == current
            row.addView(View(this).apply {
                background = roundedBg(if (on) Theme.primary else Color.parseColor("#D0C6E8"), 999)
                layoutParams = LinearLayout.LayoutParams(dp(if (on) 22 else 8), dp(8)).apply { setMargins(dp(3), 0, dp(3), 0) }
            })
        }
        return row
    }

    // ---------- pages ----------

    private fun renderCover(col: LinearLayout, e: JsonSeed.Entry, g: FormGuide) {
        val bmp = PhotoLib.bitmap(this, e.photo) ?: run { finish(); return }
        val bmp2 = PhotoLib.secondBitmap(this, e.photo)
        col.addView(photoFrame(bmp, bmp2, animate = true) { frame ->
            val name = captionText(e.name)
            name.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { setMargins(dp(12), dp(10), dp(12), 0) }
            frame.addView(name)
        })
        val n = g.steps.size
        col.addView(strip(
            boldLead("✓  $n step${if (n == 1) "" else "s"}", " — one cue at a time, with the guide drawn on the photo"),
            "#EAF7EC", "#BFE3C5", "#14532D"
        ))
        val attached = g.steps.count { it.dont != null }
        val total = precautions().size
        col.addView(strip(
            if (attached > 0) boldLead("✗  $attached common mistake${if (attached == 1) "" else "s"}", " to avoid, shown on the step where they happen")
            else boldLead("✗  $total common mistake${if (total == 1) "" else "s"}", " to avoid — all on the last screen"),
            "#FDECEA", "#F3C1BC", "#7A1F1A"
        ))
        col.addView(bigCta("Start steps ›") { page = 1; render() })
    }

    private fun renderStep(col: LinearLayout, e: JsonSeed.Entry, g: FormGuide, idx: Int) {
        val step = g.steps[idx]
        val bmp1 = PhotoLib.bitmap(this, e.photo) ?: run { finish(); return }
        val bmp2 = PhotoLib.secondBitmap(this, e.photo)
        val base = if (step.photo == 2 && bmp2 != null) bmp2 else bmp1
        // v3.20 (mock r3): header band first — chip + ✕, then the ✓
        // caption on the surface — and the photo below carries ONLY
        // the yellow annotation. No text touches the photo.
        col.addView(headerRow("STEP ${idx + 1} OF ${g.steps.size}"))
        col.addView(stepCaption(step.doText))
        col.addView(photoFrame(base, null, animate = false) { frame ->
            if (step.anno != null) {
                frame.addView(AnnoView(this, step.anno).apply {
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                })
            }
        })
        if (step.dont != null) {
            col.addView(strip(SpannableStringBuilder("✗  ${step.dont}"), "#FDECEA", "#F3C1BC", "#7A1F1A"))
        }
        col.addView(dots(g.steps.size, idx))
        col.addView(TextView(this).apply {
            text = "‹  Swipe  ›"; textSize = 13.5f; setTextColor(Theme.primary); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), 0, 0) }
        })
    }

    private fun renderFinal(col: LinearLayout, e: JsonSeed.Entry, g: FormGuide) {
        // v3.20: the final screen's header row carries just the ✕
        // (mock r3 shows the row here too); its one button, "✓ Got
        // it", closes the guide from the bottom of the recap.
        col.addView(headerRow(null))
        // Muscle card: fixed 104dp logo + the text column as the row's
        // ONLY weighted child (geometry audit: nothing can starve it).
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(Theme.surface, 18)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(12), 0, 0) }
        }
        val logo = PartLogos.bitmap(this, partId)
        if (logo != null) {
            val logoFrame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(104), dp(104))
                roundedClip(12)
            }
            logoFrame.addView(ImageView(this).apply {
                setImageBitmap(logo); scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
            card.addView(logoFrame)
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(if (logo != null) dp(14) else 0, 0, 0, 0) }
        }
        textCol.addView(makeText("You should feel it here", 16f, true).apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        })
        val feelText = g.feel.ifBlank { musclesLabel(DbHelper.parseMuscles(e.targetMuscles, e.name)) }
        textCol.addView(makeText(feelText, 13.5f, false, Theme.textSecondary).apply {
            setLineSpacing(0f, 1.45f)
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(4), 0, 0)
        })
        card.addView(textCol)
        col.addView(card)

        // Do / Don't recap: side by side only while each column keeps
        // >= 150dp; stacked vertically below that (geometry audit).
        val contentDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt() -
            2 * (if (isCompactScreen()) 14 else 20)
        val sideBySide = (contentDp - 10) / 2 >= 150
        val dd = LinearLayout(this).apply {
            orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(10), 0, 0) }
        }
        dd.addView(ddColumn("✓  DO", cues(), "#EAF7EC", "#BFE3C5", "#14532D", first = true, sideBySide = sideBySide))
        dd.addView(ddColumn("✗  DON'T", precautions(), "#FDECEA", "#F3C1BC", "#7A1F1A", first = false, sideBySide = sideBySide))
        col.addView(dd)
        // v3.20: the ONLY button on the step pages (mock r3).
        col.addView(bigCta("✓ Got it") { finish() })
    }

    private fun ddColumn(title: String, items: List<String>, bg: String, stroke: String, ink: String, first: Boolean, sideBySide: Boolean): LinearLayout {
        val colv = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Color.parseColor(bg), 18, Color.parseColor(stroke))
            setPadding(dp(13), dp(12), dp(13), dp(12))
            layoutParams = if (sideBySide)
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (!first) setMargins(dp(10), 0, 0, 0) }
            else
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { if (!first) setMargins(0, dp(10), 0, 0) }
        }
        colv.addView(TextView(this).apply {
            text = title; textSize = 14.5f; setTextColor(Color.parseColor(ink))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        items.forEachIndexed { i, item ->
            if (i > 0) colv.addView(View(this).apply {
                setBackgroundColor(0x0F000000)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
            colv.addView(TextView(this).apply {
                text = item; textSize = 13f; setTextColor(Theme.textPrimary)
                setLineSpacing(0f, 1.4f)
                setPadding(0, dp(4), 0, dp(4))
            })
        }
        return colv
    }

    companion object {
        fun intent(context: android.content.Context, entryId: String): Intent =
            Intent(context, FormGuideActivity::class.java).putExtra("guideEntryId", entryId)
    }
}
