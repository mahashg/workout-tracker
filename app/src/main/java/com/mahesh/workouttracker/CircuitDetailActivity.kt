package com.mahesh.workouttracker

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** v3.11 Circuit detail (approved mock r1, Screen 3): everything about
 *  one circuit on one screen before committing — where it came from,
 *  totals up top, the motions in order with the dose on each, and a
 *  Start button that launches the player with exactly the motions
 *  still in the working list.
 *
 *  Editing is remove-only (mock caption): a long-press drops a motion
 *  from [working] for THIS launch only — the circuit itself is never
 *  mutated. The most recent removal can be undone from the slim bar
 *  directly above Start; a second removal replaces the undo record. */
class CircuitDetailActivity : AppCompatActivity() {

    private lateinit var circuit: CircuitsData.Circuit

    /** Indices into [CircuitsData.Circuit.steps], in play order.
     *  Initially every step; long-press removals drop entries here. */
    private val working = mutableListOf<Int>()

    /** The single undo record: the removed step index, the position
     *  it held in [working] when removed, and its display name. */
    private var undoStepIndex: Int? = null
    private var undoPosition: Int = -1
    private var undoName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val circuitId = intent.getStringExtra("circuitId")
        if (circuitId.isNullOrBlank()) { finish(); return }
        circuit = CircuitsData.circuitById(this, circuitId) ?: run { finish(); return }
        working.clear()
        working.addAll(circuit.steps.indices)
        render()
    }

    // ---------- derived lines ----------

    private fun sourceLine(): String {
        val creator = circuit.creator?.trim()?.takeIf { it.isNotEmpty() }
        val suffix = if (creator != null) " · $creator" else ""
        return when (circuit.sourceType) {
            "instagram-scan" -> "From your saved reel$suffix"
            "instagram-search", "instagram" -> "From Instagram$suffix"
            null -> "From the Nerd Fitness circuit guide"
            else -> "From ${circuit.sourceType}$suffix"
        }
    }

    private fun formatDisplay(format: String): String = when {
        format.equals("EMOM", ignoreCase = true) -> "EMOM"
        format.equals("every 2 minutes", ignoreCase = true) -> "E2MOM"
        format.equals("amrap", ignoreCase = true) -> "AMRAP"
        format.equals("for-time-single-pass", ignoreCase = true) -> "For time"
        format.equals("rounds", ignoreCase = true) -> "Rounds"
        else -> format
    }

    private fun entryName(entryId: String): String =
        JsonSeed.entryById(this, entryId)?.name?.takeIf { it.isNotBlank() } ?: entryId

    // ---------- editing ----------

    private fun removeStepAt(position: Int) {
        if (position !in working.indices) return
        val stepIndex = working.removeAt(position)
        undoStepIndex = stepIndex
        undoPosition = position
        undoName = entryName(circuit.steps[stepIndex].entryId)
        render()
    }

    private fun undoRemoval() {
        val stepIndex = undoStepIndex ?: return
        working.add(undoPosition.coerceIn(0, working.size), stepIndex)
        undoStepIndex = null
        undoPosition = -1
        undoName = ""
        render()
    }

    private fun onStartClicked() {
        if (working.isEmpty()) {
            Toast.makeText(this, "Remove undone — a circuit needs at least one motion", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, CircuitPlayerActivity::class.java)
                .putExtra("circuitId", circuit.id)
                .putExtra("stepIndices", working.toIntArray())
        )
    }

    // ---------- render ----------

    private fun render() {
        val root = fitRoot()
        setContentView(root)

        // Slim back link (mock Screen 3) — NOT the standard topBar.
        val back = makeText("‹ Circuits", 15f, false, Theme.primary)
        (back.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(2), 0, 0)
        back.isClickable = true
        back.setOnClickListener { finish() }
        root.addView(back)

        val title = makeText(circuit.name, 21f, true, Theme.textPrimary)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        (title.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(6), 0, 0)
        root.addView(title)

        val source = makeText(sourceLine(), 12.5f, false, Theme.textSecondary)
        (source.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(2), 0, 0)
        root.addView(source)

        root.addView(totalsCard())

        // Steps region: the one scrolling area; everything else is fixed.
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { setMargins(0, dp(8), 0, 0) }
        }
        val stepsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        var previousBlock: String? = null
        var isFirstStep = true
        for ((position, stepIndex) in working.withIndex()) {
            val step = circuit.steps[stepIndex]
            val block = step.block
            if (block != null && (isFirstStep || block != previousBlock)) {
                stepsBox.addView(blockLabel(block))
            }
            previousBlock = block
            isFirstStep = false
            stepsBox.addView(stepRow(position, step))
            if (position != working.lastIndex) {
                stepsBox.addView(View(this).apply {
                    setBackgroundColor(Theme.surfaceVariant)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    )
                })
            }
        }
        scroll.addView(stepsBox)
        root.addView(scroll)

        // Undo bar: directly above Start, only while a removal is undoable.
        if (undoStepIndex != null) {
            root.addView(undoBar())
        }

        val startBtn = makeButton("▶  Start circuit") { onStartClicked() }
        tightButton(startBtn, 56, 16, primary = true)
        startBtn.alpha = if (working.isEmpty()) 0.5f else 1f
        (startBtn.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(10), 0, 0)
        root.addView(startBtn)

        val hint = makeText(
            "Tap a motion for its video · long-press to remove it before starting.",
            12f, false, Theme.textSecondary
        )
        hint.gravity = Gravity.CENTER
        (hint.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(9), 0, 0)
        root.addView(hint)
    }

    private fun totalsCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(Theme.surface, 18)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(12), 0, 0) }
        }
        val (roundsValue, roundsLabel) =
            if (circuit.rounds == -1) "AMRAP" to "format"
            else circuit.rounds.toString() to "rounds"
        val (styleValue, styleLabel) = when {
            circuit.workSec != null -> "${circuit.workSec}s / ${circuit.restSec}s" to "work / rest"
            circuit.repsPerMotion != null -> "×${circuit.repsPerMotion}" to "reps each"
            circuit.format != null -> formatDisplay(circuit.format!!) to "format"
            else -> "—" to "style"
        }
        val minutesValue =
            if (circuit.timeBoxMin != null) "${circuit.totalMinutes}"
            else "~${circuit.totalMinutes}"
        addTotalColumn(card, working.size.toString(), "motions")
        addTotalColumn(card, roundsValue, roundsLabel)
        addTotalColumn(card, styleValue, styleLabel)
        addTotalColumn(card, minutesValue, "minutes")
        return card
    }

    private fun addTotalColumn(card: LinearLayout, value: String, label: String) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val v = makeText(value, 17f, true, Theme.textPrimary)
        v.gravity = Gravity.CENTER
        v.maxLines = 1
        v.ellipsize = TextUtils.TruncateAt.END
        (v.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        col.addView(v)
        val k = makeText(label, 11f, false, Theme.textSecondary)
        k.gravity = Gravity.CENTER
        k.maxLines = 1
        (k.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(1), 0, 0)
        col.addView(k)
        card.addView(col)
    }

    private fun blockLabel(block: String): TextView {
        val tv = makeText(block.uppercase(), 11f, true, Theme.textTertiary)
        tv.letterSpacing = 0.08f
        (tv.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(10), 0, dp(2))
        return tv
    }

    private fun stepRow(position: Int, step: CircuitsData.CStep): LinearLayout {
        val entry = JsonSeed.entryById(this, step.entryId)
        val name = entry?.name?.takeIf { it.isNotBlank() } ?: step.entryId
        val equipment = entry?.equipment ?: ""
        val partName = CircuitsData.partNameOf(this, step.entryId)
        val subLine = when {
            partName.isNotBlank() && equipment.isNotBlank() -> "$partName · $equipment"
            partName.isNotBlank() -> partName
            else -> equipment
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(11), dp(4), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isLongClickable = true
            setOnLongClickListener {
                removeStepAt(position)
                true
            }
        }
        // v3.12: tap a motion for its video (Short preferred,
        // falling back to the full video); the long-press remove
        // above is kept exactly as is — Android dispatches the
        // long-press instead of the click when it fires.
        val videoUrl = entry?.shortUrl?.takeIf { it.isNotBlank() }
            ?: entry?.url?.takeIf { it.isNotBlank() }
        if (videoUrl != null) {
            row.isClickable = true
            row.setOnClickListener { openUrl(this@CircuitDetailActivity, videoUrl) }
        }
        // Numbered disc: position in the WORKING list, so removals renumber.
        row.addView(TextView(this).apply {
            text = (position + 1).toString()
            textSize = 12.5f
            setTextColor(Theme.primaryDark)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(IconTints.BLUE)
            }
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        })
        val nameCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(12), 0, dp(8), 0) }
        }
        val nm = makeText(name, 15f, true, Theme.textPrimary)
        (nm.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        nameCol.addView(nm)
        if (subLine.isNotBlank()) {
            val tg = makeText(subLine, 12f, false, Theme.textSecondary)
            (tg.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(1), 0, 0)
            nameCol.addView(tg)
        }
        row.addView(nameCol)
        // Dose pill.
        row.addView(TextView(this).apply {
            text = step.doseLabel
            textSize = 11f
            setTextColor(Theme.primaryDark)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = roundedBg(Theme.surfaceVariant, 999)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        // v3.12: video affordance at the row's end, after the dose
        // pill — shown only when the motion actually has a video.
        if (videoUrl != null) {
            row.addView(TextView(this).apply {
                text = "▶"
                textSize = 13f
                setTextColor(Theme.primary)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(8), 0, 0, 0) }
            })
        }
        return row
    }

    private fun undoBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, 0) }
        }
        val undoBtn = makeSmallButton("↶ Undo") { undoRemoval() }
        undoBtn.minHeight = 0
        undoBtn.minWidth = 0
        (undoBtn.layoutParams as LinearLayout.LayoutParams).apply {
            width = LinearLayout.LayoutParams.WRAP_CONTENT
            height = dp(36)
            setMargins(0, 0, dp(10), 0)
        }
        undoBtn.setPadding(dp(12), 0, dp(12), 0)
        bar.addView(undoBtn)
        val msg = makeText("'" + undoName + "' removed", 12f, false, Theme.textSecondary)
        (msg.layoutParams as LinearLayout.LayoutParams).apply {
            width = 0
            weight = 1f
            setMargins(0, 0, 0, 0)
        }
        msg.maxLines = 1
        msg.ellipsize = TextUtils.TruncateAt.END
        bar.addView(msg)
        return bar
    }
}
