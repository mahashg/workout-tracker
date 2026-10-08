package com.mahesh.workouttracker

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SessionActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var sessionId: Long = -1
    private var timer: CountDownTimer? = null
    private var timerDialog: AlertDialog? = null
    private var timerChip: TimerChipView? = null
    private val howToExpanded = mutableSetOf<Long>()
    private var howToDefaultApplied = false
    private var page: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = DbHelper(this)
        sessionId = intent.getLongExtra("sessionId", -1)
        if (sessionId < 0) { finish(); return }
        render()
    }
    override fun onPause() { super.onPause(); timerChip?.stopTicking(); timerChip?.foldNow() }
    override fun onDestroy() { super.onDestroy(); timer?.cancel(); timerDialog?.dismiss(); timerChip?.stopTicking() }

    private fun sectionOf(type: String): String = when (type.lowercase()) { "warmup" -> "Warm Up"; "stretch" -> "Cool Down"; else -> "Exercise" }

    /** Rough height (dp) of one exercise block, used to size pages so nothing scrolls. */
    private fun estimateHeight(ex: SessionExercise): Int {
        val sets = db.getSets(ex.id).size
        var h = 104 + sets * 50
        if (ex.variation.isNotBlank()) h += 14
        if (howToExpanded.contains(ex.id)) {
            h += if (Beginner.beginnerMode(this)) 168 else 52
        }
        return h
    }

    /** Greedy pagination: fill each page up to the available height, 1-3 exercises. */
    private fun computePages(exs: List<SessionExercise>): List<IntRange> {
        if (exs.isEmpty()) return listOf(0..-1)
        val avail = (screenHeightDp() - if (isCompactScreen()) 348 else 372).coerceAtLeast(180)
        val pages = mutableListOf<IntRange>()
        var start = 0
        while (start < exs.size) {
            var end = start
            var used = estimateHeight(exs[start])
            while (end + 1 < exs.size && end - start < 2) {
                val nextH = estimateHeight(exs[end + 1]) + 8
                if (used + nextH > avail) break
                used += nextH; end++
            }
            pages.add(start..end)
            start = end + 1
        }
        return pages
    }

    private fun pageContaining(exId: Long, exs: List<SessionExercise>): Int {
        val pages = computePages(exs)
        val idx = exs.indexOfFirst { it.id == exId }
        if (idx < 0) return page.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        return pages.indexOfFirst { idx in it }.coerceAtLeast(0)
    }

    private fun smallBtn(text: String, w: Int, h: Int = 40, onClick: () -> Unit): Button {
        val b = makeSmallButton(text, onClick)
        b.minWidth = 0; b.minHeight = 0; b.setPadding(0, 0, 0, 0); b.textSize = 12f
        (b.layoutParams as LinearLayout.LayoutParams).apply { width = dp(w); height = dp(h) }
        return b
    }

    private fun render(anchorExId: Long = -1) {
        val session = db.getSession(sessionId) ?: run { finish(); return }
        val unit = WeekManager.unit(this)
        val root = fitRoot(); setContentView(root)
        root.addView(topBar(session.routineName, "${DateUtil.display(session.date)} • Week ${session.weekNumber}"))

        val exs = db.getSessionExercises(sessionId)
        if (anchorExId > 0) page = pageContaining(anchorExId, exs)
        val pages = computePages(exs)
        if (page !in pages.indices) page = (pages.size - 1).coerceAtLeast(0)
        val range = pages.getOrElse(page) { 0..-1 }

        // Compact header
        val header = cardLayout("#2563EB")
        header.setPadding(dp(12), dp(8), dp(12), dp(8))
        val hRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val allMuscles = linkedSetOf<String>()
        for (e in exs) allMuscles.addAll(DbHelper.parseMuscles(e.targetMuscles, e.name))
        hRow.addView(BodyMapView(this, allMuscles, true).apply { layoutParams = LinearLayout.LayoutParams(dp(44), dp(54)) })
        val hInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(10), 0, 0, 0) }
        hInfo.addView(makeText(if (session.completed) "Workout (Completed)" else "Workout of the Day", 11f, false, Theme.textSecondary))
        hInfo.addView(makeText(session.routineName, 18f, true))
        hInfo.addView(caption("${session.focus} • Targets: ${musclesLabel(allMuscles)}"))
        if (session.startedAt > 0) {
            val durTxt = if (session.completed) WorkoutTimer.formatDuration(session.elapsedSec) else WorkoutTimer.formatDuration(WorkoutTimer.liveElapsedSec(this, session)) + " so far"
            hInfo.addView(makeText("Time: $durTxt", 12f, true, Theme.primary))
        }
        hRow.addView(hInfo); header.addView(hRow)
        if (!session.completed) {
            db.ensureSessionStarted(sessionId)
            val chip = TimerChipView(this)
            chip.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, 0) }
            chip.bind(this, db, db.getSession(sessionId) ?: session) {}
            chip.startTicking()
            timerChip = chip
            header.addView(chip)
        }
        root.addView(header)

        // Paginated exercise region (flexes to fill the screen)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        if (exs.isEmpty()) {
            content.addView(makeText("No exercises in this session.", 14f))
        } else {
            val firstSec = sectionOf(exs[range.first].type)
            val rangeLabel = if (range.first == range.last) "Exercise ${range.first + 1} of ${exs.size}" else "Exercises ${range.first + 1}–${range.last + 1} of ${exs.size}"
            content.addView(makeText("$firstSec • $rangeLabel", 13f, true, sectionColor(exs[range.first].type)))
            var lastSection = ""
            for (idx in range) {
                val ex = exs[idx]
                val section = sectionOf(ex.type)
                if (section != lastSection && idx != range.first) {
                    content.addView(makeText(section, 13f, true, sectionColor(ex.type)))
                    lastSection = section
                } else if (idx == range.first) lastSection = section
                content.addView(exerciseCard(ex, idx, exs, unit))
            }
        }
        root.addView(content)

        if (pages.size > 1) root.addView(pagerBar(page, pages.size, { page--; render() }, { page++; render() }))

        // Fixed bottom action bar
        addActionBar(root, session.completed)
    }

    private fun exerciseCard(ex: SessionExercise, idx: Int, exs: List<SessionExercise>, unit: String): LinearLayout {
        val card = cardLayout()
        card.setPadding(dp(10), dp(8), dp(10), dp(8))
        (card.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(4), 0, dp(4))
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val exMuscles = DbHelper.parseMuscles(ex.targetMuscles, ex.name)
        if (exMuscles.isNotEmpty()) top.addView(BodyMapView(this, exMuscles).apply { layoutParams = LinearLayout.LayoutParams(dp(36), dp(44)) })
        val tInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(8), 0, 0, 0) }
        val titleTv = makeText("${idx + 1}. ${ex.name}", 15f, true)
        titleTv.maxLines = 1; titleTv.ellipsize = android.text.TextUtils.TruncateAt.END
        tInfo.addView(titleTv)
        val badgeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        badgeRow.addView(equipmentBadge(ex.equipment))
        val sets0 = db.getSets(ex.id)
        badgeRow.addView(makeText("${ex.type} • ${doItLine(sets0)}", 11f, true, Color.parseColor("#92400E")))
        tInfo.addView(badgeRow)
        val last = db.lastPerformedForExercise(ex.name, sessionId)
        if (last != null) {
            val summary = last.sets.joinToString(", ") { s -> if (s.isBodyweight) "BW x${s.reps.ifBlank { "?" }}" else "${s.weight.ifBlank { "?" }} $unit x${s.reps.ifBlank { "?" }}" }
            val eff = if (last.effort.isNotBlank()) " • ${last.effort} — ${Beginner.effortSuggestion(last.effort, unit)}" else ""
            val lt = makeText("Last: ${DateUtil.display(last.date)} • $summary$eff", 11f, false, Color.parseColor("#15803D"))
            lt.maxLines = 1; lt.ellipsize = android.text.TextUtils.TruncateAt.END
            tInfo.addView(lt)
        } else {
            tInfo.addView(makeText("First time", 11f, false, Theme.textSecondary))
        }
        top.addView(tInfo); card.addView(top)
        if (db.effectiveStatus(ex) == "skipped") card.addView(makeText("Skipped", 12f, true, Color.parseColor("#92400E")))

        // "Show me how" toggle (Beginner Mode): video + feel + posture + cues
        if (Beginner.beginnerMode(this)) {
            if (!howToDefaultApplied) { howToDefaultApplied = true; howToExpanded.add(ex.id) }
            val expanded = howToExpanded.contains(ex.id)
            val toggle = makeSecondaryButton(if (expanded) "Hide how-to  ▴" else "Show me how  ▾") {
                if (howToExpanded.contains(ex.id)) howToExpanded.remove(ex.id) else howToExpanded.add(ex.id)
                render(ex.id)
            }
            (toggle.layoutParams as LinearLayout.LayoutParams).height = dp(40)
            card.addView(toggle)
            if (expanded) {
                if (ex.youtubeUrl.isNotBlank()) {
                    val vid = primaryButtonWithIcon("Watch Form Video", R.drawable.ic_play) { openUrl(this, ex.youtubeUrl) }
                    (vid.layoutParams as LinearLayout.LayoutParams).height = dp(48)
                    card.addView(vid)
                }
                card.addView(makeText("What you'll feel: ${musclesLabel(exMuscles)}", 12f, false, Color.parseColor("#0369A1")))
                val posture = ex.postureCheck.ifBlank { DbHelper.postureForName(ex.name, ex.type) }
                var truncated = false
                if (posture.isNotBlank()) {
                    val items = posture.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                    card.addView(makeText("Check your posture:", 13f, true, Color.parseColor("#15803D")))
                    for (c in items.take(4)) card.addView(makeText("☐  $c", 12f, false))
                    if (items.size > 4) truncated = true
                }
                val cues = ex.cues.ifBlank { DbHelper.cuesForName(ex.name, ex.equipment, ex.type) }
                if (cues.isNotBlank()) {
                    val items = cues.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                    for (c in items.take(3)) card.addView(makeText("•  $c", 12f, false, Theme.textSecondary))
                    if (items.size > 3) truncated = true
                }
                if (truncated) card.addView(makeText("…", 12f, true, Theme.textTertiary))
                if (ex.equipment == "Machine" || ex.equipment == "Dumbbell" || ex.equipment == "Kettlebell") card.addView(makeText("Start light: use the lightest weight that feels easy first.", 11f, false, Color.parseColor("#92400E")))
            }
        } else {
            if (ex.youtubeUrl.isNotBlank()) {
                val vid = primaryButtonWithIcon("Watch Form Video", R.drawable.ic_play) { openUrl(this, ex.youtubeUrl) }
                (vid.layoutParams as LinearLayout.LayoutParams).height = dp(48)
                card.addView(vid)
            }
        }

        val sets = db.getSets(ex.id)
        strike(titleTv, sets.isNotEmpty() && sets.all { it.isDone })
        val wStep = if (unit == "kg") 2.5 else 5.0
        for (set in sets) {
            val sRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(3), dp(4), dp(3)); background = roundedBg("#EDF2F7", 12); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { setMargins(0, dp(3), 0, dp(3)) } }
            sRow.addView(TextView(this).apply { text = "S${set.setNumber}"; setTextColor(Theme.textPrimary); textSize = 12f; layoutParams = LinearLayout.LayoutParams(dp(26), LinearLayout.LayoutParams.WRAP_CONTENT) })
            val wEt = EditText(this).apply { setText(set.weight); hint = "wt"; styleEditText(this); textSize = 12f; minHeight = dp(40); setPadding(dp(4), 0, dp(4), 0); isEnabled = !set.isBodyweight; layoutParams = LinearLayout.LayoutParams(dp(44), dp(40)) }
            val rEt = EditText(this).apply { setText(set.reps); hint = "reps"; styleEditText(this); textSize = 12f; minHeight = dp(40); setPadding(dp(4), 0, dp(4), 0); layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)) }
            val bwCb = CheckBox(this).apply { text = "BW"; setTextColor(Theme.textSecondary); textSize = 11f; isChecked = set.isBodyweight; setPadding(0, 0, 0, 0); layoutParams = LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT) }
            val doneCb = CheckBox(this).apply { text = "Done"; setTextColor(Theme.textPrimary); textSize = 12f; isChecked = set.isDone; setPadding(0, 0, 0, 0); layoutParams = LinearLayout.LayoutParams(dp(60), LinearLayout.LayoutParams.WRAP_CONTENT) }
            wEt.addTextChangedListener(simpleWatcher { db.updateSet(set.copy(weight = it, isBodyweight = bwCb.isChecked, isDone = doneCb.isChecked)) })
            rEt.addTextChangedListener(simpleWatcher { db.updateSet(set.copy(weight = wEt.text.toString(), reps = it, isBodyweight = bwCb.isChecked, isDone = doneCb.isChecked)) })
            bwCb.setOnCheckedChangeListener { _, checked -> wEt.isEnabled = !checked; db.updateSet(set.copy(weight = wEt.text.toString(), reps = rEt.text.toString(), isBodyweight = checked, isDone = doneCb.isChecked)) }
            doneCb.setOnCheckedChangeListener { _, checked ->
                db.updateSet(set.copy(weight = wEt.text.toString(), reps = rEt.text.toString(), isBodyweight = bwCb.isChecked, isDone = checked))
                db.syncExerciseStatus(ex.id)
                if (checked) startRestTimer()
                render(ex.id)
            }
            sRow.addView(smallBtn("−", 28) { val v = ((wEt.text.toString().toDoubleOrNull() ?: 0.0) - wStep).coerceAtLeast(0.0); wEt.setText(if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()) })
            sRow.addView(wEt)
            sRow.addView(smallBtn("+", 28) { val v = (wEt.text.toString().toDoubleOrNull() ?: 0.0) + wStep; wEt.setText(if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()) })
            sRow.addView(TextView(this).apply { text = "$unit×"; setTextColor(Theme.textSecondary); textSize = 11f; layoutParams = LinearLayout.LayoutParams(dp(26), LinearLayout.LayoutParams.WRAP_CONTENT) })
            sRow.addView(smallBtn("−", 28) { val v = ((rEt.text.toString().filter { it.isDigit() }.toIntOrNull() ?: 0) - 1).coerceAtLeast(0); rEt.setText(v.toString()) })
            sRow.addView(rEt)
            sRow.addView(smallBtn("+", 28) { val v = (rEt.text.toString().filter { it.isDigit() }.toIntOrNull() ?: 0) + 1; rEt.setText(v.toString()) })
            sRow.addView(bwCb); sRow.addView(doneCb)
            card.addView(sRow)
        }
        val setBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun sb(b: Button) { (b.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(40) }; setBtnRow.addView(b) }
        if (idx > 0) sb(makeSmallButton("↑ Up") { db.swapSessionExerciseOrder(ex, exs[idx - 1]); render(ex.id) })
        if (idx < exs.size - 1) sb(makeSmallButton("↓ Down") { db.swapSessionExerciseOrder(ex, exs[idx + 1]); render(ex.id) })
        sb(makeSmallButton("+ Set") { db.addSet(ex.id); db.syncExerciseStatus(ex.id); render(ex.id) })
        sb(makeSmallButton("− Set") { db.removeLastSet(ex.id); db.syncExerciseStatus(ex.id); render(ex.id) })
        card.addView(setBtnRow)
        return card
    }

    private fun addActionBar(root: LinearLayout, completed: Boolean) {
        val session = db.getSession(sessionId) ?: return
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun ab(b: Button) { (b.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48) }; row.addView(b) }
        ab(makeSmallButton("Save Order") {
            db.saveSessionOrderAsDefault(sessionId, session.routineId); Toast.makeText(this, "Saved as default order", Toast.LENGTH_SHORT).show()
        })
        if (!completed) {
            ab(makeSmallButton("Finish Workout") {
                AlertDialog.Builder(this).setTitle("Finish workout?").setMessage("Mark as completed? Unchecked sets stay as-is in history/CSV.")
                    .setPositiveButton("Finish") { _, _ -> val fresh = db.getSession(sessionId); if (fresh != null) WorkoutTimer.finish(this, db, fresh); db.setSessionCompleted(sessionId, true); startActivity(android.content.Intent(this, SummaryActivity::class.java).apply { putExtra("sessionId", sessionId) }); finish() }.setNegativeButton("Cancel", null).show()
            })
        } else {
            ab(makeSmallButton("Re-open") { db.setSessionCompleted(sessionId, false); render() })
        }
        ab(makeSmallButton("Save & Exit") { finish() })
        root.addView(row)
        val discard = makeSmallButton("Discard Session") {
            AlertDialog.Builder(this).setTitle("Discard session?").setMessage("Delete this session and its sets?")
                .setPositiveButton("Discard") { _, _ -> WorkoutTimer.clear(this, sessionId); db.deleteSession(sessionId); finish() }.setNegativeButton("Cancel", null).show()
        }
        (discard.layoutParams as LinearLayout.LayoutParams).height = dp(48)
        root.addView(discard)
    }

    private fun simpleWatcher(onChange: (String) -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
    }
    private fun startRestTimer() {
        timer?.cancel(); timerDialog?.dismiss()
        val tv = TextView(this).apply { text = "60"; textSize = 34f; setTextColor(Theme.textPrimary); setPadding(dp(24), dp(24), dp(24), dp(24)); gravity = Gravity.CENTER }
        val dlg = AlertDialog.Builder(this).setTitle("Rest 60 sec").setView(tv).setNegativeButton("Skip") { _, _ -> timer?.cancel() }.setPositiveButton("Cancel Timer") { _, _ -> timer?.cancel() }.create()
        timerDialog = dlg; dlg.show()
        timer = object : CountDownTimer(60000, 1000) { override fun onTick(ms: Long) { tv.text = "${ms / 1000}" } override fun onFinish() { tv.text = "Done!"; Toast.makeText(this@SessionActivity, "Rest done", Toast.LENGTH_SHORT).show() } }.start()
    }
}
