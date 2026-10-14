package com.mahesh.workouttracker

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * v3.9 set logger — ONE sheet for every entry point (card "+ Log set",
 * the list view's set chips / row taps, and the hub, which reaches the
 * logger through the card). Replaces the old per-entry dialogs
 * (CardSessionActivity.logSetDialog, SessionActivity.editSetDialog)
 * with the approved mock frame 6: every set is a row with chunky
 * steppers and a big ✓; rows arrive prefilled from last time.
 *
 * This is a UI replacement, NOT a logging-model change. The writes
 * and side effects are the old ones, re-hooked 1:1:
 *  - Logged sets write through db.updateSet (same rows, same fields);
 *    db.syncExerciseStatus runs after any commit that changed a row.
 *  - Repeat last time keeps the old semantics exactly: fill every set
 *    from the last performance + mark logged, written IMMEDIATELY,
 *    then the caller's post-log hook decides rest/advance.
 *  - Everything else is STAGED in the sheet and committed by the
 *    green Done bar. The caller receives a [Result] (how many sets
 *    flipped to logged, how many remain) and runs the SAME branch
 *    the old dialog ran: card flow — all logged → status done +
 *    advance-with-rest; some newly logged → the 30s set rest. List
 *    flow — a newly logged Main set → set rest, or exercise rest when
 *    the exercise finished. Un-logging or value-only edits never
 *    trigger a rest, exactly as before.
 *  - [onSnapshot] fires once, immediately before the first write of
 *    a commit (the card flow's undo snapshot; the list passes a no-op).
 */
object SetLogger {

    class Result(val newlyLogged: Int, val remaining: Int, val changed: Boolean)

    private class RowState(
        val orig: SessionSet,
        var weight: Double,
        var reps: Int,
        var isBw: Boolean,
        var done: Boolean
    )

    private fun fmtWeight(w: Double): String =
        if (w % 1.0 == 0.0) String.format(Locale.US, "%.0f", w) else String.format(Locale.US, "%.1f", w)

    /** Numeric editor (the old logger's Custom entry, preserved):
     *  decimal for weight, integer for reps; OK applies the value. */
    private fun customValueDialog(act: Activity, decimal: Boolean, current: Double, title: String, apply: (Double) -> Unit) {
        val et = EditText(act).apply {
            inputType = if (decimal) android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                        else android.text.InputType.TYPE_CLASS_NUMBER
            setText(if (decimal) (if (current % 1.0 == 0.0) current.toInt().toString() else current.toString()) else current.toInt().toString())
            setSelection(text.length)
            act.styleEditText(this)
        }
        AlertDialog.Builder(act).setTitle(title).setView(et)
            .setPositiveButton("OK") { _, _ ->
                val v = et.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                apply(if (decimal) v.coerceAtLeast(0.0) else v.coerceAtLeast(1.0))
            }
            .setNegativeButton("Cancel", null).show()
    }

    fun show(
        act: Activity,
        db: DbHelper,
        sessionId: Long,
        ex: SessionExercise,
        onSnapshot: () -> Unit,
        onCommit: (Result) -> Unit
    ) {
        val last = db.lastPerformedForExercise(ex.name, sessionId)

        fun makeRow(set: SessionSet, idx: Int): RowState {
            // Prefill (mock frame 6): an unlogged set takes its own
            // index's values from the last performance when they exist,
            // else the session set's stored values (which carry the
            // exercise's target sets/reps / most recent weight).
            val ls = if (!set.isDone) last?.sets?.getOrNull(idx) else null
            return if (ls != null) RowState(
                set,
                ls.weight.toDoubleOrNull() ?: 0.0,
                ls.reps.filter { it.isDigit() }.toIntOrNull() ?: 10,
                ls.isBodyweight, false
            ) else RowState(
                set,
                set.weight.toDoubleOrNull() ?: 0.0,
                set.reps.filter { it.isDigit() }.toIntOrNull() ?: 10,
                set.isBodyweight, set.isDone
            )
        }

        val rows = mutableListOf<RowState>()
        fun rebuildRows() {
            rows.clear()
            db.getSets(ex.id).forEachIndexed { i, s -> rows.add(makeRow(s, i)) }
        }
        rebuildRows()

        val sheet = Dialog(act)

        fun roundBtn(text: String, onClick: () -> Unit): TextView {
            return TextView(act).apply {
                this.text = text; textSize = 18f; gravity = Gravity.CENTER
                setTextColor(Theme.primary)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                background = act.roundedBg(Palettes.current.stepperBg, 999)
                layoutParams = LinearLayout.LayoutParams(act.dp(34), act.dp(34))
                isClickable = true
                setOnClickListener { onClick() }
            }
        }

        fun renderSheet() {
            val unit = WeekManager.unit(act)
            val wStep = if (unit == "kg") 2.5 else 5.0
            val root = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Theme.surface)
                    cornerRadii = floatArrayOf(act.dp(24).toFloat(), act.dp(24).toFloat(), act.dp(24).toFloat(), act.dp(24).toFloat(), 0f, 0f, 0f, 0f)
                }
                setPadding(act.dp(18), act.dp(8), act.dp(18), act.dp(16))
            }
            // Drag handle (carried over from the old sheet).
            root.addView(android.view.View(act).apply {
                background = act.roundedBg(Theme.stroke, 4)
                layoutParams = LinearLayout.LayoutParams(act.dp(44), act.dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, act.dp(2), 0, act.dp(10)) }
            })
            // Header: name + ⚖ unit bubble + ✕.
            val header = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val nameTv = act.makeText(ex.name, 17f, true)
            nameTv.maxLines = 2; nameTv.ellipsize = android.text.TextUtils.TruncateAt.END
            (nameTv.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; setMargins(0, 0, act.dp(8), 0) }
            header.addView(nameTv)
            val unitBubble = act.settingsIcon("⚖️", IconTints.LILAC, 38, 18f)
            unitBubble.isClickable = true
            unitBubble.setOnClickListener {
                // r4: one tap flips the app-wide unit pref in place —
                // the sheet re-renders with the new step + KG/LB labels
                // (stored values are unit-relative, exactly as the old
                // logger treated them; only labels/steps change).
                WeekManager.setUnit(act, if (unit == "kg") "lb" else "kg")
                renderSheet()
            }
            header.addView(unitBubble)
            val closeTv = TextView(act).apply {
                text = "✕"; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Theme.textSecondary)
                layoutParams = LinearLayout.LayoutParams(act.dp(38), act.dp(38)).apply { setMargins(act.dp(6), 0, 0, 0) }
                isClickable = true
                setOnClickListener { sheet.dismiss() }
            }
            header.addView(closeTv)
            root.addView(header)
            // Last-time line.
            val lastTv = if (last != null)
                act.makeText("Last time · ${DateUtil.display(last.date)} — ${lastTimeLine(last)}", 12.5f, true, Theme.primary)
            else
                act.makeText("No previous performance", 12.5f, false, Theme.textSecondary)
            (lastTv.layoutParams as LinearLayout.LayoutParams).setMargins(0, act.dp(2), 0, 0)
            lastTv.maxLines = 2
            root.addView(lastTv)
            // Repeat last time banner (old S1 semantics, mock styling).
            if (last != null) {
                val fillCount = minOf(rows.size, last.sets.size)
                val banner = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Palettes.current.doorGymBg); cornerRadius = act.dp(12).toFloat()
                        setStroke(act.dp(1), Palettes.current.doorGymBorder)
                    }
                    setPadding(act.dp(12), act.dp(9), act.dp(12), act.dp(9))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, act.dp(10), 0, 0) }
                }
                banner.addView(TextView(act).apply {
                    text = "↺"; textSize = 19f; setTextColor(Theme.primary); gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(act.dp(26), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                val bcol = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(act.dp(6), 0, act.dp(8), 0) }
                }
                val bt = act.makeText("Repeat last time", 13.5f, true)
                (bt.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
                bcol.addView(bt)
                val bs = act.makeText("Fills all $fillCount sets and logs them — one tap", 11.5f, false, Theme.textSecondary)
                (bs.layoutParams as LinearLayout.LayoutParams).setMargins(0, act.dp(1), 0, 0)
                bcol.addView(bs)
                banner.addView(bcol)
                banner.addView(TextView(act).apply {
                    text = "Fill"; textSize = 13.5f; setTextColor(Theme.primary)
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                    setPadding(act.dp(10), act.dp(6), act.dp(4), act.dp(6))
                    isClickable = true
                    setOnClickListener {
                        // The old Repeat button, 1:1: snapshot, fill +
                        // log immediately, sync status, dismiss, and let
                        // the caller's hook run the rest/advance branch.
                        onSnapshot()
                        val before = db.getSets(ex.id)
                        var newly = 0
                        for ((i, s) in before.withIndex()) {
                            val ls = last.sets.getOrNull(i) ?: continue
                            if (!s.isDone) newly++
                            db.updateSet(s.copy(weight = ls.weight, reps = ls.reps, isBodyweight = ls.isBodyweight, isDone = true))
                        }
                        db.syncExerciseStatus(ex.id)
                        val remaining = db.getSets(ex.id).count { !it.isDone }
                        sheet.dismiss()
                        onCommit(Result(newly, remaining, true))
                    }
                })
                root.addView(banner)
            }
            // Set rows.
            for ((idx, st) in rows.withIndex()) {
                val rowCard = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Palettes.current.sheetRow); cornerRadius = act.dp(14).toFloat()
                        setStroke(act.dp(1), Theme.stroke)
                    }
                    setPadding(act.dp(12), act.dp(8), act.dp(10), act.dp(10))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, act.dp(8), 0, 0) }
                }
                // Line 1: SET n micro-label + BW toggle pill.
                val line1 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val setLab = TextView(act).apply {
                    text = "SET ${st.orig.setNumber}"; textSize = 10.5f; setTextColor(Theme.textTertiary)
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                    letterSpacing = 0.08f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                line1.addView(setLab)
                line1.addView(TextView(act).apply {
                    text = "BW"; textSize = 10f
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                    if (st.isBw) { setTextColor(Color.WHITE); background = act.roundedBg("#5B21B6", 999) }
                    else { setTextColor(Theme.textTertiary); background = act.roundedBg(Theme.surfaceVariant, 999) }
                    setPadding(act.dp(8), act.dp(2), act.dp(8), act.dp(2))
                    isClickable = true
                    setOnClickListener { st.isBw = !st.isBw; renderSheet() }
                })
                rowCard.addView(line1)
                // Line 2: weight stepper · reps stepper · ✓ circle.
                val line2 = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, act.dp(6), 0, 0) }
                }
                fun valueStack(value: String, micro: String, onTap: () -> Unit): LinearLayout {
                    val stack = LinearLayout(act).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(act.dp(54), LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(act.dp(5), 0, act.dp(5), 0) }
                        isClickable = true
                        setOnClickListener { onTap() }
                    }
                    stack.addView(TextView(act).apply {
                        text = value; textSize = 16.5f; gravity = Gravity.CENTER; setTextColor(Theme.textPrimary)
                        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    })
                    stack.addView(TextView(act).apply {
                        text = micro; textSize = 9f; gravity = Gravity.CENTER; setTextColor(Theme.textTertiary)
                        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                        letterSpacing = 0.06f
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    })
                    return stack
                }
                // Weight stepper (step 2.5 kg / 5 lb, as the old logger).
                line2.addView(roundBtn("−") {
                    if (st.isBw) { st.isBw = false; st.weight = 0.0 } else st.weight = (st.weight - wStep).coerceAtLeast(0.0)
                    renderSheet()
                })
                line2.addView(valueStack(if (st.isBw) "BW" else fmtWeight(st.weight), unit.uppercase(Locale.US)) {
                    customValueDialog(act, true, st.weight, "Custom weight ($unit)") { v -> st.isBw = false; st.weight = v; renderSheet() }
                })
                line2.addView(roundBtn("+") {
                    if (st.isBw) { st.isBw = false; st.weight = wStep } else st.weight += wStep
                    renderSheet()
                })
                // Reps stepper (step 1).
                val repsGroup = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(act.dp(14), 0, 0, 0) }
                }
                repsGroup.addView(roundBtn("−") { st.reps = (st.reps - 1).coerceAtLeast(1); renderSheet() })
                repsGroup.addView(valueStack(st.reps.toString(), "REPS") {
                    customValueDialog(act, false, st.reps.toDouble(), "Custom reps") { v -> st.reps = v.toInt().coerceAtLeast(1); renderSheet() }
                })
                repsGroup.addView(roundBtn("+") { st.reps++; renderSheet() })
                line2.addView(repsGroup)
                line2.addView(android.view.View(act), LinearLayout.LayoutParams(0, 1, 1f))
                // The big ✓: filled green when logged, hollow when not.
                line2.addView(TextView(act).apply {
                    text = "✓"; textSize = 18f; gravity = Gravity.CENTER
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                    if (st.done) {
                        setTextColor(Color.WHITE)
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Theme.success) }
                        elevation = act.dp(2).toFloat()
                    } else {
                        setTextColor(Palettes.current.checkOffStroke)
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setStroke((2.5f * act.resources.displayMetrics.density).toInt(), Palettes.current.checkOffStroke)
                        }
                    }
                    layoutParams = LinearLayout.LayoutParams(act.dp(40), act.dp(40))
                    isClickable = true
                    setOnClickListener { st.done = !st.done; renderSheet() }
                })
                rowCard.addView(line2)
                // Minimal set removal, preserved from the list view's
                // − button (db.removeLastSet): long-press the LAST row.
                if (idx == rows.lastIndex && rows.size > 1) {
                    rowCard.setOnLongClickListener {
                        AlertDialog.Builder(act)
                            .setTitle("Remove set ${st.orig.setNumber}?")
                            .setMessage("Removes the last set of this exercise. Sets you already logged stay in history.")
                            .setPositiveButton("Remove") { _, _ ->
                                db.removeLastSet(ex.id)
                                db.syncExerciseStatus(ex.id)
                                rebuildRows()
                                renderSheet()
                            }
                            .setNegativeButton("Cancel", null).show()
                        true
                    }
                }
                root.addView(rowCard)
            }
            // Hint line (the mock's footer copy, unit-aware).
            val hint = act.makeText(
                "Tap a number to type it exactly · weight steps ±${if (unit == "kg") "2.5 kg" else "5 lb"}, reps ±1",
                11f, false, Theme.textTertiary)
            hint.gravity = Gravity.CENTER
            (hint.layoutParams as LinearLayout.LayoutParams).setMargins(0, act.dp(8), 0, 0)
            root.addView(hint)
            // + Add set (db.addSet — the list view's + semantics: the new
            // set copies the previous row's values).
            root.addView(TextView(act).apply {
                text = "+ Add set"; textSize = 13.5f; gravity = Gravity.CENTER; setTextColor(Theme.primary)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                background = GradientDrawable().apply {
                    setColor(Palettes.current.sheetRow); cornerRadius = act.dp(12).toFloat()
                    setStroke(act.dp(1), Color.parseColor("#B9C9D8"), act.dp(6).toFloat(), act.dp(4).toFloat())
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, act.dp(44)).apply { setMargins(0, act.dp(8), 0, 0) }
                isClickable = true
                setOnClickListener {
                    val newId = db.addSet(ex.id)
                    val fresh = db.getSets(ex.id)
                    val i = fresh.indexOfFirst { it.id == newId }
                    if (i >= 0) rows.add(makeRow(fresh[i], i))
                    renderSheet()
                }
            })
            // Done bar: commits the staged edits (the old Save Set).
            root.addView(TextView(act).apply {
                text = "Done"; textSize = 15.5f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                background = act.roundedBg(Theme.success, 14)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, act.dp(52)).apply { setMargins(0, act.dp(10), 0, 0) }
                isClickable = true
                setOnClickListener {
                    var changed = false
                    var newly = 0
                    for (st in rows) {
                        val wStr = if (st.isBw) "" else fmtWeight(st.weight)
                        val rStr = st.reps.toString()
                        if (wStr != st.orig.weight || rStr != st.orig.reps || st.isBw != st.orig.isBodyweight || st.done != st.orig.isDone) {
                            if (!changed) { onSnapshot(); changed = true }
                            if (st.done && !st.orig.isDone) newly++
                            db.updateSet(st.orig.copy(weight = wStr, reps = rStr, isBodyweight = st.isBw, isDone = st.done))
                        }
                    }
                    if (changed) db.syncExerciseStatus(ex.id)
                    val remaining = db.getSets(ex.id).count { !it.isDone }
                    sheet.dismiss()
                    onCommit(Result(newly, remaining, changed))
                }
            })
            sheet.setContentView(root)
        }

        renderSheet()
        sheet.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        sheet.show()
    }
}
