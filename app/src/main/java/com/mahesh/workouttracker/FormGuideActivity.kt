package com.mahesh.workouttracker

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
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
 * v3.19 Form Guide viewer (approved mock form-guide-mock-r2, screens
 * 2–5). Opened from the gym exercise card's "📖 How to do it" line and
 * the Library detail's FORM GUIDE row — nowhere else (no Movie deck,
 * no Circuits). Strictly read-only: it touches no session, set, rest
 * or timer state, so opening and closing it mid-workout returns to the
 * card exactly as it was (the card's own onPause/onResume lifecycle
 * handles its loopers and chips, as with any other screen on top).
 *
 * Pages: 0 = cover (the two-photo GIF loop + what's-inside strips +
 * Start steps), 1..N = one stored cue per screen (photo, STEP chip,
 * ✓ caption, yellow annotation, the step's own ✗ don't strip when a
 * precaution clearly belongs to it, dots, Back/Next), N+1 = final
 * (body-part logo + "You should feel it here" + the full Do / Don't
 * recap from the entry's cues and precautions). The viewer renders
 * identically in Simple and full mode (mock: the reading surface keeps
 * the chunky Simple-Mode control sizes in both).
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
        render()
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
        if (page > 0) root.addView(navRow(g))
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

    private fun stepChip(label: String): TextView = TextView(this).apply {
        text = label; textSize = 11f; setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        background = roundedBg(0x8C000000.toInt(), 999)
        setPadding(dp(9), dp(4), dp(9), dp(4))
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(dp(10), dp(10), 0, 0) }
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

    private fun navRow(g: FormGuide): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(10), 0, 0) }
        }
        fun pill(label: String, primary: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
            text = label; textSize = 16f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            if (primary) { setTextColor(Color.WHITE); background = roundedBg(Theme.primary, 999) }
            else { setTextColor(Theme.primaryDark); background = roundedBg(Theme.surfaceVariant, 999) }
            setPadding(0, dp(14), 0, dp(14))
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val isFinal = page > g.steps.size
        row.addView(pill("‹ Back", false) { page--; render() })
        if (isFinal) {
            row.addView(pill("✓ Got it", true) { finish() }.apply {
                (layoutParams as LinearLayout.LayoutParams).setMargins(dp(10), 0, 0, 0)
            })
        } else {
            row.addView(pill("Next ›", true) { page++; render() }.apply {
                (layoutParams as LinearLayout.LayoutParams).setMargins(dp(10), 0, 0, 0)
            })
        }
        return row
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
        col.addView(photoFrame(base, null, animate = false) { frame ->
            frame.addView(stepChip("STEP ${idx + 1} OF ${g.steps.size}"))
            val cap = captionText("✓ ${step.doText}")
            cap.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { setMargins(dp(12), dp(40), dp(12), 0) }
            frame.addView(cap)
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
    }

    private fun renderFinal(col: LinearLayout, e: JsonSeed.Entry, g: FormGuide) {
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
