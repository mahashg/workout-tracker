package com.mahesh.workouttracker

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** v3 picker: choose today's body parts (multi-select, at least 1), then the
 *  workout is assembled from the per-part JSON-seeded data. Same one-page
 *  2-column grid as Home, with Done / Due / N× state per part (S3). */
class PickPartsActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private val selected = linkedSetOf<String>()
    private var created = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = DbHelper(this)
        JsonSeed.applyV3IfNeeded(this, db)
        created = true
        render()
    }

    private fun render() {
        val wm = WeekManager.reconcile(this, db)
        val compact = isCompactScreen()
        val root = fitRoot()
        setContentView(root)
        root.addView(topBar("Start Workout", "Pick the body parts for today"))
        val lastTrained = db.lastTrainedDates()
        val counts = db.partCountsInWeek(wm.weekStart)

        val cellH = if (compact) 56 else 64
        for (rowParts in BodyParts.all.chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (part in rowParts) {
                row.addView(partCell(part, lastTrained[part.id], counts[part.id] ?: 0), LinearLayout.LayoutParams(0, dp(cellH), 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
            if (rowParts.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, dp(cellH), 1f))
            root.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(cellH)).apply { setMargins(0, dp(2), 0, dp(2)) })
        }
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val n = selected.size
        val btn = makeButton(if (n == 0) "Pick at least 1 body part" else "Build workout · $n part${if (n == 1) "" else "s"}") {
            if (selected.isEmpty()) return@makeButton
            val ordered = BodyParts.ids.filter { it in selected }
            startActivity(Intent(this, AssembleActivity::class.java).putExtra("parts", ordered.joinToString(",")))
        }
        tightButton(btn, if (compact) 50 else 54, 14, primary = true)
        btn.isEnabled = n > 0
        btn.alpha = if (n > 0) 1f else 0.5f
        root.addView(btn)
    }

    private fun partCell(part: BodyParts.Part, last: String?, countThisWeek: Int): View {
        val isSel = part.id in selected
        val done = countThisWeek > 0
        val days = last?.let { daysSince(it) }
        val due = !part.repeatable && !done && (last == null || (days != null && days >= 7))
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(accent = if (isSel) "#2563EB" else null, tappable = true)
            setPadding(dp(10), dp(6), dp(10), dp(5))
            isClickable = true
            setOnClickListener {
                if (isSel) selected.remove(part.id) else selected.add(part.id)
                render()
            }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val nameTv = makeText(part.name, 14.5f, true)
        nameTv.maxLines = 1
        top.addView(nameTv)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val (pillText, pillBg, pillInk) = when {
            isSel -> Triple("✓ Picked", "#DBEAFE", Theme.primary)
            part.repeatable -> Triple("${countThisWeek}× this week", "#EDF2F7", Theme.textSecondary)
            done -> Triple("✓ Done", "#DCFCE7", Theme.success)
            due -> Triple("Due", "#FEF3C7", Theme.warn)
            else -> Triple("Pending", "#E2E8F0", Theme.pendingFg)
        }
        top.addView(TextView(this).apply {
            text = pillText; textSize = 10.5f; setTextColor(pillInk); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            background = roundedBg(pillBg, 999); setPadding(dp(7), dp(2), dp(7), dp(2)); maxLines = 1
        })
        cell.addView(top)
        val lastLine = when {
            last == null -> "Not trained yet"
            days != null && days == 0L -> "Last · today"
            days != null && days == 1L -> "Last · yesterday"
            days != null -> "Last · ${DateUtil.display(last)} · ${days}d ago"
            else -> "Last · ${DateUtil.display(last)}"
        }
        val lastTv = makeText(lastLine, 11f, false, Theme.textSecondary)
        lastTv.maxLines = 1
        lastTv.setPadding(0, dp(2), 0, 0)
        cell.addView(lastTv)
        return cell
    }
}
