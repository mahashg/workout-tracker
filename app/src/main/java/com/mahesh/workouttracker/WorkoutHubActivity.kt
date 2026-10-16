package com.mahesh.workouttracker

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * v3.7 workout hub (approved mock screen 1): the screen a workout now
 * lives on. Picking body parts creates the session immediately with
 * ALL of its exercises; the hub lists every one of them in three
 * tinted section bands — Warm-Up / Workout / Cool-Down — with a
 * left-swipe Pass on pending rows (skip, never a rest; v3.9.1 — it
 * replaced the per-row Pass button) and its v3.9.2 mirror, a
 * right-swipe on a passed row to add it back to pending, drag
 * reorder within a section, and tap-to-start cards. It replaces both the old assemble page and
 * the sections screen in the start/resume flow (those stay in the
 * codebase, dormant).
 *
 * This is the ONE screen that scrolls, by the user's explicit request
 * ("a scroller through which I can easily scroll through and see all
 * the workouts") — an approved exception to the no-scroll rule, for
 * this list only. The Finish flow at the bottom is the sections
 * screen's, unchanged: same early-finish confirmation, Summary
 * celebrate path, Health Connect + Drive call sites.
 */
class WorkoutHubActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var sessionId: Long = -1
    private var created = false

    private var timerChip: TimerChipView? = null
    private var restChip: RestChipView? = null
    private var countTv: TextView? = null
    private var segBar: LinearLayout? = null
    private var recycler: RecyclerView? = null
    private var finishBtn: android.widget.Button? = null
    private var adapter: HubAdapter? = null

    private val sectionOrder = listOf("Warmup", "Main", "Stretch")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = DbHelper(this)
        sessionId = intent.getLongExtra("sessionId", -1)
        if (sessionId < 0) { finish(); return }
        buildLayout()
        created = true
        renderAll()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val id = intent.getLongExtra("sessionId", -1)
        if (id > 0) sessionId = id
        if (created) renderAll()
    }

    override fun onResume() { super.onResume(); if (created) renderAll() }
    override fun onPause() { super.onPause(); timerChip?.stopTicking(); timerChip?.foldNow() }

    // ---------- layout ----------

    private fun bandColors(type: String): Triple<Int, Int, Int> = when (type) {
        // (band bg, band ink, segment fill) — approved mock hexes.
        "Warmup" -> Triple(Color.parseColor("#FEF3C7"), Color.parseColor("#92400E"), Color.parseColor("#D97706"))
        "Stretch" -> Triple(Color.parseColor("#DCFCE7"), Color.parseColor("#166534"), Color.parseColor("#15803D"))
        else -> Triple(Color.parseColor("#DBEAFE"), Color.parseColor("#1E40AF"), Color.parseColor("#2563EB"))
    }

    private fun bandLabel(type: String): String = when (type) {
        "Warmup" -> "WARM-UP"; "Stretch" -> "COOL-DOWN"; else -> "WORKOUT"
    }

    /** Rounded rect in exact dp (Ui.roundedBg takes raw px — new v3.7
     *  code converts explicitly so dp-vs-px can never drift). */
    private fun rectBg(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 1): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
        }
    }

    private fun buildLayout() {
        val session = db.getSession(sessionId) ?: run { finish(); return }
        val root = fitRoot()
        setContentView(root)
        root.addView(topBar("Today's Workout", session.routineName))

        // Header card: elapsed timer chip + rest chip + "X of Y done",
        // then the 3-segment progress bar (mock screen 1).
        val header = cardLayout()
        (header.layoutParams as LinearLayout.LayoutParams).apply { setMargins(0, 0, 0, 0) }
        header.setPadding(dp(12), dp(8), dp(12), dp(10))
        val hRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (!session.completed) {
            db.ensureSessionStarted(sessionId)
            val chip = TimerChipView(this)
            chip.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30))
            chip.bind(this, db, db.getSession(sessionId) ?: session) {}
            // v3.12: swipe right on the timer bar = the Finish flow
            // (same confirm-or-finish the Finish button runs). No
            // RestTimer.attach here — the card owns rest completion;
            // this screen's rest chip just reflects the live engine.
            chip.onFinishSwipe = { onFinishTap() }
            chip.startTicking()
            timerChip = chip
            hRow.addView(chip)
        } else if (session.startedAt > 0) {
            hRow.addView(makeText("Time: ${WorkoutTimer.formatDuration(session.elapsedSec)}", 13f, true, Theme.textSecondary).apply {
                (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
            })
        }
        val count = makeText("", 13f, true, Theme.textPrimary)
        count.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(10), 0, 0, 0) }
        countTv = count
        hRow.addView(count)
        val rc = RestChipView(this)
        rc.bind(this)
        rc.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(8), 0, 0, 0) }
        restChip = rc
        hRow.addView(rc)
        header.addView(hRow)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).apply { setMargins(0, dp(8), 0, 0) }
        }
        segBar = bar
        header.addView(bar)
        root.addView(header)

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@WorkoutHubActivity)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(0, dp(4), 0, 0) }
            itemAnimator = null
        }
        recycler = rv
        root.addView(rv)
        val ith = ItemTouchHelper(HubDragCallback())
        ith.attachToRecyclerView(rv)
        hubTouchHelper = ith

        val fb = makeButton("Finish Workout") { onFinishTap() }
        tightButton(fb, 48, 12, primary = true)
        (fb.layoutParams as LinearLayout.LayoutParams).apply { height = dp(48); setMargins(0, dp(6), 0, 0) }
        finishBtn = fb
        root.addView(fb)
    }

    private var hubTouchHelper: ItemTouchHelper? = null

    // ---------- data / render ----------

    private sealed class HubItem {
        data class Band(val type: String, val handled: Int, val total: Int, val estMin: Int) : HubItem()
        data class Row(val ex: SessionExercise, val posInSection: Int) : HubItem()
    }

    private fun sectionEstMin(type: String, list: List<SessionExercise>): Int = when (type) {
        // Same heuristic family as the retired assemble page's estimate.
        "Warmup" -> list.size
        "Stretch" -> maxOf(1, (list.size + 1) / 2)
        else -> list.sumOf { db.getSets(it.id).size } * 2
    }

    private fun renderAll() {
        val session = db.getSession(sessionId) ?: run { finish(); return }
        // The header is built once (onCreate); refresh + re-tick the
        // timer chip here so returning to the hub resumes the clock.
        timerChip?.let { it.refresh(); it.startTicking() }
        restChip?.refresh()
        val exs = db.getSessionExercises(sessionId)
        val handled = exs.count { db.effectiveStatus(it) != "pending" }
        countTv?.text = "$handled of ${exs.size} done"

        // 3-segment bar: segment widths ∝ section sizes, each filled by
        // its section's handled fraction (mock screen 1).
        segBar?.removeAllViews()
        var firstSeg = true
        for (type in sectionOrder) {
            val list = exs.filter { it.type.equals(type, true) }
            if (list.isEmpty()) continue
            val (_, _, fill) = bandColors(type)
            val seg = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = rectBg(Color.parseColor("#E4EBF2"), 3)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, list.size.toFloat()).apply {
                    setMargins(if (firstSeg) 0 else dp(3), 0, 0, 0)
                }
            }
            firstSeg = false
            val h = list.count { db.effectiveStatus(it) != "pending" }
            if (h > 0) seg.addView(View(this).apply {
                background = rectBg(fill, 3)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, h.toFloat())
            })
            if (h < list.size) seg.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (list.size - h).toFloat())
            })
            segBar?.addView(seg)
        }

        val items = mutableListOf<HubItem>()
        for (type in sectionOrder) {
            val list = exs.filter { it.type.equals(type, true) }
            if (list.isEmpty()) continue
            items.add(HubItem.Band(type, list.count { db.effectiveStatus(it) != "pending" }, list.size, sectionEstMin(type, list)))
            for ((i, ex) in list.withIndex()) items.add(HubItem.Row(ex, i + 1))
        }
        val nextUpId = exs.firstOrNull { db.effectiveStatus(it) == "pending" }?.id ?: -1L
        val ad = HubAdapter(items, nextUpId)
        adapter = ad
        recycler?.adapter = ad

        val allHandled = exs.isNotEmpty() && exs.all { db.effectiveStatus(it) != "pending" }
        finishBtn?.let { fb ->
            if (allHandled) fb.background = GradientDrawable().apply { setColor(Theme.success); cornerRadius = dp(12).toFloat() }
            else tightButton(fb, 48, 12, primary = true) // re-applies the primary ripple background
        }
        if (session.completed) finishBtn?.visibility = View.GONE
    }

    private fun onFinishTap() {
        val exs = db.getSessionExercises(sessionId)
        val allHandled = exs.isNotEmpty() && exs.all { db.effectiveStatus(it) != "pending" }
        if (!allHandled) {
            AlertDialog.Builder(this).setTitle("Finish early?")
                .setMessage("Some exercises are not done or skipped yet. Finish anyway?")
                .setPositiveButton("Finish") { _, _ -> doFinish() }
                .setNegativeButton("Keep going", null).show()
        } else doFinish()
    }

    /** Identical to the sections screen's finish: timer finalize, mark
     *  completed, Drive backup + Health Connect sync call sites, then
     *  the celebrating Summary. */
    private fun doFinish() {
        val fresh = db.getSession(sessionId)
        if (fresh != null) WorkoutTimer.finish(this, db, fresh)
        db.setSessionCompleted(sessionId, true)
        DriveBackup.backupSilently(this, db)
        HealthSync.syncSessionIfEnabled(this, db, sessionId)
        startActivity(Intent(this, SummaryActivity::class.java).apply { putExtra("sessionId", sessionId); putExtra("celebrate", true) })
        finish()
    }

    private fun openCard(ex: SessionExercise) {
        startActivity(Intent(this, CardSessionActivity::class.java).apply {
            putExtra("sessionId", sessionId); putExtra("sectionType", ex.type); putExtra("exerciseId", ex.id)
        })
    }

    private fun passExercise(ex: SessionExercise) {
        // Same semantics as the card's Skip: a status write only.
        // A pass NEVER starts a rest.
        db.setSessionExerciseStatus(ex.id, "skipped")
        Toast.makeText(this, "Passed ${ex.name}", Toast.LENGTH_SHORT).show()
        renderAll()
    }

    /** v3.9.2: the mirror of passExercise — a RIGHT swipe on a passed
     *  row writes it back to "pending" through the same status path.
     *  Only the status column changes: any sets logged before the pass
     *  stay exactly as they are, and restoring NEVER starts a rest.
     *  The full re-render recomputes up-next highlighting as usual. */
    private fun restoreExercise(ex: SessionExercise) {
        db.setSessionExerciseStatus(ex.id, "pending")
        Toast.makeText(this, "Added back ${ex.name}", Toast.LENGTH_SHORT).show()
        renderAll()
    }

    // ---------- drag reorder (within a section only) ----------

    private inner class HubDragCallback : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
            if (viewHolder is BandHolder) 0 else super.getDragDirs(recyclerView, viewHolder)
        /** v3.9.1: LEFT swipe = Pass (Mahesh's request — it replaces
         *  the per-row Pass button). Swipe is offered ONLY on rows
         *  still pending — the exact set the Pass button showed for.
         *  v3.9.2 adds the mirror: RIGHT swipe = Add back, offered
         *  ONLY on passed (skipped) rows. Done rows get neither
         *  direction — logged work is never undone by a swipe — and
         *  bands get 0 both ways. Drag stays handle-driven (below),
         *  so the two never fight. */
        override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            val row = viewHolder as? RowHolder ?: return 0
            val item = adapter?.items?.getOrNull(row.bindingAdapterPosition) as? HubItem.Row ?: return 0
            return when (db.effectiveStatus(item.ex)) {
                "pending" -> ItemTouchHelper.LEFT
                "skipped" -> ItemTouchHelper.RIGHT
                else -> 0
            }
        }
        override fun isLongPressDragEnabled(): Boolean = false
        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val ad = adapter ?: return false
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            // Rows swap only with a row of the SAME section — a band is
            // never a swap target, so a drag can never cross sections.
            return ad.moveRow(from, to)
        }
        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            // LEFT: the IDENTICAL write the Pass button performed:
            // status "skipped" via passExercise (never a rest, no
            // advance side effects), then the full re-render it always
            // did — so a swiped row renders exactly like a
            // button-passed one. RIGHT (v3.9.2): the reverse write via
            // restoreExercise — status "pending", same re-render.
            val item = adapter?.items?.getOrNull(viewHolder.bindingAdapterPosition) as? HubItem.Row ?: return
            when (direction) {
                ItemTouchHelper.LEFT -> if (db.effectiveStatus(item.ex) == "pending") passExercise(item.ex)
                ItemTouchHelper.RIGHT -> if (db.effectiveStatus(item.ex) == "skipped") restoreExercise(item.ex)
            }
        }
        /** Swipe cues: as the row slides left, a pending-toned panel
         *  with "✕ PASS" is revealed behind its right edge — the same
         *  grey/✕ treatment a passed row wears afterwards. v3.9.2
         *  mirrors it on the other side: sliding right reveals
         *  "↩ ADD BACK" behind the row's LEFT edge, in the same
         *  pending palette treatment the row returns to. */
        override fun onChildDraw(c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && viewHolder is RowHolder && dX != 0f) {
                val v = viewHolder.itemView
                val bg = rectBg(Theme.pendingBg, 12)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Theme.pendingFg; textSize = dp(12).toFloat()
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                }
                val baseline = (v.top + v.bottom) / 2f - (paint.descent() + paint.ascent()) / 2f
                if (dX < 0) {
                    bg.setBounds((v.right + dX).toInt(), v.top, v.right, v.bottom)
                    bg.draw(c)
                    paint.textAlign = Paint.Align.RIGHT
                    c.drawText("✕ PASS", (v.right - dp(16)).toFloat(), baseline, paint)
                } else {
                    bg.setBounds(v.left, v.top, (v.left + dX).toInt(), v.bottom)
                    bg.draw(c)
                    paint.textAlign = Paint.Align.LEFT
                    c.drawText("↩ ADD BACK", (v.left + dp(16)).toFloat(), baseline, paint)
                }
            }
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }

    // ---------- adapter ----------

    private inner class HubAdapter(val items: MutableList<HubItem>, val nextUpId: Long) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int): Int = if (items[position] is HubItem.Band) 0 else 1
        override fun getItemCount(): Int = items.size

        fun moveRow(from: Int, to: Int): Boolean {
            val a = items.getOrNull(from) as? HubItem.Row ?: return false
            val b = items.getOrNull(to) as? HubItem.Row ?: return false
            if (!a.ex.type.equals(b.ex.type, true)) return false
            items[from] = b; items[to] = a
            // Persist exactly like the rest of the app: swap sortOrders.
            db.swapSessionExerciseOrder(a.ex, b.ex)
            notifyItemMoved(from, to)
            return true
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 0) {
                val band = LinearLayout(this@WorkoutHubActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    layoutParams = recyclerLp(top = 10, bottom = 2)
                }
                val label = TextView(this@WorkoutHubActivity).apply {
                    textSize = 13f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val est = TextView(this@WorkoutHubActivity).apply {
                    textSize = 11.5f
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                band.addView(label); band.addView(est)
                return BandHolder(band, label, est)
            }
            val row = LinearLayout(this@WorkoutHubActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(8), dp(8))
                layoutParams = recyclerLp(top = 3, bottom = 3)
            }
            val holder = RowHolder(row)
            holder.circle = TextView(this@WorkoutHubActivity).apply {
                textSize = 12f; gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            }
            row.addView(holder.circle)
            val col = LinearLayout(this@WorkoutHubActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(10), 0, dp(6), 0) }
            }
            holder.micro = TextView(this@WorkoutHubActivity).apply {
                text = "UP NEXT — TAP TO START"; textSize = 10f
                setTextColor(Theme.primary)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                letterSpacing = 0.06f
                (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, 0, 0)
            }
            col.addView(holder.micro)
            val nameRow = LinearLayout(this@WorkoutHubActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            holder.nameTv = TextView(this@WorkoutHubActivity).apply {
                textSize = 14.5f; setTextColor(Theme.textPrimary)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            nameRow.addView(holder.nameTv)
            holder.tagPill = TextView(this@WorkoutHubActivity).apply {
                textSize = 10f; setTextColor(Color.parseColor("#3730A3"))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = rectBg(Color.parseColor("#E0E7FF"), 999)
                setPadding(dp(7), dp(2), dp(7), dp(2)); maxLines = 1
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(6), 0, 0, 0) }
            }
            nameRow.addView(holder.tagPill)
            col.addView(nameRow)
            holder.lastTv = TextView(this@WorkoutHubActivity).apply {
                textSize = 11.5f; setTextColor(Theme.textSecondary)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }
            col.addView(holder.lastTv)
            row.addView(col)
            // v3.9.1: no Pass button — LEFT swipe on a pending row is
            // the Pass now (HubDragCallback); v3.9.2: RIGHT swipe on a
            // passed row adds it back. The reclaimed width goes to the
            // name/last-time column.
            holder.handle = TextView(this@WorkoutHubActivity).apply {
                text = "⋮⋮"; textSize = 16f; setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(4), dp(2), dp(4))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            row.addView(holder.handle)
            return holder
        }

        private fun recyclerLp(top: Int, bottom: Int): RecyclerView.LayoutParams =
            RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(top), 0, dp(bottom))
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is HubItem.Band -> {
                    val h = holder as BandHolder
                    val (bg, ink, _) = bandColors(item.type)
                    h.band.background = rectBg(bg, 10)
                    h.label.setTextColor(ink)
                    h.label.text = "${bandLabel(item.type)} · ${item.handled} of ${item.total} done"
                    h.est.setTextColor(ink)
                    h.est.text = "≈${item.estMin} min"
                }
                is HubItem.Row -> bindRow(holder as RowHolder, item)
            }
        }

        private fun bindRow(holder: RowHolder, item: HubItem.Row) {
            val ex = item.ex
            val status = db.effectiveStatus(ex)
            val isNext = ex.id == nextUpId
            holder.row.background = if (isNext) rectBg(Theme.surface, 12, Theme.primary, 2)
                                    else rectBg(Theme.surface, 12, Theme.stroke, 1)
            holder.micro.visibility = if (isNext) View.VISIBLE else View.GONE
            // Status circle: done = green ✓; passed = grey ✕; to-do =
            // neutral ring with the position number inside its section.
            when (status) {
                "done" -> {
                    holder.circle.text = "✓"; holder.circle.setTextColor(Color.WHITE); holder.circle.textSize = 12f
                    holder.circle.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Theme.success) }
                }
                "skipped" -> {
                    holder.circle.text = "✕"; holder.circle.setTextColor(Color.parseColor("#64748B")); holder.circle.textSize = 11f
                    holder.circle.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#E2E8F0")) }
                }
                else -> {
                    holder.circle.text = item.posInSection.toString(); holder.circle.setTextColor(Color.parseColor("#94A3B8")); holder.circle.textSize = 11f
                    holder.circle.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setColor(Color.WHITE)
                        setStroke(dp(2), Color.parseColor("#C4CFDB"))
                    }
                }
            }
            holder.nameTv.text = ex.name
            holder.nameTv.setTextColor(if (status == "skipped") Theme.textSecondary else Theme.textPrimary)
            strike(holder.nameTv, status == "skipped")
            // Body-part tag pill: Main rows only (mock screen 1).
            if (ex.type.equals("Main", true)) {
                val partId = db.sessionExercisePart(ex)
                holder.tagPill.text = BodyParts.name(partId)
                holder.tagPill.visibility = if (partId.isNotBlank()) View.VISIBLE else View.GONE
            } else holder.tagPill.visibility = View.GONE
            val last = db.lastPerformedForExercise(ex.name, sessionId)
            holder.lastTv.text = if (last != null) "Last: ${lastTimeLine(last)}" else {
                val sets = db.getSets(ex.id)
                val reps = sets.firstOrNull()?.reps?.takeIf { it.isNotBlank() }
                if (reps != null) "New exercise · ${sets.size} × $reps" else "New exercise"
            }
            holder.row.setOnClickListener { openCard(ex) }
            holder.handle.setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN) hubTouchHelper?.startDrag(holder)
                false
            }
        }
    }
}

// File-level holders (no activity reference needed — views only).
private class BandHolder(val band: LinearLayout, val label: TextView, val est: TextView) : RecyclerView.ViewHolder(band)

private class RowHolder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
    lateinit var circle: TextView
    lateinit var micro: TextView
    lateinit var nameTv: TextView
    lateinit var tagPill: TextView
    lateinit var lastTv: TextView
    lateinit var handle: TextView
}
