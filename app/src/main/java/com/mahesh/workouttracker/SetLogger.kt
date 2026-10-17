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

    fun fmtWeight(w: Double): String =
        if (w % 1.0 == 0.0) String.format(Locale.US, "%.0f", w) else String.format(Locale.US, "%.1f", w)

    /** v3.13: the mutable state behind one [buildSetPanel] — shared by
     *  the card's inline logger and the sheet's active row. */
    class SetPanelState(var weight: Double, var reps: Int, var isBw: Boolean)

    /** v3.13: prefill for the panel, replicating show()'s makeRow value
     *  rules EXACTLY: an unlogged set takes the last performance's set
     *  at the same index when present (weight / digit-reps / BW flag,
     *  with a bodyweight-equipment exercise forcing BW), else the
     *  stored set's own values under the same BW rule. */
    fun panelPrefill(db: DbHelper, sessionId: Long, ex: SessionExercise, set: SessionSet, idx: Int): SetPanelState {
        val bwExercise = ex.equipment.equals("Bodyweight", ignoreCase = true)
        val last = db.lastPerformedForExercise(ex.name, sessionId)
        val ls = if (!set.isDone) last?.sets?.getOrNull(idx) else null
        return if (ls != null) SetPanelState(
            ls.weight.toDoubleOrNull() ?: 0.0,
            ls.reps.filter { it.isDigit() }.toIntOrNull() ?: 10,
            ls.isBodyweight || bwExercise
        ) else SetPanelState(
            set.weight.toDoubleOrNull() ?: 0.0,
            set.reps.filter { it.isDigit() }.toIntOrNull() ?: 10,
            set.isBodyweight || bwExercise
        )
    }

    /** v3.13 (r4 Screen 6 panel): the shared calm logger panel — a
     *  header row (micro title + BW pill + unit/last-time text), the
     *  reps / weight stepper columns and the hero ✓ that logs. Every
     *  mutation writes into [state], refreshes the affected value
     *  view and fires [onStateChange]; the caller owns the actual DB
     *  write, which happens only in [onLog]. [onUnitFlip] is non-null
     *  only in the sheet, where the header-right text is the unit
     *  toggle; on the card it is a plain last-time label. */
    fun buildSetPanel(
        act: Activity,
        title: String,
        headerRight: String,
        bwExercise: Boolean,
        unitIsKg: Boolean,
        state: SetPanelState,
        onUnitFlip: (() -> Unit)?,
        onStateChange: () -> Unit,
        onLog: () -> Unit
    ): LinearLayout {
        val panel = act.bendPanel()
        var weightValue: TextView? = null
        var bwPill: TextView? = null
        fun refreshBwPill() {
            bwPill?.apply {
                if (state.isBw) { setTextColor(Color.WHITE); background = act.roundedBg("#5B21B6", 999) }
                else { setTextColor(Theme.textTertiary); background = act.roundedBg(Theme.surfaceVariant, 999) }
            }
        }
        fun refreshWeight() {
            weightValue?.text = if (state.isBw) "BW" else fmtWeight(state.weight)
            refreshBwPill()
        }
        // Header row: micro title + spacer + BW pill + header-right text.
        val header = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        header.addView(act.bendMicro(title, Theme.primary))
        header.addView(android.view.View(act), LinearLayout.LayoutParams(0, 1, 1f))
        if (!bwExercise) {
            val pill = TextView(act).apply {
                text = "BW"; textSize = 10f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                setPadding(act.dp(8), act.dp(2), act.dp(8), act.dp(2))
                isClickable = true
                setOnClickListener { state.isBw = !state.isBw; refreshWeight(); onStateChange() }
            }
            bwPill = pill
            refreshBwPill()
            header.addView(pill)
        }
        header.addView(TextView(act).apply {
            text = headerRight; textSize = 12f; setTextColor(Theme.textSecondary)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(act.dp(8), 0, 0, 0) }
            if (onUnitFlip != null) {
                isClickable = true
                setOnClickListener { onUnitFlip() }
            }
        })
        panel.addView(header)
        // Stepper columns: reps first, weight second (as the sheet's
        // rows were ordered); a bodyweight exercise keeps reps only.
        val wStep = if (unitIsKg) 2.5 else 5.0
        val cols = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, act.dp(14), 0, 0) }
        }
        fun stepperColumn(label: String, isWeight: Boolean, first: Boolean): LinearLayout {
            val col = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (!first) setMargins(act.dp(14), 0, 0, 0) }
            }
            col.addView(act.bendMicro(label, Theme.textTertiary).apply { gravity = Gravity.CENTER })
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, act.dp(8), 0, 0) }
            }
            val minus = act.bendSoftCircle("−", 36, 17f)
            minus.isClickable = true
            val value = TextView(act).apply {
                textSize = 22f; gravity = Gravity.CENTER; setTextColor(Theme.textPrimary)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                setFontFeatureSettings("tnum")
                minWidth = act.dp(54)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
            }
            val plus = act.bendSoftCircle("+", 36, 17f)
            plus.isClickable = true
            if (isWeight) {
                weightValue = value
                value.text = if (state.isBw) "BW" else fmtWeight(state.weight)
                minus.setOnClickListener {
                    if (state.isBw) { state.isBw = false; state.weight = 0.0 } else state.weight = (state.weight - wStep).coerceAtLeast(0.0)
                    refreshWeight(); onStateChange()
                }
                value.setOnClickListener {
                    customValueDialog(act, true, state.weight, "Custom weight (${if (unitIsKg) "kg" else "lb"})") { v -> state.isBw = false; state.weight = v; refreshWeight(); onStateChange() }
                }
                plus.setOnClickListener {
                    if (state.isBw) { state.isBw = false; state.weight = wStep } else state.weight += wStep
                    refreshWeight(); onStateChange()
                }
            } else {
                value.text = state.reps.toString()
                minus.setOnClickListener { state.reps = (state.reps - 1).coerceAtLeast(1); value.text = state.reps.toString(); onStateChange() }
                value.setOnClickListener {
                    customValueDialog(act, false, state.reps.toDouble(), "Custom reps") { v -> state.reps = v.toInt().coerceAtLeast(1); value.text = state.reps.toString(); onStateChange() }
                }
                plus.setOnClickListener { state.reps++; value.text = state.reps.toString(); onStateChange() }
            }
            row.addView(minus); row.addView(value); row.addView(plus)
            col.addView(row)
            return col
        }
        cols.addView(stepperColumn("REPS", isWeight = false, first = true))
        if (!bwExercise) cols.addView(stepperColumn("WEIGHT · ${if (unitIsKg) "KG" else "LB"}", isWeight = true, first = false))
        panel.addView(cols)
        // The hero ✓ that logs this set.
        val logBtn = act.bendHeroCircle("✓", Theme.success, 58, 22f)
        logBtn.layoutParams = LinearLayout.LayoutParams(act.dp(58), act.dp(58)).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, act.dp(16), 0, 0) }
        logBtn.isClickable = true
        logBtn.setOnClickListener { onLog() }
        panel.addView(logBtn)
        return panel
    }

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

        // v3.10 (Mahesh's spec): a Bodyweight-equipment exercise logs
        // REPS ONLY. Its rows are forced to the app's BW representation
        // (isBodyweight with an empty weight string — the same
        // convention the session-set factory uses when it creates a
        // bodyweight exercise's sets, and the one the BW pill toggles),
        // and the sheet drops the weight stepper, the per-row BW pill
        // and the ⚖ unit bubble entirely. Weighted exercises render
        // and behave exactly as before.
        val bwExercise = ex.equipment.equals("Bodyweight", ignoreCase = true)

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
                ls.isBodyweight || bwExercise, false
            ) else RowState(
                set,
                set.weight.toDoubleOrNull() ?: 0.0,
                set.reps.filter { it.isDigit() }.toIntOrNull() ?: 10,
                set.isBodyweight || bwExercise, set.isDone
            )
        }

        val rows = mutableListOf<RowState>()
        fun rebuildRows() {
            rows.clear()
            db.getSets(ex.id).forEachIndexed { i, s -> rows.add(makeRow(s, i)) }
        }
        rebuildRows()

        // v3.13 (r4 Screen 6): ONE row is expanded as the logger panel;
        // the rest are quiet tint bands. activeIdx survives renderSheet()
        // re-renders — it lives here, not in the render pass.
        var activeIdx = rows.indexOfFirst { !it.done }.let { if (it >= 0) it else 0 }

        val sheet = Dialog(act)

        fun renderSheet() {
            if (activeIdx !in rows.indices) activeIdx = 0
            val unit = WeekManager.unit(act)
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
            // v3.10: no ⚖ bubble for a bodyweight exercise — there is
            // no weight to unit-flip.
            if (!bwExercise) {
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
            }
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
            // Repeat last time (old S1 semantics): r4 Screen 6 makes it
            // ONE quiet centered line — the tap handler is the EXACT
            // current banner Fill handler body.
            if (last != null) {
                root.addView(TextView(act).apply {
                    text = "↻ Repeat last time"; textSize = 13.5f; gravity = Gravity.CENTER; setTextColor(Theme.primary)
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                    setPadding(act.dp(10), act.dp(10), act.dp(10), act.dp(10))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, act.dp(10), 0, 0) }
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
            }
            // Set rows (r4 Screen 6): the ACTIVE row is the shared
            // logger panel; every other row is a quiet tint band that
            // expands on tap. Staging is unchanged — panel edits copy
            // back into the RowState and Done below commits them.
            for ((idx, st) in rows.withIndex()) {
                if (idx == activeIdx) {
                    val panelState = SetPanelState(st.weight, st.reps, st.isBw)
                    val panel = buildSetPanel(
                        act,
                        "SET ${st.orig.setNumber}",
                        "⚖ ${unit.uppercase(Locale.US)} · tap for ${if (unit == "kg") "lb" else "kg"}",
                        bwExercise,
                        unitIsKg = unit == "kg",
                        state = panelState,
                        onUnitFlip = {
                            WeekManager.setUnit(act, if (unit == "kg") "lb" else "kg")
                            renderSheet()
                        },
                        onStateChange = {
                            st.weight = panelState.weight
                            st.reps = panelState.reps
                            st.isBw = panelState.isBw
                        },
                        onLog = {
                            st.done = !st.done
                            if (st.done) {
                                val next = rows.indices.firstOrNull { it > idx && !rows[it].done }
                                if (next != null) activeIdx = next
                            }
                            renderSheet()
                        }
                    )
                    panel.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, act.dp(8), 0, 0) }
                    // Minimal set removal, preserved from the list view's
                    // − button (db.removeLastSet): long-press the LAST row.
                    if (idx == rows.lastIndex && rows.size > 1) {
                        panel.setOnLongClickListener {
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
                    root.addView(panel)
                } else {
                    val band = LinearLayout(act).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        background = GradientDrawable().apply {
                            setColor(Palettes.current.surface); cornerRadius = act.dp(16).toFloat()
                        }
                        setPadding(act.dp(14), act.dp(12), act.dp(14), act.dp(12))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, act.dp(8), 0, 0) }
                        isClickable = true
                        setOnClickListener { activeIdx = idx; renderSheet() }
                    }
                    band.addView(TextView(act).apply {
                        text = "Set ${st.orig.setNumber} · ${if (st.isBw) "BW" else fmtWeight(st.weight) + " " + unit} × ${st.reps}"
                        textSize = 15.5f; setTextColor(Theme.textPrimary)
                        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    band.addView(TextView(act).apply {
                        if (st.done) {
                            text = "✓"; setTextColor(Theme.success)
                        } else {
                            text = "›"; setTextColor(Theme.textTertiary)
                        }
                        textSize = 15f; gravity = Gravity.CENTER
                        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                    })
                    // Long-press removal also lives on the band when the
                    // last row is not the active one.
                    if (idx == rows.lastIndex && rows.size > 1) {
                        band.setOnLongClickListener {
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
                    root.addView(band)
                }
            }
            // Hint line (the mock's footer copy, unit-aware).
            // v3.10: a bodyweight sheet has no weight steps to describe.
            val hint = act.makeText(
                if (bwExercise) "Tap a number to type it exactly · reps ±1"
                else "Tap a number to type it exactly · weight steps ±${if (unit == "kg") "2.5 kg" else "5 lb"}, reps ±1",
                11f, false, Theme.textTertiary)
            hint.gravity = Gravity.CENTER
            (hint.layoutParams as LinearLayout.LayoutParams).setMargins(0, act.dp(8), 0, 0)
            root.addView(hint)
            // + Add set (db.addSet — the list view's + semantics: the new
            // set copies the previous row's values).
            root.addView(TextView(act).apply {
                text = "+ Add set"; textSize = 13.5f; gravity = Gravity.CENTER; setTextColor(Theme.primary)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                setPadding(act.dp(10), act.dp(10), act.dp(10), act.dp(10))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, act.dp(8), 0, 0) }
                isClickable = true
                setOnClickListener {
                    val newId = db.addSet(ex.id)
                    val fresh = db.getSets(ex.id)
                    val i = fresh.indexOfFirst { it.id == newId }
                    if (i >= 0) {
                        rows.add(makeRow(fresh[i], i))
                        // v3.13: the new row becomes the active panel row.
                        activeIdx = rows.lastIndex
                    }
                    renderSheet()
                }
            })
            // Done bar: commits the staged edits (the old Save Set) —
            // r4 Screen 6 restyles it as a wide PRIMARY pill; the commit
            // body below is byte-identical.
            root.addView(TextView(act).apply {
                text = "Done with this exercise"; textSize = 15.5f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                background = act.roundedBg(Theme.primary, 999)
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
