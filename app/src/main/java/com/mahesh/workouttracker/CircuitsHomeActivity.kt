package com.mahesh.workouttracker

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** v3.11 Circuits home (approved mock v3.11-circuits-mock-r1,
 *  Screen 2): the preset browser behind the Home screen's Circuits
 *  door. Header + format chips + the "All motions A–Z" row stay
 *  fixed; only the banded preset list below re-renders when a chip
 *  is picked. The list is a ScrollView — this screen is a declared
 *  list context (the presets + motions library are long, unlike the
 *  app's paginated part pages). A row tap opens the circuit detail
 *  screen; the detail's Start button is what begins a run. */
class CircuitsHomeActivity : AppCompatActivity() {
    private var circuits: List<CircuitsData.Circuit> = emptyList()
    private var chipDefs: List<Pair<String, (CircuitsData.Circuit) -> Boolean>> = emptyList()
    private var selectedChip = 0
    private var created = false
    private var listBox: LinearLayout? = null
    private val chipViews = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        created = true
        render()
    }

    override fun onResume() {
        super.onResume()
        if (created) render()
    }

    private fun render() {
        circuits = CircuitsData.load(this)
        chipDefs = CircuitsData.chips()
        if (selectedChip !in chipDefs.indices) selectedChip = 0
        val motionsCount = CircuitsData.motions(this).size
        val root = tabScaffold("Home")
        val compact = isCompactScreen()
        val title = makeText("⚡ Circuits", 21f, true)
        (title.layoutParams as LinearLayout.LayoutParams).apply { height = dp(if (compact) 32 else 38); setMargins(0, dp(2), 0, 0) }
        title.gravity = Gravity.CENTER_VERTICAL
        root.addView(title)
        val sub = caption("${circuits.size} presets · $motionsCount motions in the library")
        (sub.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        root.addView(sub)

        // ---- Format chips: one horizontally scrolling row. The pill
        // itself is 40dp tall inside the 48dp row so the touch area
        // clears 48dp even though the visible pill stays slim. ----
        chipViews.clear()
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        for ((i, def) in chipDefs.withIndex()) {
            val chip = TextView(this).apply {
                text = def.first; textSize = 12.5f; gravity = Gravity.CENTER; maxLines = 1
                minHeight = dp(40)
                setPadding(dp(12), 0, dp(12), 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp(7), 0) }
                isClickable = true
                setOnClickListener {
                    selectedChip = i
                    styleChips()
                    renderList()
                }
            }
            chipViews.add(chip)
            chipRow.addView(chip)
        }
        styleChips()
        val chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { setMargins(0, dp(8), 0, 0) }
            addView(chipRow)
        }
        root.addView(chipsScroll)

        // ---- All motions A–Z (always visible, never filtered) ----
        root.addView(allMotionsRow())

        // ---- Banded preset list (the only re-rendered region) ----
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(box)
        }
        root.addView(scroll)
        listBox = box
        renderList()
    }

    private fun styleChips() {
        for ((i, chip) in chipViews.withIndex()) {
            val sel = i == selectedChip
            if (sel) {
                chip.setTextColor(Color.WHITE)
                chip.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                chip.background = roundedBg(Theme.primary, 999)
            } else {
                chip.setTextColor(Theme.textSecondary)
                chip.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                chip.background = roundedBg(Color.WHITE, 999, Theme.stroke)
            }
        }
    }

    /** The mock .row shell shared by the All-motions row and every
     *  preset row: white fill, 16dp corners, 1dp stroke, 12/14dp
     *  padding (the mock's literal white — Theme.surface is a tinted
     *  container tone in both palettes, not the row white). */
    private fun rowCard(onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(Color.WHITE, 16, Theme.stroke)
            elevation = dp(2).toFloat()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    /** Name (15sp bold, one line) over meta (12sp secondary), the
     *  factory text margins stripped as on every other row. */
    private fun textCol(name: String, meta: String): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(12), 0, dp(8), 0) }
        }
        val nm = makeText(name, 15f, true)
        nm.maxLines = 1; nm.ellipsize = android.text.TextUtils.TruncateAt.END
        (nm.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        col.addView(nm)
        val mt = makeText(meta, 12f, false, Theme.textSecondary)
        mt.maxLines = 1; mt.ellipsize = android.text.TextUtils.TruncateAt.END
        (mt.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(1), 0, 0)
        col.addView(mt)
        return col
    }

    private fun allMotionsRow(): LinearLayout {
        val row = rowCard { startActivity(Intent(this, CircuitMotionsActivity::class.java)) }
        (row.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(9), 0, 0)
        row.addView(settingsIcon("🔤", IconTints.LILAC))
        row.addView(textCol("All motions A–Z", "Burpees, climbers, shuffles… every motion"))
        row.addView(TextView(this).apply {
            text = "›"; textSize = 20f; setTextColor(Theme.textTertiary); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(48))
        })
        return row
    }

    private fun renderList() {
        val box = listBox ?: return
        box.removeAllViews()
        val predicate: (CircuitsData.Circuit) -> Boolean =
            chipDefs.getOrNull(selectedChip)?.second ?: { true }
        var any = false
        for (band in CircuitsData.BAND_ORDER) {
            val inBand = circuits.filter { it.band == band && predicate(it) }
            if (inBand.isEmpty()) continue
            any = true
            val label = makeText(band.uppercase(), 12f, true, Theme.primary)
            label.letterSpacing = 0.1f
            (label.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(18), 0, 0)
            box.addView(label)
            for (c in inBand) box.addView(circuitRow(c, band))
        }
        if (!any) {
            val empty = caption("No circuits match this filter.")
            (empty.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(14), 0, 0)
            box.addView(empty)
        }
    }

    private fun circuitRow(c: CircuitsData.Circuit, band: String): LinearLayout {
        val row = rowCard {
            startActivity(Intent(this, CircuitDetailActivity::class.java).putExtra("circuitId", c.id))
        }
        (row.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(9), 0, 0)
        row.addView(settingsIcon(emojiFor(c), tintForBand(band), 44, 21f))
        val roundsLabel = when {
            c.rounds == -1 -> "AMRAP"
            c.rounds == 1 -> "1 round"
            else -> "${c.rounds} rounds"
        }
        row.addView(textCol(c.name, "${c.steps.size} motions · $roundsLabel · ${c.dosePattern}"))
        row.addView(minutesPill(c, band == "Start Here"))
        return row
    }

    /** pillView-style duration pill: the estimate "~" drops on
     *  time-boxed circuits (their clock is exact); Start Here rows
     *  get the green treatment from the mock. */
    private fun minutesPill(c: CircuitsData.Circuit, startHere: Boolean): TextView {
        val label = if (c.timeBoxMin != null) "${c.totalMinutes} min" else "~${c.totalMinutes} min"
        return TextView(this).apply {
            text = label; textSize = 11f
            setTextColor(if (startHere) Theme.success else Theme.primaryDark)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = roundedBg(if (startHere) Theme.successBg else Theme.surfaceVariant, 999)
            setPadding(dp(8), dp(3), dp(8), dp(3)); maxLines = 1; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Specialty picks the emoji; a preset with no specialty match
     *  from Nerd Fitness (no source) reads 📘, anything else the
     *  generic circuit bolt. */
    private fun emojiFor(c: CircuitsData.Circuit): String {
        when (c.specialty?.trim()?.lowercase()) {
            "beginner" -> return "🌱"
            "low-impact", "low impact" -> return "🕊️"
            "mobility" -> return "🧘"
            "dumbbell" -> return "🏋️"
            "core" -> return "🎯"
            "hiit" -> return "🔥"
            "tabata" -> return "⚡"
            "time-boxed", "time boxed" -> return "⏱️"
            "bodyweight" -> return "💪"
            "warm-up", "warm up", "warmup" -> return "🌤️"
        }
        return if (c.sourceType == null) "📘" else "⚡"
    }

    private fun tintForBand(band: String): Int = when (band) {
        "Start Here" -> IconTints.GREEN
        "From Your Reels" -> IconTints.YOLK
        "Quick & Timed" -> IconTints.PINK
        else -> IconTints.LILAC
    }
}

/** v3.11 All motions A–Z (reached from the Circuits home row): every
 *  motion in the library, one row each. The list exists to PREVIEW
 *  motions — the row tap and its ▶ alike play the motion's video
 *  (the Short where one was verified, else the long video). A
 *  ScrollView because the library is long — a declared list context,
 *  like the Circuits preset list. */
class CircuitMotionsActivity : AppCompatActivity() {
    private var created = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        created = true
        render()
    }

    override fun onResume() {
        super.onResume()
        if (created) render()
    }

    private fun render() {
        val root = fitRoot()
        setContentView(root)
        val motions = CircuitsData.motions(this)
        root.addView(topBar("All motions", "${motions.size} motions"))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(box)
        }
        root.addView(scroll)
        for ((partId, entry) in motions) box.addView(motionRow(partId, entry))
    }

    private fun motionRow(partId: String, entry: JsonSeed.Entry): LinearLayout {
        val video = entry.shortUrl.ifBlank { entry.url }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(Color.WHITE, 16, Theme.stroke)
            elevation = dp(2).toFloat()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(9), 0, 0) }
            isClickable = true
            setOnClickListener { openUrl(this@CircuitMotionsActivity, video) }
        }
        // 44dp photo thumb (the catalog photo, centre-cropped into
        // the 12dp-rounded square); the runner squircle stands in
        // when the entry has no usable photo.
        val bmp = if (entry.photo.isNotBlank()) PhotoLib.bitmap(this, entry.photo) else null
        if (bmp != null) {
            row.addView(ImageView(this).apply {
                setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                roundedClip(12)
            })
        } else {
            row.addView(settingsIcon("🏃", IconTints.LILAC))
        }
        val resolved = CircuitsData.partNameOf(this, entry.id)
        val partName = if (resolved.isBlank() || resolved == entry.id) BodyParts.name(partId) else resolved
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(12), 0, dp(8), 0) }
        }
        val nm = makeText(entry.name, 15f, true)
        nm.maxLines = 1; nm.ellipsize = android.text.TextUtils.TruncateAt.END
        (nm.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        col.addView(nm)
        val mt = makeText("$partName · ${entry.equipment}", 12f, false, Theme.textSecondary)
        mt.maxLines = 1; mt.ellipsize = android.text.TextUtils.TruncateAt.END
        (mt.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(1), 0, 0)
        col.addView(mt)
        row.addView(col)
        row.addView(playGlyph { openUrl(this, video) })
        return row
    }
}
