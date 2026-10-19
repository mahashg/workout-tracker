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
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
    private var hairline: BendHairlineView? = null
    private var recycler: RecyclerView? = null
    private var pauseDisc: TextView? = null
    private var searchDisc: TextView? = null
    private var searchRow: LinearLayout? = null
    private var searchInput: EditText? = null
    private var footTv: TextView? = null
    private var searchOpen: Boolean = false
    private var searchQuery: String = ""
    private var adapter: HubAdapter? = null

    private fun isSearching(): Boolean = searchQuery.trim().isNotEmpty()

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

        // v3.13 Bend header (r4 mock Screen 5): no topBar, no header
        // card — a calm plain column: hairline progress, a top row
        // (screen name / routine / count on the left, the elapsed
        // timer chip on the right), then the rest chip on its own
        // left-aligned row (it self-hides when no rest is active).
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val hl = BendHairlineView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        hairline = hl
        header.addView(hl)
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(12), 0, 0) }
        }
        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        leftCol.addView(bendScreenName("Today's workout").apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        })
        leftCol.addView(bendMicro(session.routineName.uppercase()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(2), 0, 0) }
        })
        val count = makeText("", 12f, false, Theme.textSecondary)
        count.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(2), 0, 0) }
        countTv = count
        leftCol.addView(count)
        topRow.addView(leftCol)
        if (!session.completed) {
            db.ensureSessionStarted(sessionId)
            val chip = TimerChipView(this)
            chip.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30))
            chip.bind(this, db, db.getSession(sessionId) ?: session) { refreshPauseDisc() }
            // v3.12: swipe right on the timer bar = the Finish flow
            // (same confirm-or-finish the Finish button runs). No
            // RestTimer.attach here — the card owns rest completion;
            // this screen's rest chip just reflects the live engine.
            chip.onFinishSwipe = { onFinishTap() }
            chip.startTicking()
            timerChip = chip
            topRow.addView(chip)
            val pausedNow = WorkoutTimer.isPaused(this, sessionId)
            val pauseD = bendSoftCircle(if (pausedNow) "▶" else "⏸", 38, 15f)
            (pauseD.background as? GradientDrawable)?.setStroke(dp(1), Theme.stroke)
            if (pausedNow) pauseD.setTextColor(Theme.primary)
            pauseD.isClickable = true
            pauseD.setOnClickListener { timerChip?.togglePause() }
            (pauseD.layoutParams as LinearLayout.LayoutParams).setMargins(dp(9), 0, 0, 0)
            pauseDisc = pauseD
            topRow.addView(pauseD)
            val searchD = bendSoftCircle("🔍", 38, 15f)
            searchD.isClickable = true
            searchD.setOnClickListener { toggleSearch(!searchOpen) }
            (searchD.layoutParams as LinearLayout.LayoutParams).setMargins(dp(8), 0, 0, 0)
            searchDisc = searchD
            topRow.addView(searchD)
        } else if (session.startedAt > 0) {
            topRow.addView(makeText("Time: ${WorkoutTimer.formatDuration(session.elapsedSec)}", 13f, true, Theme.textSecondary).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 0) }
            })
        }
        header.addView(topRow)
        val sRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { setMargins(0, dp(10), 0, 0) }
            background = GradientDrawable().apply {
                setColor(Palettes.current.sheetRow); cornerRadius = dp(999).toFloat()
                setStroke(dp(2), Theme.primary)
            }
            setPadding(dp(15), 0, dp(6), 0)
        }
        sRow.addView(TextView(this).apply { text = "🔍"; textSize = 15f })
        val sInput = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(8), 0, 0, 0) }
            background = null
            hint = "Search name or equipment"
            textSize = 15f
            setTextColor(Theme.textPrimary)
            setHintTextColor(Theme.textTertiary)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString() ?: ""
                    renderAll()
                }
            })
        }
        searchInput = sInput
        sRow.addView(sInput)
        sRow.addView(TextView(this).apply {
            text = "✕"; textSize = 15f; setTextColor(Theme.textTertiary); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            setOnClickListener {
                if (!searchInput?.text.isNullOrEmpty()) searchInput?.setText("") else toggleSearch(false)
            }
        })
        searchRow = sRow
        header.addView(sRow)
        val rcRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val rc = RestChipView(this)
        rc.bind(this)
        rc.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, 0) }
        restChip = rc
        rcRow.addView(rc)
        header.addView(rcRow)
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

        val foot = makeText("Swipe a row left to Pass · right to bring it back", 12f, false, Theme.textTertiary).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), 0, dp(2)) }
        }
        footTv = foot
        root.addView(foot)
    }

    private fun refreshPauseDisc() { // ⏸ running / ▶ paused, mirrors the chip via its bind onToggle
        val disc = pauseDisc ?: return
        val paused = WorkoutTimer.isPaused(this, sessionId)
        disc.text = if (paused) "▶" else "⏸"
        disc.setTextColor(if (paused) Theme.primary else Theme.primaryDark)
    }

    private fun toggleSearch(open: Boolean) {
        searchOpen = open
        searchRow?.visibility = if (open) View.VISIBLE else View.GONE
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        if (open) {
            searchInput?.requestFocus()
            imm.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        } else {
            if (searchQuery.isNotEmpty()) { searchQuery = ""; searchInput?.setText("") }
            searchInput?.clearFocus()
            imm.hideSoftInputFromWindow(searchInput?.windowToken, 0)
            renderAll()
        }
    }

    private var hubTouchHelper: ItemTouchHelper? = null

    // ---------- data / render ----------

    private sealed class HubItem {
        data class Band(val type: String, val handled: Int, val total: Int, val estMin: Int) : HubItem()
        data class Row(val ex: SessionExercise, val posInSection: Int) : HubItem()
        object Finish : HubItem()
        data class Note(val text: String) : HubItem()
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

        // v3.13: the one Bend hairline — filled by the handled
        // fraction of the whole session (replaces the 3-segment bar).
        hairline?.setFraction(if (exs.isEmpty()) 0f else handled.toFloat() / exs.size)

        val allHandled = exs.isNotEmpty() && exs.all { db.effectiveStatus(it) != "pending" }
        val nextUpId = exs.firstOrNull { db.effectiveStatus(it) == "pending" }?.id ?: -1L

        val items = mutableListOf<HubItem>()
        if (isSearching()) {
            val q = searchQuery.trim().lowercase()
            val matches = exs.filter { ex ->
                val part = db.sessionExercisePart(ex)
                val partHaystack = (part + " " + runCatching { BodyParts.name(part) }.getOrDefault("")).lowercase()
                ex.name.lowercase().contains(q) || ex.equipment.lowercase().contains(q) || partHaystack.contains(q)
            }
            items.add(HubItem.Note(if (matches.isEmpty()) "No matches in this workout" else "${matches.size} match${if (matches.size == 1) "" else "es"} — by name and equipment"))
            if (matches.isNotEmpty()) {
                for (type in sectionOrder) {
                    val list = matches.filter { it.type.equals(type, true) }
                    if (list.isEmpty()) continue
                    items.add(HubItem.Band(type, list.count { db.effectiveStatus(it) != "pending" }, list.size, sectionEstMin(type, list)))
                    for ((i, ex) in list.withIndex()) items.add(HubItem.Row(ex, i + 1))
                }
            }
        } else {
            for (type in sectionOrder) {
                val list = exs.filter { it.type.equals(type, true) }
                if (list.isEmpty()) continue
                items.add(HubItem.Band(type, list.count { db.effectiveStatus(it) != "pending" }, list.size, sectionEstMin(type, list)))
                for ((i, ex) in list.withIndex()) items.add(HubItem.Row(ex, i + 1))
            }
            if (!session.completed) items.add(HubItem.Finish)
        }
        val ad = HubAdapter(items, nextUpId, allHandled)
        adapter = ad
        recycler?.adapter = ad

        footTv?.text = if (isSearching()) "Searching — drag and swipe are paused" else "Swipe a row left to Pass · right to bring it back"
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
        override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            if (isSearching()) return 0
            return if (viewHolder is BandHolder) 0 else super.getDragDirs(recyclerView, viewHolder)
        }
        /** v3.9.1: LEFT swipe = Pass (Mahesh's request — it replaces
         *  the per-row Pass button). Swipe is offered ONLY on rows
         *  still pending — the exact set the Pass button showed for.
         *  v3.9.2 adds the mirror: RIGHT swipe = Add back, offered
         *  ONLY on passed (skipped) rows. Done rows get neither
         *  direction — logged work is never undone by a swipe — and
         *  bands get 0 both ways. Drag stays handle-driven (below),
         *  so the two never fight. */
        override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            if (isSearching()) return 0
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
                val bg = rectBg(Theme.pendingBg, 18)
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

    private inner class HubAdapter(val items: MutableList<HubItem>, val nextUpId: Long, val allHandled: Boolean) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is HubItem.Band -> 0
            is HubItem.Row -> 1
            HubItem.Finish -> 2
            is HubItem.Note -> 3
        }
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
                // v3.13: bare label row — no band background, no est.
                val band = LinearLayout(this@WorkoutHubActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(2), dp(16), dp(2), dp(2))
                    layoutParams = recyclerLp(top = 16, bottom = 2)
                }
                val label = TextView(this@WorkoutHubActivity).apply {
                    textSize = 10.5f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    letterSpacing = 0.18f
                    setTextColor(Theme.textTertiary)
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                band.addView(label)
                return BandHolder(band, label)
            }
            if (viewType == 2) {
                val container = LinearLayout(this@WorkoutHubActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(14), 0, dp(8))
                    layoutParams = recyclerLp(top = 0, bottom = 0)
                }
                val btn = makeButton("⏹ Finish workout") { onFinishTap() }
                tightButton(btn, 52, 999, primary = true)
                (btn.layoutParams as LinearLayout.LayoutParams).height = dp(52)
                container.addView(btn)
                return FinishHolder(container, btn)
            }
            if (viewType == 3) {
                val tv = TextView(this@WorkoutHubActivity).apply {
                    textSize = 12f; setTextColor(Theme.textTertiary)
                    setPadding(dp(2), dp(10), dp(2), dp(2))
                    layoutParams = recyclerLp(top = 0, bottom = 0)
                }
                return NoteHolder(tv)
            }
            // v3.13 Bend row: sheetRow fill, 18dp corners, no stroke;
            // a 44dp circular photo/body-map thumb instead of the
            // status circle; name + meta column; a quiet right state
            // word; the drag handle. No "UP NEXT" micro, no part pill.
            val row = LinearLayout(this@WorkoutHubActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = rectBg(Palettes.current.sheetRow, 18)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = recyclerLp(top = 4, bottom = 4)
            }
            val holder = RowHolder(row)
            holder.thumb = bendCircleFrame(44)
            row.addView(holder.thumb)
            val col = LinearLayout(this@WorkoutHubActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(12), 0, dp(6), 0) }
            }
            holder.nameTv = TextView(this@WorkoutHubActivity).apply {
                textSize = 15f; setTextColor(Theme.textPrimary)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            col.addView(holder.nameTv)
            holder.lastTv = TextView(this@WorkoutHubActivity).apply {
                textSize = 12f; setTextColor(Theme.textSecondary)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }
            col.addView(holder.lastTv)
            row.addView(col)
            holder.st = TextView(this@WorkoutHubActivity).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            row.addView(holder.st)
            // v3.9.1: no Pass button — LEFT swipe on a pending row is
            // the Pass now (HubDragCallback); v3.9.2: RIGHT swipe on a
            // passed row adds it back. The reclaimed width goes to the
            // name/last-time column.
            holder.handle = TextView(this@WorkoutHubActivity).apply {
                text = "⋮⋮"; textSize = 16f; setTextColor(Theme.textTertiary)
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
                    h.label.text = bandLabel(item.type).uppercase()
                }
                is HubItem.Row -> bindRow(holder as RowHolder, item)
                HubItem.Finish -> {
                    val h = holder as FinishHolder
                    if (allHandled) h.button.background = GradientDrawable().apply { setColor(Theme.success); cornerRadius = dp(999).toFloat() }
                    else tightButton(h.button, 52, 999, primary = true) // re-applies the primary ripple background
                }
                is HubItem.Note -> {
                    val h = holder as NoteHolder
                    h.tv.text = item.text
                }
            }
        }

        private fun bindRow(holder: RowHolder, item: HubItem.Row) {
            val ex = item.ex
            val ctx = this@WorkoutHubActivity
            val status = db.effectiveStatus(ex)
            val isNext = ex.id == nextUpId
            holder.row.background = rectBg(Palettes.current.sheetRow, 18)
            // Passed rows recede via alpha (no strike-through anymore).
            holder.row.alpha = if (status == "skipped") 0.45f else 1f
            // Thumb: the exercise photo cropped into the 44dp circle,
            // else a centered body map of its target muscles.
            holder.thumb.removeAllViews()
            val bmp = PhotoLib.assetForSessionExercise(ctx, db, ex)?.let { PhotoLib.bitmap(ctx, it) }
            if (bmp != null) {
                holder.thumb.addView(ImageView(ctx).apply {
                    setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                })
            } else {
                holder.thumb.addView(BodyMapView(ctx, DbHelper.parseMuscles(ex.targetMuscles, ex.name), true).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(26), dp(34)).apply { gravity = Gravity.CENTER }
                })
            }
            holder.nameTv.text = ex.name
            holder.nameTv.setTextColor(when {
                isNext -> Theme.primary
                status == "skipped" -> Theme.textSecondary
                else -> Theme.textPrimary
            })
            holder.nameTv.typeface = Typeface.create("sans-serif-medium", if (isNext) Typeface.BOLD else Typeface.NORMAL)
            val sets = db.getSets(ex.id)
            val doneSets = sets.count { it.isDone }
            val last = db.lastPerformedForExercise(ex.name, sessionId)
            holder.lastTv.text = when {
                isNext -> "${sets.size} sets · set ${doneSets + 1} of ${sets.size}"
                last != null -> "${sets.size} sets · last ${last.sets.firstOrNull()?.let { setText(it) } ?: lastTimeLine(last)}"
                else -> "${sets.size} sets · new exercise"
            }
            // Right state word: done ✓ / Passed / NOW, else nothing.
            when {
                status == "done" -> holder.st.apply {
                    text = "✓"; textSize = 15f; setTextColor(Theme.success)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                }
                status == "skipped" -> holder.st.apply {
                    text = "Passed"; textSize = 12f; setTextColor(Theme.textTertiary)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
                isNext -> holder.st.apply {
                    text = "NOW"; textSize = 12f; setTextColor(Theme.primary)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                }
                else -> holder.st.text = ""
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
// v3.13: BandHolder's est view was dropped with the bare-label band
// (the holder is now just band + label).
private class BandHolder(val band: LinearLayout, val label: TextView) : RecyclerView.ViewHolder(band)

private class FinishHolder(val container: LinearLayout, val button: android.widget.Button) : RecyclerView.ViewHolder(container)

private class NoteHolder(val tv: TextView) : RecyclerView.ViewHolder(tv)

private class RowHolder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
    lateinit var thumb: FrameLayout
    lateinit var nameTv: TextView
    lateinit var lastTv: TextView
    lateinit var st: TextView
    lateinit var handle: TextView
}
