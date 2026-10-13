package com.mahesh.workouttracker

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/** v3 body-part detail: the part's warm-ups, exercises and stretches from the
 *  DB (the working copy of the JSON data), each with its last performance and
 *  photo where one exists. Add/edit is scoped to this part. One page,
 *  paginated rows (no scrolling, per the standing rule). */
class BodyPartDetailActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var partId: String = ""
    private var page = 0
    private var created = false
    private val photoLoopers = mutableListOf<PhotoLooper>()

    private sealed class Row {
        data class Header(val text: String, val color: Int) : Row()
        data class Item(val e: Exercise) : Row()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = DbHelper(this)
        partId = intent.getStringExtra("bodyPart") ?: ""
        if (BodyParts.byId(partId) == null) { finish(); return }
        created = true
        render()
    }

    override fun onResume() { super.onResume(); if (created) render() }
    override fun onPause() { super.onPause(); photoLoopers.forEach { it.stop() } }

    private fun rows(): List<Row> {
        val all = db.getExercisesForPart(partId)
        val out = mutableListOf<Row>()
        fun group(type: String, label: String) {
            val items = all.filter { it.type == type }
            if (items.isEmpty()) return
            out.add(Row.Header(label, sectionColor(type)))
            for (e in items) out.add(Row.Item(e))
        }
        group("Warmup", "Warm-up")
        group("Main", "Exercises")
        group("Stretch", "Cool-down stretches")
        return out
    }

    private fun render() {
        // Rows from the previous render are discarded; stop their photo
        // crossfade loops first (v3.1).
        photoLoopers.forEach { it.stop() }; photoLoopers.clear()
        val compact = isCompactScreen()
        val root = fitRoot()
        setContentView(root)
        val part = BodyParts.byId(partId)!!
        val last = db.lastTrainedDates()[partId]
        root.addView(topBar(part.name, if (last != null) "Last trained ${DateUtil.display(last)}" else "Not trained yet"))
        val all = rows()
        val itemH = if (compact) 54 else 58
        val pageSize = if (compact) 7 else 8
        val pageCount = maxOf(1, (all.size + pageSize - 1) / pageSize)
        page = page.coerceIn(0, pageCount - 1)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val from = page * pageSize
        val to = minOf(all.size, from + pageSize)
        for (i in from until to) {
            when (val r = all[i]) {
                is Row.Header -> {
                    val h = makeText(r.text, 13f, true, r.color)
                    h.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)).apply { setMargins(0, dp(4), 0, 0) }
                    h.gravity = Gravity.CENTER_VERTICAL
                    box.addView(h)
                }
                is Row.Item -> box.addView(itemRow(r.e, itemH))
            }
        }
        root.addView(box)
        if (pageCount > 1) root.addView(pagerBar(page, pageCount, { page--; render() }, { page++; render() }))
        val addBtn = makeSecondaryButton("+ Add exercise") {
            startActivity(Intent(this, ExerciseEditActivity::class.java).putExtra("exerciseId", -1L).putExtra("bodyPart", partId))
        }
        tightButton(addBtn, if (compact) 46 else 50, 12)
        root.addView(addBtn)
    }

    private fun itemRow(e: Exercise, rowH: Int): View {
        // v3.1: the exercise photo is the row's BACKGROUND — a CENTER_CROP
        // ImageView filling the rounded row (never stretched), crossfade-
        // looping its two frames where both exist, with a light mist scrim
        // from the left so the name/meta text stays legible. No photo ->
        // the body map sits in the row as before.
        val asset = PhotoLib.assetForExercise(this, e)
        val bmp: Bitmap? = asset?.let { PhotoLib.bitmap(this, it) }
        val frame = FrameLayout(this).apply {
            background = cardDrawable(tappable = true)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(rowH)).apply { setMargins(0, dp(2), 0, dp(2)) }
            roundedClip(20)
            isClickable = true
            setOnClickListener { startActivity(Intent(this@BodyPartDetailActivity, ExerciseEditActivity::class.java).putExtra("exerciseId", e.id).putExtra("bodyPart", partId)) }
        }
        if (bmp != null) {
            frame.addView(ImageView(this).apply {
                setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
            val bmp2 = asset?.let { PhotoLib.secondBitmap(this, it) }
            if (bmp2 != null) {
                val iv2 = ImageView(this).apply {
                    setImageBitmap(bmp2); scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                }
                frame.addView(iv2)
                val looper = PhotoLooper(iv2); photoLoopers.add(looper); looper.start()
            }
            frame.addView(View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xE6F7F9FB.toInt(), 0x00F7F9FB))
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        if (bmp == null) {
            val muscles = DbHelper.parseMuscles(e.targetMuscles, e.name)
            row.addView(BodyMapView(this, muscles).apply { layoutParams = LinearLayout.LayoutParams(dp(30), dp(38)).apply { setMargins(0, 0, dp(8), 0) } })
        }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val nm = makeText(e.name, 14f, true)
        nm.maxLines = 1; nm.ellipsize = android.text.TextUtils.TruncateAt.END
        info.addView(nm)
        val last = db.lastPerformedForExercise(e.name, -1)
        val meta = makeText(
            if (last != null) "${e.equipment} · ${e.defaultSets}×${e.targetReps} · Last: ${lastTimeLine(last)}"
            else "${e.equipment} · ${e.defaultSets}×${e.targetReps}",
            11f, false, Theme.textSecondary)
        meta.maxLines = 1; meta.ellipsize = android.text.TextUtils.TruncateAt.END
        info.addView(meta)
        row.addView(info)
        row.addView(iconView(R.drawable.ic_chevron, 18, Theme.textTertiary))
        frame.addView(row)
        return frame
    }
}
