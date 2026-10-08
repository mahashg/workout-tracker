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
        // anchorExId is accepted for call-site compatibility; the one-page list
        // needs no re-anchoring because every exercise is always visible.

        // Compact fixed header (explicit 92dp): body map + name/focus + timer chip inline.
        val header = cardLayout("#2563EB")
        header.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(76)).apply { setMargins(0, 0, 0, 0) }
        header.setPadding(dp(10), dp(6), dp(10), dp(6))
        val hRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT) }
        val allMuscles = linkedSetOf<String>()
        for (e in exs) allMuscles.addAll(DbHelper.parseMuscles(e.targetMuscles, e.name))
        hRow.addView(BodyMapView(this, allMuscles, true).apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(50)) })
        val hInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(8), 0, 0, 0) }
        val hTitle = makeText(session.routineName, 16f, true)
        hTitle.maxLines = 1; hTitle.ellipsize = android.text.TextUtils.TruncateAt.END
        (hTitle.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        hInfo.addView(hTitle)
        val hSub = makeText("${session.focus} • ${musclesLabel(allMuscles)}", 11f, false, Theme.textSecondary)
        hSub.maxLines = 1; hSub.ellipsize = android.text.TextUtils.TruncateAt.END
        (hSub.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        hInfo.addView(hSub)
        if (session.startedAt > 0 && session.completed) {
            val t = makeText("Time: ${WorkoutTimer.formatDuration(session.elapsedSec)}", 11f, true, Theme.primary)
            (t.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
            hInfo.addView(t)
        }
        hRow.addView(hInfo)
        if (!session.completed) {
            db.ensureSessionStarted(sessionId)
            val chip = TimerChipView(this)
            chip.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34))
            chip.bind(this, db, db.getSession(sessionId) ?: session) {}
            chip.startTicking()
            timerChip = chip
            hRow.addView(chip)
        }
        header.addView(hRow)
        root.addView(header)

        // ONE PAGE exercise region (v2.5.1, Mahesh: "On doing exercise page it
        // should be one page"): ALL exercises visible, each row an equal
        // weight share of the flexing region; an expanded (accordion) row
        // takes a larger share while the rest compress to their floor.
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        if (exs.isEmpty()) {
            content.addView(makeText("No exercises in this session.", 14f))
        } else {
            // Section markers folded into each row's tag (no separate header rows,
            // they would steal height from the 13 rows).
            for (idx in exs.indices) content.addView(exerciseCard(exs[idx], idx, exs, unit))
        }
        root.addView(content)

        // Fixed bottom action bar
        addActionBar(root, session.completed)
    }

    /**
     * One-page list row (v2.5.1): equal weight share of the exercise region.
     * Collapsed: one compact line — name, section/status, set chips (tap a chip
     * to log/edit that set), reorder chevrons, +/− set, how-to toggle.
     * Expanded (accordion): takes a larger weight share and shows last-done,
     * set summary, video, posture (<=4) and cues (<=3) under the same line.
     * Logging writes exactly the same SessionSet rows as the old full rows.
     */
    private fun exerciseCard(ex: SessionExercise, idx: Int, exs: List<SessionExercise>, unit: String): LinearLayout {
        if (!howToDefaultApplied) { howToDefaultApplied = true; /* no auto-expand on the one-page list */ }
        val expanded = howToExpanded.contains(ex.id) && Beginner.beginnerMode(this)
        val card = cardLayout()
        card.setPadding(dp(6), dp(2), dp(6), dp(2))
        (card.layoutParams as LinearLayout.LayoutParams).apply {
            height = 0; weight = if (expanded) 3.2f else 1f; setMargins(0, dp(1), 0, dp(1))
        }
        val sets = db.getSets(ex.id)
        val status = db.effectiveStatus(ex)
        val allDone = sets.isNotEmpty() && sets.all { it.isDone }

        // ---- Compact main line ----
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        // Section color dot + status glyph
        val dot = TextView(this).apply {
            text = when (status) { "done" -> "✓"; "skipped" -> "↷"; else -> "●" }
            textSize = 11f
            setTextColor(if (status == "pending") sectionColor(ex.type) else Color.parseColor("#15803D"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(16), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        top.addView(dot)
        val titleTv = makeText("${idx + 1}. ${ex.name}", 12f, true)
        titleTv.maxLines = 1; titleTv.ellipsize = android.text.TextUtils.TruncateAt.END
        (titleTv.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; setMargins(0, 0, 0, 0) }
        strike(titleTv, allDone)
        if (status == "skipped") titleTv.setTextColor(Color.parseColor("#92400E"))
        top.addView(titleTv)
        // Set chips: one small chip per set; tap = log/edit that set.
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for (set in sets) {
            val chip = TextView(this).apply {
                text = if (set.isDone) "${set.setNumber}✓" else "${set.setNumber}"
                textSize = 11f
                setTextColor(if (set.isDone) Color.WHITE else Theme.textPrimary)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                gravity = Gravity.CENTER
                background = roundedBg(if (set.isDone) "#15803D" else "#EDF2F7", 10)
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(22)).apply { setMargins(dp(1), 0, dp(1), 0) }
                isClickable = true
                setOnClickListener { editSetDialog(ex, set, unit) }
            }
            chipRow.addView(chip)
        }
        top.addView(chipRow)
        // Compact controls: reorder, add/remove set, how-to accordion toggle.
        if (idx > 0) top.addView(smallBtn("↑", 22, 22) { db.swapSessionExerciseOrder(ex, exs[idx - 1]); render(ex.id) })
        if (idx < exs.size - 1) top.addView(smallBtn("↓", 22, 22) { db.swapSessionExerciseOrder(ex, exs[idx + 1]); render(ex.id) })
        top.addView(smallBtn("+", 22, 22) { db.addSet(ex.id); db.syncExerciseStatus(ex.id); render(ex.id) })
        top.addView(smallBtn("−", 22, 22) { db.removeLastSet(ex.id); db.syncExerciseStatus(ex.id); render(ex.id) })
        if (Beginner.beginnerMode(this)) {
            top.addView(smallBtn(if (expanded) "▴" else "▾", 22, 22) {
                if (howToExpanded.contains(ex.id)) howToExpanded.remove(ex.id) else { howToExpanded.clear(); howToExpanded.add(ex.id) }
                render(ex.id)
            })
        }
        card.addView(top)

        // One-line meta under the name line only when there is height for it is
        // folded into the expanded block; collapsed rows stay a single line so
        // 13 of them fit the region (target ~40dp on a 640dp viewport).

        // ---- Accordion expansion: larger weight share, others compress ----
        if (expanded) {
            val exMuscles = DbHelper.parseMuscles(ex.targetMuscles, ex.name)
            val meta = makeText("${sectionOf(ex.type)} • ${ex.equipment} • ${doItLine(sets)}${if (status == "skipped") " • Skipped" else ""}", 11f, true, Color.parseColor("#92400E"))
            meta.maxLines = 1; meta.ellipsize = android.text.TextUtils.TruncateAt.END
            (meta.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
            card.addView(meta)
            val last = db.lastPerformedForExercise(ex.name, sessionId)
            if (last != null) {
                val summary = last.sets.joinToString(", ") { s -> if (s.isBodyweight) "BW x${s.reps.ifBlank { "?" }}" else "${s.weight.ifBlank { "?" }} $unit x${s.reps.ifBlank { "?" }}" }
                val eff = if (last.effort.isNotBlank()) " • ${last.effort} — ${Beginner.effortSuggestion(last.effort, unit)}" else ""
                val lt = makeText("Last: ${DateUtil.display(last.date)} • $summary$eff", 11f, false, Color.parseColor("#15803D"))
                lt.maxLines = 1; lt.ellipsize = android.text.TextUtils.TruncateAt.END
                (lt.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
                card.addView(lt)
            } else {
                val ft = makeText("First time", 11f, false, Theme.textSecondary)
                (ft.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
                card.addView(ft)
            }
            if (sets.isNotEmpty()) {
                val sum = makeText("Sets: " + sets.joinToString(", ") { s -> (if (s.isBodyweight) "BW" else s.weight.ifBlank { "?" }) + "×" + s.reps.ifBlank { "?" } + if (s.isDone) "✓" else "" } + "  (tap a number chip to log)", 11f, false, Theme.textSecondary)
                sum.maxLines = 1; sum.ellipsize = android.text.TextUtils.TruncateAt.END
                (sum.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
                card.addView(sum)
            }
            if (ex.youtubeUrl.isNotBlank()) {
                val vid = primaryButtonWithIcon("Watch Form Video", R.drawable.ic_play) { openUrl(this, ex.youtubeUrl) }
                (vid.layoutParams as LinearLayout.LayoutParams).apply { height = dp(36); setMargins(0, dp(2), 0, dp(2)) }
                vid.textSize = 12f
                card.addView(vid)
            }
            val feel = makeText("What you'll feel: ${musclesLabel(exMuscles)}", 11f, false, Color.parseColor("#0369A1"))
            (feel.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
            card.addView(feel)
            val posture = ex.postureCheck.ifBlank { DbHelper.postureForName(ex.name, ex.type) }
            var truncated = false
            if (posture.isNotBlank()) {
                val items = posture.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                val ph = makeText("Check your posture:", 12f, true, Color.parseColor("#15803D"))
                (ph.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
                card.addView(ph)
                for (c in items.take(4)) {
                    val b = makeText("☐  $c", 11f, false)
                    b.maxLines = 1; b.ellipsize = android.text.TextUtils.TruncateAt.END
                    (b.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
                    card.addView(b)
                }
                if (items.size > 4) truncated = true
            }
            val cues = ex.cues.ifBlank { DbHelper.cuesForName(ex.name, ex.equipment, ex.type) }
            if (cues.isNotBlank()) {
                val items = cues.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                for (c in items.take(3)) {
                    val b = makeText("•  $c", 11f, false, Theme.textSecondary)
                    b.maxLines = 1; b.ellipsize = android.text.TextUtils.TruncateAt.END
                    (b.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
                    card.addView(b)
                }
                if (items.size > 3) truncated = true
            }
            if (truncated) {
                val dots = makeText("…", 11f, true, Theme.textTertiary)
                (dots.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
                card.addView(dots)
            }
            if (ex.equipment == "Machine" || ex.equipment == "Dumbbell" || ex.equipment == "Kettlebell") {
                val sl = makeText("Start light: use the lightest weight that feels easy first.", 11f, false, Color.parseColor("#92400E"))
                (sl.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
                card.addView(sl)
            }
        }
        return card
    }

    /** Tap-a-set-chip editor: same fields/writes as the old per-set row
     *  (weight, reps, BW, Done + steppers), in a dialog so the one-page list
     *  rows can stay ~40dp. Checking Done starts the 60s rest timer. */
    private fun editSetDialog(ex: SessionExercise, set: SessionSet, unit: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(4)) }
        val wStep = if (unit == "kg") 2.5 else 5.0
        val wEt = EditText(this).apply { setText(set.weight); hint = "weight"; styleEditText(this); textSize = 13f; isEnabled = !set.isBodyweight }
        val rEt = EditText(this).apply { setText(set.reps); hint = "reps"; styleEditText(this); textSize = 13f }
        val bwCb = CheckBox(this).apply { text = "Bodyweight (BW)"; setTextColor(Theme.textPrimary); textSize = 12f; isChecked = set.isBodyweight }
        val doneCb = CheckBox(this).apply { text = "Done"; setTextColor(Theme.textPrimary); textSize = 13f; isChecked = set.isDone }
        bwCb.setOnCheckedChangeListener { _, checked -> wEt.isEnabled = !checked }
        fun stepperRow(label: String, et: EditText, minus: () -> Unit, plus: () -> Unit): LinearLayout {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(TextView(this).apply { text = label; textSize = 12f; setTextColor(Theme.textSecondary); layoutParams = LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.WRAP_CONTENT) })
            r.addView(smallBtn("−", 36, 36) { minus() })
            et.layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            r.addView(et)
            r.addView(smallBtn("+", 36, 36) { plus() })
            return r
        }
        box.addView(stepperRow("Weight", wEt,
            { val v = ((wEt.text.toString().toDoubleOrNull() ?: 0.0) - wStep).coerceAtLeast(0.0); wEt.setText(if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()) },
            { val v = (wEt.text.toString().toDoubleOrNull() ?: 0.0) + wStep; wEt.setText(if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()) }))
        box.addView(stepperRow("Reps", rEt,
            { val v = ((rEt.text.toString().filter { it.isDigit() }.toIntOrNull() ?: 0) - 1).coerceAtLeast(0); rEt.setText(v.toString()) },
            { val v = (rEt.text.toString().filter { it.isDigit() }.toIntOrNull() ?: 0) + 1; rEt.setText(v.toString()) }))
        box.addView(bwCb); box.addView(doneCb)
        AlertDialog.Builder(this)
            .setTitle("${ex.name} — Set ${set.setNumber}")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                db.updateSet(set.copy(weight = wEt.text.toString(), reps = rEt.text.toString(), isBodyweight = bwCb.isChecked, isDone = doneCb.isChecked))
                db.syncExerciseStatus(ex.id)
                if (doneCb.isChecked && !set.isDone) startRestTimer()
                render(ex.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addActionBar(root: LinearLayout, completed: Boolean) {
        val session = db.getSession(sessionId) ?: return
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)) }
        fun ab(b: Button) { (b.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(44); setMargins(dp(2), 0, dp(2), 0) }; b.textSize = 12f; row.addView(b) }
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
        (discard.layoutParams as LinearLayout.LayoutParams).apply { height = dp(44); setMargins(dp(2), dp(4), dp(2), 0) }
        discard.textSize = 12f
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
