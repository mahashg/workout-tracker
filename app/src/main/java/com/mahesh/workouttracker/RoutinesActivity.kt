package com.mahesh.workouttracker

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** v3.9 Library tab (mock r4 frames 4/4b): the part-grid landing is
 *  replaced by ONE flat, filterable list of every exercise. A slim
 *  filter bar sits on top — COLLAPSED by default, showing just the
 *  active-filter summary; expanding reveals two chip rows (parts,
 *  then gear + a 🏠 Home chip). Filters combine (part AND gear).
 *  The list is a RecyclerView — the third approved scrolling surface
 *  (with the hub list and the card strip); no scroll-view widget, as ever.
 *  Add is preserved exactly: the header "＋" opens the existing
 *  add flow (part picker -> ExerciseEditActivity, the same part-scoped
 *  add BodyPartDetailActivity uses). v3.15: a row tap opens the
 *  VIEW-ONLY exercise detail page (ExerciseDetailActivity — photo,
 *  posture checks, cues, and the video links SHOWN with Watch/Copy),
 *  the same page part-detail rows open. The part pages themselves
 *  remain reachable from the Workout page grid. */
class RoutinesActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var filterPart: String? = null
    private var filterGear: String? = null
    private var filtersExpanded = false

    companion object { private const val GEAR_HOME = "__home__" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = DbHelper(this)
        Seed.applyV2IfNeeded(this, db)
        JsonSeed.applyV3IfNeeded(this, db)
        render()
    }
    override fun onResume() { super.onResume(); if (::db.isInitialized) render() }

    private fun render() {
        val root = tabScaffold("Library")
        val all = db.getAllExercises()
        // Home membership lives in the bundled JSON (home flag), keyed
        // by sourceId with a name fallback for adopted rows.
        val homeIds = mutableSetOf<String>()
        val homeNames = mutableSetOf<String>()
        for (pd in JsonSeed.loadAll(this)) for (e in pd.all) if (e.home) { homeIds.add(e.id); homeNames.add(e.name.trim().lowercase()) }
        fun isHome(e: Exercise): Boolean = e.sourceId in homeIds || e.name.trim().lowercase() in homeNames
        // Last performance per exercise name, one pass over history
        // (sessions come back newest-first, so first hit wins).
        val lastMap = mutableMapOf<String, LastPerformed>()
        for (s in db.getSessions()) {
            for (se in db.getSessionExercises(s.id)) {
                val key = se.name.trim().lowercase()
                if (key in lastMap) continue
                val doneSets = db.getSets(se.id).filter { it.isDone }
                if (doneSets.isNotEmpty()) lastMap[key] = LastPerformed(s.date, doneSets, se.effort)
            }
        }
        val gearValues = all.map { it.equipment }.filter { it.isNotBlank() }.distinct()
            .sortedWith(compareBy({ gearRank(it) }, { it }))

        // Title + the preserved add affordance.
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = screenTitle("Library")
        (title.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(40); setMargins(0, 0, 0, 0) }
        title.gravity = Gravity.CENTER_VERTICAL
        titleRow.addView(title)
        titleRow.addView(TextView(this).apply {
            text = "＋"; textSize = 26f; gravity = Gravity.CENTER; setTextColor(Theme.primary)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            isClickable = true
            setOnClickListener { addExercise() }
        })
        root.addView(titleRow)

        // Filter bar (collapsed by default).
        val anyFilter = filterPart != null || filterGear != null
        val partLabel = filterPart?.let { BodyParts.name(it) } ?: "All parts"
        val gearLabel = when (filterGear) { null -> "All gear"; GEAR_HOME -> "🏠 Home"; else -> filterGear }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(Theme.surface, 14, Theme.stroke)
            elevation = dp(2).toFloat()
            setPadding(dp(12), dp(9), dp(10), dp(9))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, 0) }
            isClickable = true
            setOnClickListener { filtersExpanded = !filtersExpanded; render() }
        }
        val fb = makeText("🎛 Filters", 14.5f, true)
        (fb.layoutParams as LinearLayout.LayoutParams).apply { width = LinearLayout.LayoutParams.WRAP_CONTENT; setMargins(0, 0, 0, 0) }
        bar.addView(fb)
        val sum = makeText("$partLabel · $gearLabel", 12.5f, false, Theme.textSecondary)
        (sum.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; setMargins(dp(10), 0, dp(6), 0) }
        sum.maxLines = 1; sum.ellipsize = android.text.TextUtils.TruncateAt.END
        bar.addView(sum)
        if (anyFilter) {
            bar.addView(TextView(this).apply {
                text = "✕ Clear"; textSize = 12.5f; setTextColor(Theme.warmAmber)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                isClickable = true
                setOnClickListener { filterPart = null; filterGear = null; render() }
            })
        }
        bar.addView(makeText(if (filtersExpanded) "▴" else "▾", 16f, true, Theme.textTertiary).apply {
            (layoutParams as LinearLayout.LayoutParams).apply { width = dp(24); setMargins(0, 0, 0, 0) }
            gravity = Gravity.CENTER
        })
        root.addView(bar)

        // Expanded chip rows: parts, then gear (+ 🏠 Home).
        if (filtersExpanded) {
            val chipBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, 0) }
            }
            val partChips = listOf<Pair<String, String?>>(("All parts" to null)) + BodyParts.all.map { it.name to it.id }
            addChipRows(chipBox, partChips, filterPart, { v -> filterPart = v; render() }, partLogos = true)
            val gearChips = listOf<Pair<String, String?>>(("All gear" to null)) +
                gearValues.map { it to it } + listOf("🏠 Home" to GEAR_HOME)
            addChipRows(chipBox, gearChips, filterGear, { v -> filterGear = v; render() })
            root.addView(chipBox)
        }

        // The flat list.
        val filtered = all.filter { e ->
            (filterPart == null || e.bodyPart == filterPart) &&
                (filterGear == null || (if (filterGear == GEAR_HOME) isHome(e) else e.equipment == filterGear))
        }
        val count = caption(if (!anyFilter) "Showing all ${all.size} exercises" else "Showing ${filtered.size} of ${all.size}")
        (count.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(6), 0, dp(2))
        root.addView(count)
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@RoutinesActivity)
            adapter = LibraryAdapter(filtered, lastMap, homeIds, homeNames)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(rv)
    }

    private fun gearRank(equipment: String): Int = when (equipment) {
        "Machine" -> 0; "Dumbbell" -> 1; "Kettlebell" -> 2; "Bodyweight" -> 3; else -> 4
    }

    private fun addChipRows(box: LinearLayout, chips: List<Pair<String, String?>>, selected: String?, onPick: (String?) -> Unit, partLogos: Boolean = false) {
        for (rowChips in chips.chunked(3)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42))
            }
            for ((label, value) in rowChips) {
                val sel = value == selected
                val warm = value == GEAR_HOME
                val chip = TextView(this).apply {
                    text = label; textSize = 12.5f; gravity = Gravity.CENTER; maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    typeface = android.graphics.Typeface.create("sans-serif-medium", if (sel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    when {
                        sel && warm -> { setTextColor(android.graphics.Color.WHITE); background = roundedBg(Theme.warmAmber, 999) }
                        sel -> { setTextColor(Theme.onPrimary); background = roundedBg(Theme.primary, 999) }
                        else -> { setTextColor(Theme.textSecondary); background = roundedBg(Theme.surfaceVariant, 999, Theme.stroke) }
                    }
                    layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) }
                    isClickable = true
                    setOnClickListener { onPick(value) }
                    // v3.12: part chips (partLogos only, non-null value)
                    // carry their 24dp anatomy logo as a left compound
                    // drawable; "All parts" (null) and gear chips don't.
                    if (partLogos && value != null) {
                        PartLogos.bitmap(this@RoutinesActivity, value)?.let { bmp ->
                            val scaled = Bitmap.createScaledBitmap(bmp, dp(24), dp(24), true)
                            setCompoundDrawablesWithIntrinsicBounds(BitmapDrawable(resources, scaled), null, null, null)
                            compoundDrawablePadding = dp(6)
                        }
                    }
                }
                row.addView(chip)
            }
            while (row.childCount < 3) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            box.addView(row)
        }
    }

    /** The existing add flow, reached as before: pick the part the new
     *  exercise belongs to, then the part-scoped add form opens. */
    private fun addExercise() {
        val parts = BodyParts.all
        AlertDialog.Builder(this)
            .setTitle("Add exercise to…")
            .setItems(parts.map { it.name }.toTypedArray()) { _, which ->
                startActivity(Intent(this, ExerciseEditActivity::class.java)
                    .putExtra("exerciseId", -1L).putExtra("bodyPart", parts[which].id))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class LibraryAdapter(
        private val items: List<Exercise>,
        private val lastMap: Map<String, LastPerformed>,
        private val homeIds: Set<String>,
        private val homeNames: Set<String>
    ) : RecyclerView.Adapter<LibraryAdapter.RowHolder>() {

        inner class RowHolder(val card: LinearLayout) : RecyclerView.ViewHolder(card) {
            val thumbBox: FrameLayout = card.getChildAt(0) as FrameLayout
            val info: LinearLayout = card.getChildAt(1) as LinearLayout
            val nameTv: TextView = info.getChildAt(0) as TextView
            val tagsRow: LinearLayout = info.getChildAt(1) as LinearLayout
            val lastTv: TextView = info.getChildAt(2) as TextView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val card = LinearLayout(this@RoutinesActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = cardDrawable(tappable = true)
                elevation = dp(2).toFloat()
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = recyclerLp()
            }
            val thumbBox = FrameLayout(this@RoutinesActivity).apply {
                background = roundedBg(Theme.surfaceVariant, 10)
                layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
            }
            card.addView(thumbBox)
            val info = LinearLayout(this@RoutinesActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(10), 0, 0, 0) }
            }
            val nameTv = makeText("", 14.5f, true)
            nameTv.maxLines = 1; nameTv.ellipsize = android.text.TextUtils.TruncateAt.END
            (nameTv.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
            info.addView(nameTv)
            val tagsRow = LinearLayout(this@RoutinesActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, 0) }
            }
            info.addView(tagsRow)
            val lastTv = makeText("", 12f, false, Theme.textSecondary)
            lastTv.maxLines = 1; lastTv.ellipsize = android.text.TextUtils.TruncateAt.END
            (lastTv.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(3), 0, 0)
            info.addView(lastTv)
            card.addView(info)
            return RowHolder(card)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val e = items[position]
            val ctx = this@RoutinesActivity
            holder.nameTv.text = e.name
            // Thumb: the exercise photo's first frame (static,
            // CENTER_CROP) or the body-map tile when there is none.
            holder.thumbBox.removeAllViews()
            val asset = PhotoLib.assetForExercise(ctx, e)
            val bmp = asset?.let { PhotoLib.bitmap(ctx, it) }
            if (bmp != null) {
                holder.thumbBox.addView(ImageView(ctx).apply {
                    setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    roundedClip(10)
                })
            } else {
                holder.thumbBox.addView(BodyMapView(ctx, DbHelper.parseMuscles(e.targetMuscles, e.name)).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(34), dp(44), Gravity.CENTER)
                })
            }
            // Tags: PART + EQUIPMENT (+ 🏠 HOME when home=true).
            holder.tagsRow.removeAllViews()
            if (e.bodyPart.isNotBlank()) BodyParts.byId(e.bodyPart)?.let {
                holder.tagsRow.addView(tagView(it.name.uppercase(), Palettes.current.tagPartBg, Palettes.current.tagPartFg))
            }
            if (e.equipment.isNotBlank()) {
                holder.tagsRow.addView(tagView(e.equipment.uppercase(), Palettes.current.tagGearBg, Palettes.current.tagGearFg))
            }
            if (e.sourceId in homeIds || e.name.trim().lowercase() in homeNames) {
                holder.tagsRow.addView(tagView("🏠 HOME", Palettes.current.tagHomeBg, Palettes.current.tagHomeFg))
            }
            val lp = lastMap[e.name.trim().lowercase()]
            holder.lastTv.text = if (lp != null) "Last: ${lastTimeLine(lp)}" else "New"
            holder.card.isClickable = true
            holder.card.setOnClickListener {
                // v3.15: a row tap opens the VIEW-ONLY detail page
                // (ExerciseDetailActivity), not the edit form.
                startActivity(Intent(ctx, ExerciseDetailActivity::class.java)
                    .putExtra("exerciseId", e.id).putExtra("bodyPart", e.bodyPart))
            }
        }

        private fun recyclerLp(): RecyclerView.LayoutParams =
            RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(3), 0, dp(3))
            }
    }
}
