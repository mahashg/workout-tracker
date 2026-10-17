package com.mahesh.workouttracker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * v3.15 Library entry detail — a VIEW-ONLY page (approved mock
 * detail-pages r2, screens 4–5; Mahesh: "Let's make it a view only
 * page"). Tapping a workout in the Library list (and on the part
 * pages, whose rows opened the same detail) lands here instead of
 * the ExerciseEditActivity form: nothing on this page mutates data —
 * no edit fields, no paste box, no save.
 *
 * Contents, in the mock's order: photo band (the entry's photo with
 * the established two-frame crossfade, body-map fallback), name,
 * info chips (body part with its anatomy logo, equipment,
 * sets × target reps, 🏠 Home when the catalog flags it), a targets
 * line, POSTURE CHECKS as ☐ lines (full content), CUES, and the
 * VIDEO section — the point of the change: the entry's links are
 * SHOWN, not hidden behind a ▶. When the catalog carries a Short
 * that differs from the full video there are two rows (SHORT /
 * FULL VIDEO); otherwise one VIDEO row plus the mock's honest note
 * ("This workout has one link — it plays as both the Short and the
 * full video."). Each row offers ▶ Watch (the established openUrl
 * path) and ⧉ Copy link (copies the FULL stored URL + a toast).
 * Display strings come from the stored data at runtime: the full
 * video URL is the DB working copy's youtubeUrl (the JSON url is
 * the fallback), the Short is read from the bundled JSON by
 * sourceId / name — the same runtime resolution the cards use.
 * A quiet footer states the page's contract: "View only — this
 * page doesn't change the workout".
 *
 * The page scrolls (ScrollView around a wrap-content column, the
 * HistoryDetailActivity structure) so long check/cue lists are
 * never clipped. Editing still exists where it always did — the
 * Library/part-page Add flows and the routine editor — this page
 * is purely for looking.
 */
class ExerciseDetailActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var exerciseId: Long = -1
    private var looper: PhotoLooper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = DbHelper(this)
        exerciseId = intent.getLongExtra("exerciseId", -1)
        if (exerciseId < 0 || db.getExercise(exerciseId) == null) { finish(); return }
        render()
    }

    override fun onResume() { super.onResume(); looper?.start() }
    override fun onPause() { super.onPause(); looper?.stop() }

    private fun render() {
        val e = db.getExercise(exerciseId) ?: run { finish(); return }
        val entry = JsonSeed.entryById(this, e.sourceId) ?: JsonSeed.entryByName(this, e.name)
        val root = fitRoot(); setContentView(root)
        root.addView(topBar("Library"))

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        scroll.addView(col)
        root.addView(scroll)

        // ---- Photo band (mock .photoband: 190dp, 20dp corners) ----
        val band = FrameLayout(this).apply {
            background = roundedBg(Theme.surfaceVariant, 20)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(190)).apply { setMargins(0, dp(12), 0, 0) }
            roundedClip(20)
        }
        val asset = PhotoLib.assetForExercise(this, e)
        val bmp: Bitmap? = asset?.let { PhotoLib.bitmap(this, it) }
        if (bmp != null) {
            band.addView(ImageView(this).apply {
                setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
            val bmp2 = asset?.let { PhotoLib.secondBitmap(this, it) }
            if (bmp2 != null) {
                val iv2 = ImageView(this).apply {
                    setImageBitmap(bmp2); scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                }
                band.addView(iv2)
                looper = PhotoLooper(iv2)
            }
        } else {
            band.addView(BodyMapView(this, DbHelper.parseMuscles(e.targetMuscles, e.name)).apply {
                layoutParams = FrameLayout.LayoutParams(dp(110), dp(140), Gravity.CENTER)
            })
        }
        col.addView(band)

        // ---- Name + info chips ----
        val name = makeText(e.name, 22f, true)
        (name.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(14), 0, 0)
        col.addView(name)

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(12), 0, 0) }
        }
        if (e.bodyPart.isNotBlank()) BodyParts.byId(e.bodyPart)?.let { part ->
            chips.addView(chip(part.name, PartLogos.bitmap(this, part.id)))
        }
        if (e.equipment.isNotBlank()) chips.addView(chip(e.equipment, null))
        chips.addView(chip("${e.defaultSets} sets × ${e.targetReps}", null))
        if (entry?.home == true) chips.addView(chip("🏠 Home", null))
        col.addView(chips)

        val muscles = musclesLabel(DbHelper.parseMuscles(e.targetMuscles, e.name))
        if (e.targetMuscles.isNotBlank() && muscles.isNotBlank()) {
            val targets = makeText("Targets: $muscles", 12.5f, false, Theme.textSecondary)
            (targets.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(10), 0, 0)
            col.addView(targets)
        }

        // ---- Posture checks (full content, ☐ lines) ----
        val checks = e.postureCheck.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        if (checks.isNotEmpty()) {
            col.addView(sectionLabel("POSTURE CHECKS"))
            for (check in checks) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), 0, 0) }
                }
                row.addView(TextView(this).apply {
                    text = "☐"; textSize = 14f; setTextColor(Theme.primary)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp(9), 0) }
                })
                row.addView(TextView(this).apply {
                    text = check; textSize = 14f; setTextColor(Theme.textPrimary)
                    setLineSpacing(0f, 1.45f)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                col.addView(row)
            }
        }

        // ---- Cues (one quiet paragraph, " · "-joined, as mocked) ----
        val cues = e.cues.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        if (cues.isNotEmpty()) {
            col.addView(sectionLabel("CUES"))
            val cueTv = makeText(cues.joinToString(" · "), 14f, false, Theme.textSecondary)
            cueTv.setLineSpacing(0f, 1.45f)
            (cueTv.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(7), 0, 0)
            col.addView(cueTv)
        }

        // ---- Video: the links, shown ----
        col.addView(sectionLabel("VIDEO"))
        val full = e.youtubeUrl.ifBlank { entry?.url ?: "" }
        val short = entry?.shortUrl ?: ""
        when {
            short.isNotBlank() && full.isNotBlank() && short != full -> {
                col.addView(videoRow("SHORT", short))
                col.addView(videoRow("FULL VIDEO", full))
            }
            full.isNotBlank() || short.isNotBlank() -> {
                col.addView(videoRow("VIDEO", full.ifBlank { short }))
                val note = makeText("This workout has one link — it plays as both the Short and the full video.", 12f, false, Theme.textSecondary)
                (note.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(10), 0, 0)
                col.addView(note)
            }
            else -> {
                val none = makeText("No video link for this workout.", 13f, false, Theme.textSecondary)
                (none.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(7), 0, 0)
                col.addView(none)
            }
        }

        // ---- Footer: the page's contract ----
        val footer = makeText("View only — this page doesn't change the workout", 12f, false, Theme.textTertiary)
        footer.gravity = Gravity.CENTER
        (footer.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(18), 0, dp(8))
        col.addView(footer)
    }

    /** Mock .sect: 11sp bold, letter-spaced, gray, 22dp above. */
    private fun sectionLabel(text: String): TextView {
        val tv = overline(text)
        (tv.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(22), 0, 0)
        return tv
    }

    /** Mock .chip: lilac pill (surfaceVariant in palette terms),
     *  primary-dark text; the part chip carries its anatomy logo as
     *  a left compound drawable, the way the Library filter chips do. */
    private fun chip(text: String, logo: Bitmap?): TextView {
        return TextView(this).apply {
            this.text = text; textSize = 12f; setTextColor(Theme.primaryDark)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            background = roundedBg(Theme.surfaceVariant, 999)
            setPadding(dp(11), dp(6), dp(11), dp(6))
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp(7), 0) }
            if (logo != null) {
                val scaled = Bitmap.createScaledBitmap(logo, dp(20), dp(20), true)
                setCompoundDrawablesWithIntrinsicBounds(BitmapDrawable(resources, scaled), null, null, null)
                compoundDrawablePadding = dp(6)
            }
        }
    }

    /** Mock .vrow: white row — kind label, the link itself
     *  (host + path display of the stored URL), then ▶ Watch
     *  (primary pill) and ⧉ Copy link (light pill; copies the FULL
     *  stored URL, never the display-truncated form). */
    private fun videoRow(label: String, url: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Theme.surface, 16, Theme.stroke)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(9), 0, 0) }
        }
        row.addView(TextView(this).apply {
            text = label; textSize = 11f; setTextColor(Theme.primary)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            letterSpacing = 0.1f
        })
        row.addView(TextView(this).apply {
            text = displayLink(url); textSize = 13.5f; setTextColor(Theme.textPrimary)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(3), 0, 0) }
        })
        val acts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(10), 0, 0) }
        }
        acts.addView(TextView(this).apply {
            text = "▶ Watch"; textSize = 13f; setTextColor(Theme.onPrimary)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            background = roundedBg(Theme.primary, 999)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp(8), 0) }
            isClickable = true
            setOnClickListener { openUrl(this@ExerciseDetailActivity, url) }
        })
        acts.addView(TextView(this).apply {
            text = "⧉ Copy link"; textSize = 13f; setTextColor(Theme.primaryDark)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            background = roundedBg(Theme.surfaceVariant, 999)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Video link", url))
                Toast.makeText(this@ExerciseDetailActivity, "Link copied", Toast.LENGTH_SHORT).show()
            }
        })
        row.addView(acts)
        return row
    }

    /** Display form of a stored URL: scheme and "www." removed, so
     *  the row reads like the mock's "youtube.com/shorts/…". The
     *  stored value itself is never altered — Watch and Copy use
     *  the full original. */
    private fun displayLink(url: String): String =
        url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
}
