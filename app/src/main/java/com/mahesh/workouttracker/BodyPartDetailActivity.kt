package com.mahesh.workouttracker

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = cardDrawable(tappable = true)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(rowH)).apply { setMargins(0, dp(2), 0, dp(2)) }
            isClickable = true
            setOnClickListener { startActivity(Intent(this@BodyPartDetailActivity, ExerciseEditActivity::class.java).putExtra("exerciseId", e.id).putExtra("bodyPart", partId)) }
        }
        // Photo thumb where one exists; BodyMap otherwise.
        val asset = PhotoLib.assetForExercise(this, e)
        val bmp: Bitmap? = asset?.let { PhotoLib.bitmap(this, it) }
        if (bmp != null) {
            row.addView(ImageView(this).apply {
                setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(rowH - 10)).apply { setMargins(0, 0, dp(8), 0) }
            })
        } else {
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
        return row
    }
}
