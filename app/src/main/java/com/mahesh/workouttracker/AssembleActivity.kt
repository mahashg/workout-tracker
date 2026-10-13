package com.mahesh.workouttracker

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** v3 assembled workout: ONE flat list for today's picked parts.
 *  Warm-ups (union of the parts, deduped) sit in a card on top; the Main
 *  exercises form a single flat list — no body-part sections — in equipment
 *  clusters Machine -> Dumbbell -> Kettlebell -> Bodyweight, each row tagged
 *  with its body part. Primary exercises are pre-checked; rows can be
 *  unchecked and reordered (up/down). Start creates the session with only
 *  warm-ups + checked exercises + stretches, in the displayed order. */
class AssembleActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var partIds: List<String> = emptyList()
    private var warmups: List<Exercise> = emptyList()
    private var stretches: List<Exercise> = emptyList()
    private val mains = mutableListOf<Exercise>()
    private val checked = mutableSetOf<Long>()
    private var page = 0
    private var created = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = DbHelper(this)
        JsonSeed.applyV3IfNeeded(this, db)
        partIds = (intent.getStringExtra("parts") ?: "").split(",").map { it.trim() }.filter { BodyParts.byId(it) != null }
        if (partIds.isEmpty()) { finish(); return }
        buildLists()
        created = true
        render()
    }

    private fun equipRank(equipment: String): Int = when (equipment.lowercase()) {
        "machine" -> 0; "dumbbell" -> 1; "kettlebell" -> 2; "bodyweight" -> 3; else -> 4
    }

    private fun buildLists() {
        val warm = LinkedHashMap<String, Exercise>()
        val stretch = LinkedHashMap<String, Exercise>()
        val mainAll = mutableListOf<Exercise>()
        for (pid in partIds) {
            for (e in db.getExercisesForPart(pid)) {
                val key = e.sourceId.ifBlank { "name:" + e.name.lowercase() }
                when (e.type) {
                    "Warmup" -> warm.putIfAbsent(key, e)
                    "Stretch" -> stretch.putIfAbsent(key, e)
                    else -> mainAll.add(e)
                }
            }
        }
        warmups = warm.values.toList()
        stretches = stretch.values.toList()
        // Equipment clusters; Kotlin's sort is stable, so JSON/DB order is
        // kept inside each cluster.
        mains.clear()
        mains.addAll(mainAll.sortedBy { equipRank(it.equipment) })
        checked.clear()
        for (e in mains) {
            val entry = JsonSeed.entryById(this, e.sourceId)
            if (entry?.primary == true || e.sourceId.isBlank()) checked.add(e.id)
        }
    }

    private fun estimateMin(): Int {
        val sets = mains.filter { it.id in checked }.sumOf { it.defaultSets }
        return warmups.size * 1 + sets * 2 + (stretches.size + 1) / 2
    }

    private fun render() {
        val compact = isCompactScreen()
        val root = fitRoot()
        setContentView(root)
        val label = partIds.joinToString(" + ") { BodyParts.name(it) }
        val sub = "$label · ~${estimateMin()} min · ${checked.size} exercises"
        root.addView(topBar("Today's Workout", sub))

        // Warm-up card: the deduped union, names only (compact).
        val warmCard = cardLayout()
        warmCard.setPadding(dp(12), dp(8), dp(12), dp(8))
        val wTitle = makeText("Warm-up · ${warmups.size} moves", 13.5f, true, sectionColor("Warmup"))
        wTitle.maxLines = 1
        warmCard.addView(wTitle)
        val wNames = makeText(warmups.joinToString("  ·  ") { it.name }, 12f, false, Theme.textSecondary)
        wNames.maxLines = 2
        warmCard.addView(wNames)
        root.addView(warmCard)

        // Flat exercise list (paginated, fixed rows).
        val rowH = if (compact) 54 else 60
        val pageSize = if (compact) 5 else 7
        val pageCount = maxOf(1, (mains.size + pageSize - 1) / pageSize)
        page = page.coerceIn(0, pageCount - 1)
        val listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val from = page * pageSize
        val to = minOf(mains.size, from + pageSize)
        for (i in from until to) listBox.addView(exerciseRow(i, rowH))
        root.addView(listBox)
        if (pageCount > 1) root.addView(pagerBar(page, pageCount, { page--; render() }, { page++; render() }))

        val coolLine = makeText("Cool-down · ${stretches.joinToString(", ") { it.name }}", 11.5f, false, Theme.textSecondary)
        coolLine.maxLines = 1
        coolLine.ellipsize = android.text.TextUtils.TruncateAt.END
        root.addView(coolLine)

        val startBtn = makeButton("Start Workout") { startSession() }
        tightButton(startBtn, if (compact) 50 else 54, 14, primary = true)
        startBtn.isEnabled = checked.isNotEmpty()
        startBtn.alpha = if (checked.isNotEmpty()) 1f else 0.5f
        root.addView(startBtn)
    }

    private fun exerciseRow(index: Int, rowH: Int): View {
        val e = mains[index]
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = cardDrawable()
            setPadding(dp(8), dp(4), dp(6), dp(4))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(rowH)).apply { setMargins(0, dp(2), 0, dp(2)) }
        }
        val cb = CheckBox(this).apply {
            isChecked = e.id in checked
            buttonTintList = android.content.res.ColorStateList.valueOf(Theme.primary)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setOnCheckedChangeListener { _, on -> if (on) checked.add(e.id) else checked.remove(e.id); render() }
        }
        row.addView(cb)
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val nm = makeText(e.name, 14f, true)
        nm.maxLines = 1; nm.ellipsize = android.text.TextUtils.TruncateAt.END
        nameRow.addView(nm, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        nameRow.addView(TextView(this).apply {
            text = BodyParts.name(e.bodyPart); textSize = 10f; setTextColor(Theme.accentSky)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            background = roundedBg("#E0F2FE", 999); setPadding(dp(6), dp(1), dp(6), dp(1)); maxLines = 1
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(6), 0, 0, 0) }
        })
        info.addView(nameRow)
        val last = db.lastPerformedForExercise(e.name, -1)
        val meta = makeText(
            if (last != null) "${e.equipment} · ${e.defaultSets}×${e.targetReps} · Last: ${lastTimeLine(last)}"
            else "${e.equipment} · ${e.defaultSets}×${e.targetReps} · First time",
            11f, false, Theme.textSecondary)
        meta.maxLines = 1; meta.ellipsize = android.text.TextUtils.TruncateAt.END
        info.addView(meta)
        row.addView(info)
        val up = makeSmallButton("↑") { move(index, -1) }
        tightButton(up, 34, 10)
        (up.layoutParams as LinearLayout.LayoutParams).apply { width = dp(36); setMargins(dp(2), 0, 0, 0) }
        up.isEnabled = index > 0; up.alpha = if (index > 0) 1f else 0.35f
        row.addView(up)
        val down = makeSmallButton("↓") { move(index, 1) }
        tightButton(down, 34, 10)
        (down.layoutParams as LinearLayout.LayoutParams).apply { width = dp(36); setMargins(dp(2), 0, 0, 0) }
        down.isEnabled = index < mains.size - 1; down.alpha = if (index < mains.size - 1) 1f else 0.35f
        row.addView(down)
        return row
    }

    private fun move(index: Int, dir: Int) {
        val target = index + dir
        if (target !in mains.indices) return
        val tmp = mains[index]; mains[index] = mains[target]; mains[target] = tmp
        val pageSize = if (isCompactScreen()) 5 else 7
        page = target / pageSize
        render()
    }

    private fun startSession() {
        val items = mutableListOf<Exercise>()
        items.addAll(warmups)
        items.addAll(mains.filter { it.id in checked })
        items.addAll(stretches)
        if (items.isEmpty()) return
        val wm = WeekManager.reconcile(this, db)
        val label = partIds.joinToString(" + ") { BodyParts.name(it) }
        val sessionId = db.createAssembledSession(label, partIds, items, wm.weekNumber, wm.weekStart)
        startActivity(Intent(this, WorkoutSectionsActivity::class.java).putExtra("sessionId", sessionId))
        finish()
    }
}
