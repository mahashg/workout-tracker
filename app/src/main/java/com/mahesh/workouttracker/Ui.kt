package com.mahesh.workouttracker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

fun Context.dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

/** True on short screens (<700dp tall): tighter paddings/gaps everywhere (v2.5). */
fun Context.isCompactScreen(): Boolean =
    (resources.displayMetrics.heightPixels / resources.displayMetrics.density) < 700f

/** Available screen height in dp (used to compute pagination page sizes). */
fun Context.screenHeightDp(): Int =
    (resources.displayMetrics.heightPixels / resources.displayMetrics.density).toInt()

// ---------- Design tokens (v2.1, light theme for bright gym light) ----------
object Theme {
    val bg = Color.parseColor("#EAF0F5") // soft mist background (Mahesh color change, v2.3)
    val surface = Color.parseColor("#F7F9FB") // soft off-white cards - never pure #FFFFFF
    val surfaceVariant = Color.parseColor("#EDF2F7")
    val stroke = Color.parseColor("#D6DEE8")
    val shadow = Color.parseColor("#C3D0DE")
    val primary = Color.parseColor("#2563EB")
    val primaryDark = Color.parseColor("#1D4ED8")
    val onPrimary = Color.WHITE
    val textPrimary = Color.parseColor("#132033")
    val textSecondary = Color.parseColor("#5A6B80")
    val textTertiary = Color.parseColor("#8494A7")
    val success = Color.parseColor("#15803D")
    val successBg = Color.parseColor("#DCFCE7")
    val warn = Color.parseColor("#92400E")
    val warnBg = Color.parseColor("#FEF3C7")
    val pendingFg = Color.parseColor("#475569")
    val pendingBg = Color.parseColor("#E2E8F0")
    val accentSky = Color.parseColor("#0369A1")
    val warmAmber = Color.parseColor("#D97706")
    val coolTeal = Color.parseColor("#0D9488")
}

fun roundedBg(color: String, radiusDp: Int, strokeColor: String? = null): GradientDrawable {
    return GradientDrawable().apply {
        setColor(Color.parseColor(color)); cornerRadius = radiusDp.toFloat()
        if (strokeColor != null) setStroke(3, Color.parseColor(strokeColor))
    }
}

private fun Context.rounded(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 1): GradientDrawable {
    return GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
    }
}

/** Layered card background: soft shadow layer peeking below + white surface with stroke.
 *  If [tappable], wraps in a RippleDrawable for a real press state. */
fun Context.cardDrawable(accent: String? = null, tappable: Boolean = false): Drawable {
    val shadowLayer = rounded(Theme.shadow, 20)
    val strokeColor = if (accent != null) Color.parseColor(accent) else Theme.stroke
    val surfaceLayer = rounded(Theme.surface, 20, strokeColor, if (accent != null) 2 else 1)
    val layers = LayerDrawable(arrayOf(shadowLayer, surfaceLayer))
    layers.setLayerInset(0, dp(2), dp(3), dp(2), 0)   // shadow offset down
    layers.setLayerInset(1, 0, 0, 0, dp(4))          // surface leaves shadow visible at bottom
    if (!tappable) return layers
    val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20).toFloat() }
    return RippleDrawable(ColorStateList.valueOf(0x332563EB), layers, mask)
}

/** Card background with a caller-supplied border color (used by the swipe card to
 *  strengthen its border/tint toward the active drag direction). Same layered
 *  shadow + surface construction as [cardDrawable]. */
fun Context.deckCardDrawable(borderColor: Int? = null): Drawable {
    val shadowLayer = rounded(Theme.shadow, 20)
    val surfaceLayer = rounded(Theme.surface, 20, borderColor ?: Theme.stroke, if (borderColor != null) 3 else 1)
    val layers = LayerDrawable(arrayOf(shadowLayer, surfaceLayer))
    layers.setLayerInset(0, dp(2), dp(3), dp(2), 0)
    layers.setLayerInset(1, 0, 0, 0, dp(4))
    return layers
}

/** Gradient hero background (primary blue), layered over the same soft shadow. */
fun Context.heroDrawable(): Drawable {
    val shadowLayer = rounded(Theme.shadow, 20)
    val grad = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Theme.primary, Theme.primaryDark)).apply { cornerRadius = dp(20).toFloat() }
    val layers = LayerDrawable(arrayOf(shadowLayer, grad))
    layers.setLayerInset(0, dp(2), dp(3), dp(2), 0)
    layers.setLayerInset(1, 0, 0, 0, dp(4))
    return layers
}

// ---------- Typography ----------
private fun medium(): Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

fun Context.makeText(text: String, size: Float = 16f, bold: Boolean = false, color: Int = Theme.textPrimary): TextView {
    return TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) typeface = medium()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, dp(4)) }
    }
}
fun Context.screenTitle(text: String): TextView = makeText(text, 26f, true, Theme.textPrimary).apply { setPadding(0, dp(6), 0, dp(2)) }
fun Context.overline(text: String): TextView = makeText(text.uppercase(), 11f, true, Theme.textSecondary).apply { letterSpacing = 0.08f }
fun Context.cardTitle(text: String): TextView = makeText(text, 18f, true, Theme.textPrimary)
fun Context.bodyText(text: String): TextView = makeText(text, 14f, false, Theme.textPrimary)
fun Context.caption(text: String): TextView = makeText(text, 12f, false, Theme.textSecondary)
fun Context.sectionLabelText(text: String): TextView = makeText(text, 18f, true, Theme.textPrimary).apply { setPadding(0, dp(14), 0, dp(4)) }

// ---------- Buttons ----------
fun Context.makeButton(text: String, onClick: () -> Unit): Button {
    return Button(this).apply {
        this.text = text; textSize = 15f; setTextColor(Theme.onPrimary); typeface = medium(); isAllCaps = false
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        if (android.os.Build.VERSION.SDK_INT >= 26) setAutoSizeTextTypeUniformWithConfiguration(13, 15, 1, TypedValue.COMPLEX_UNIT_SP)
        background = RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), rounded(Theme.primary, 16), GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat() })
        minHeight = dp(56)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(6)) }
        setOnClickListener { onClick() }
    }
}
fun Context.makeSecondaryButton(text: String, onClick: () -> Unit): Button {
    return Button(this).apply {
        this.text = text; textSize = 15f; setTextColor(Theme.primary); typeface = medium(); isAllCaps = false
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        if (android.os.Build.VERSION.SDK_INT >= 26) setAutoSizeTextTypeUniformWithConfiguration(13, 15, 1, TypedValue.COMPLEX_UNIT_SP)
        background = RippleDrawable(ColorStateList.valueOf(0x222563EB), rounded(Theme.surface, 16, Theme.primary, 1), GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat() })
        minHeight = dp(52)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(6)) }
        setOnClickListener { onClick() }
    }
}
fun Context.makeSmallButton(text: String, onClick: () -> Unit): Button {
    return Button(this).apply {
        this.text = text; textSize = 13f; setTextColor(Theme.textPrimary); typeface = medium(); isAllCaps = false
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        if (android.os.Build.VERSION.SDK_INT >= 26) setAutoSizeTextTypeUniformWithConfiguration(11, 13, 1, TypedValue.COMPLEX_UNIT_SP)
        background = RippleDrawable(ColorStateList.valueOf(0x222563EB), rounded(Theme.surfaceVariant, 12, Theme.stroke, 1), GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat() })
        minHeight = dp(48); minWidth = dp(48); setPadding(dp(12), dp(4), dp(12), dp(4))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(4), dp(2), dp(4), dp(2)) }
        setOnClickListener { onClick() }
    }
}
fun Context.styleEditText(et: EditText) {
    et.apply {
        setTextColor(Theme.textPrimary); setHintTextColor(Theme.textTertiary); textSize = 14f
        background = rounded(Theme.surfaceVariant, 12, Theme.stroke, 1)
        setPadding(dp(12), dp(10), dp(12), dp(10)); minHeight = dp(48)
    }
}

fun Context.iconView(resId: Int, sizeDp: Int = 22, tint: Int = Theme.textSecondary): android.widget.ImageView {
    return android.widget.ImageView(this).apply {
        setImageResource(resId); setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }
}
fun Context.primaryButtonWithIcon(text: String, iconRes: Int, onClick: () -> Unit): Button {
    val b = makeButton(text, onClick)
    b.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
    b.compoundDrawablePadding = dp(8)
    try { b.compoundDrawables[0]?.setTint(Theme.onPrimary) } catch (e: Exception) {}
    return b
}
fun Context.smallButtonWithIcon(text: String, iconRes: Int, tint: Int = Theme.textPrimary, onClick: () -> Unit): Button {
    val b = makeSmallButton(text, onClick)
    b.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
    b.compoundDrawablePadding = dp(6)
    try { b.compoundDrawables[0]?.setTint(tint) } catch (e: Exception) {}
    return b
}

// ---------- Layout scaffolding ----------
fun Context.rootLayout(): LinearLayout {
    val compact = isCompactScreen()
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(if (compact) 14 else 20), dp(if (compact) 10 else 16), dp(if (compact) 14 else 20), dp(if (compact) 12 else 16))
        setBackgroundColor(Theme.bg)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
    }
}

/**
 * v2.5 no-scroll page root: a fixed-height vertical container that fills the
 * screen. Children that need to flex use layout height 0 + weight 1.
 */
fun Context.fitRoot(): LinearLayout = rootLayout()

/** Compact pagination bar: ‹ Prev • 2/5 • Next › (v2.5). */
fun Context.pagerBar(page: Int, pageCount: Int, onPrev: () -> Unit, onNext: () -> Unit): LinearLayout {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { setMargins(0, dp(4), 0, dp(2)) }
    }
    val prev = makeSmallButton("‹ Prev") { if (page > 0) onPrev() }
    (prev.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48) }
    prev.isEnabled = page > 0; prev.alpha = if (page > 0) 1f else 0.45f
    val mid = makeText("${page + 1} / ${pageCount.coerceAtLeast(1)}", 13f, true, Theme.textSecondary).apply {
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    val next = makeSmallButton("Next ›") { if (page < pageCount - 1) onNext() }
    (next.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(48) }
    next.isEnabled = page < pageCount - 1; next.alpha = if (page < pageCount - 1) 1f else 0.45f
    row.addView(prev); row.addView(mid); row.addView(next)
    return row
}
fun Context.cardLayout(stroke: String? = null, tappable: Boolean = false, onClick: (() -> Unit)? = null): LinearLayout {
    val pad = if (isCompactScreen()) 14 else 18
    val gap = if (isCompactScreen()) 4 else 6
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        background = cardDrawable(stroke, tappable || onClick != null)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(gap), 0, dp(gap)) }
        elevation = dp(3).toFloat()
        if (onClick != null) setOnClickListener { onClick() }
    }
}
fun Context.heroCard(): LinearLayout {
    val pad = if (isCompactScreen()) 14 else 18
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        background = heroDrawable()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(7), 0, dp(7)) }
        elevation = dp(4).toFloat()
    }
}
/** Thin horizontal progress bar (track + fill) in the design system. */
fun Context.hProgress(max: Int, progress: Int, fillColor: Int): LinearLayout {
    val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = rounded(Theme.pendingBg, 8); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10)).apply { setMargins(0, dp(8), 0, dp(6)) } }
    val m = max.coerceAtLeast(1); val p = progress.coerceIn(0, m)
    if (p > 0) bar.addView(View(this).apply { background = rounded(fillColor, 8); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, p.toFloat()) })
    if (p < m) bar.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (m - p).toFloat()) })
    return bar
}

/** Top app bar for secondary screens: back arrow + title. */
fun AppCompatActivity.topBar(title: String, subtitle: String = ""): LinearLayout {
    // Fixed 56dp bar (v2.5.1): every secondary screen can budget against this.
    val bar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56))
    }
    val back = android.widget.ImageButton(this).apply {
        setImageResource(R.drawable.ic_back); setColorFilter(Theme.primary)
        background = rounded(Theme.surface, 14, Theme.stroke, 1)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        setOnClickListener { finish() }
    }
    bar.addView(back)
    val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
    val t = makeText(title, 19f, true, Theme.textPrimary)
    t.maxLines = 1; t.ellipsize = android.text.TextUtils.TruncateAt.END
    (t.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
    titles.addView(t)
    if (subtitle.isNotBlank()) {
        val st = makeText(subtitle, 11f, false, Theme.textSecondary)
        st.maxLines = 1; st.ellipsize = android.text.TextUtils.TruncateAt.END
        (st.layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        titles.addView(st)
    }
    bar.addView(titles)
    return bar
}

/** Scaffold for the 4 tab screens: fixed (non-scrolling) content + persistent bottom nav. */
fun AppCompatActivity.tabScaffold(selected: String): LinearLayout {
    val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.bg); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT) }
    val root = rootLayout().apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
    outer.addView(root)
    outer.addView(bottomNav(selected))
    setContentView(outer)
    return root
}

fun AppCompatActivity.bottomNav(selected: String): LinearLayout {
    val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.surface) }
    wrap.addView(View(this).apply { setBackgroundColor(Theme.stroke); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)) })
    val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(4), dp(4), dp(8)) }
    val tabs = listOf(
        Triple("Home", R.drawable.ic_home, MainActivity::class.java),
        Triple("Routines", R.drawable.ic_list, RoutinesActivity::class.java),
        Triple("History", R.drawable.ic_history, HistoryActivity::class.java),
        Triple("Settings", R.drawable.ic_settings, SettingsActivity::class.java)
    )
    for ((label, iconRes, cls) in tabs) {
        val isSel = label == selected
        val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(0, dp(2), 0, dp(2)); isClickable = true }
        item.addView(View(this).apply { setBackgroundColor(if (isSel) Theme.primary else Color.TRANSPARENT); layoutParams = LinearLayout.LayoutParams(dp(28), dp(3)).apply { gravity = Gravity.CENTER_HORIZONTAL } })
        item.addView(iconView(iconRes, 23, if (isSel) Theme.primary else Theme.textSecondary).apply { setPadding(0, dp(4), 0, 0) })
        item.addView(TextView(this).apply { text = label; textSize = 11f; gravity = Gravity.CENTER; setTextColor(if (isSel) Theme.primary else Theme.textSecondary); if (isSel) typeface = medium() })
        if (!isSel) item.setOnClickListener {
            startActivity(Intent(this@bottomNav, cls).apply { addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP) })
        }
        bar.addView(item)
    }
    wrap.addView(bar)
    return wrap
}

// ---------- Pills / badges / sections ----------
fun Context.statusPill(status: String): TextView {
    val (bg, fg) = when(status) {
        "Done" -> Theme.successBg to Theme.success
        "In Progress" -> Theme.warnBg to Theme.warn
        else -> Theme.pendingBg to Theme.pendingFg
    }
    return TextView(this).apply {
        text = when(status){ "Done"->"✓ Done"; "In Progress"->"● In Progress"; else->"○ Pending" }
        textSize = 11f; setTextColor(fg); typeface = medium()
        background = rounded(bg, 20); setPadding(dp(10), dp(5), dp(10), dp(5)); gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(2)) }
    }
}
fun Context.equipmentBadge(equipment: String): TextView {
    val (label, full, bg) = when(equipment.lowercase()) {
        "kettlebell" -> Triple("KB", "Kettlebell", "#9A3412")
        "dumbbell" -> Triple("DB", "Dumbbell", "#075985")
        "bodyweight" -> Triple("BW", "Bodyweight", "#5B21B6")
        "machine" -> Triple("MC", "Machine", "#065F46")
        else -> Triple("—", equipment.ifBlank { "No gear" }, "#64748B")
    }
    return TextView(this).apply {
        text = "$label • $full"; textSize = 11f; setTextColor(Color.WHITE); typeface = medium()
        background = roundedBg(bg, 20); setPadding(dp(10), dp(5), dp(10), dp(5)); gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), dp(6), dp(4)) }
    }
}
fun sectionColor(type: String): Int = when(type.lowercase()) { "warmup" -> Color.parseColor("#D97706"); "stretch" -> Color.parseColor("#0D9488"); else -> Color.parseColor("#2563EB") }
fun sectionLabel(type: String): String = when(type.lowercase()) { "warmup" -> "Warm Up"; "stretch" -> "Cool Down"; else -> "Exercise" }
fun doItLine(sets: List<SessionSet>): String {
    if (sets.isEmpty()) return "Do it at an easy, controlled pace"
    val reps = sets.first().reps.trim()
    return if (reps.contains("min") || reps.contains("sec") || reps.contains("/")) "Do ${sets.size} set${if(sets.size>1)"s" else ""} of $reps"
    else "Do ${sets.size} sets of $reps"
}
fun Context.sectionHeader(title: String, subtitle: String = ""): LinearLayout {
    val color = when(title) { "Warm-up", "Warm Up" -> Theme.warmAmber; "Stretching", "Cool Down" -> Theme.coolTeal; else -> Theme.primary }
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(16), 0, dp(6)) }
    row.addView(View(this).apply { setBackgroundColor(color); layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)) })
    row.addView(makeText("  $title${if(subtitle.isNotBlank()) "  •  $subtitle" else ""}", 17f, true, color))
    return row
}
fun openUrl(context: Context, url: String) {
    if (url.isBlank()) return
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {}
}
fun strike(tv: TextView, on: Boolean) {
    tv.paintFlags = if (on) tv.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else tv.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
}

object WeekManager {
    data class State(val weekNumber: Int, val weekStart: String)
    fun reconcile(context: Context, db: DbHelper): State {
        val prefs = context.getSharedPreferences("workout_prefs", Context.MODE_PRIVATE)
        val today = DateUtil.today()
        val currentSunday = DateUtil.sundayOf(today)
        val currentSundayStr = DateUtil.fmt(currentSunday)
        if (!prefs.contains("weekNumber") || !prefs.contains("weekStart")) {
            prefs.edit().putInt("weekNumber", 1).putString("weekStart", currentSundayStr).apply()
            return State(1, currentSundayStr)
        }
        var weekNumber = prefs.getInt("weekNumber", 1)
        var storedStr = prefs.getString("weekStart", currentSundayStr)!!
        var stored = DateUtil.parse(storedStr)
        if (stored.after(currentSunday)) {
            prefs.edit().putInt("weekNumber", weekNumber).putString("weekStart", currentSundayStr).apply()
            return State(weekNumber, currentSundayStr)
        }
        while (stored.before(currentSunday)) {
            if (db.hasCompletedInWeek(DateUtil.fmt(stored))) weekNumber++
            stored.add(CalendarCompat.DAY, 7)
        }
        val finalStr = DateUtil.fmt(stored)
        prefs.edit().putInt("weekNumber", weekNumber).putString("weekStart", finalStr).apply()
        return State(weekNumber, finalStr)
    }
    fun unit(context: Context): String = context.getSharedPreferences("workout_prefs", Context.MODE_PRIVATE).getString("unit", "lb") ?: "lb"
    fun setUnit(context: Context, unit: String) { context.getSharedPreferences("workout_prefs", Context.MODE_PRIVATE).edit().putString("unit", unit).apply() }
}
object CalendarCompat { const val DAY = java.util.Calendar.DAY_OF_YEAR }

object Beginner {
    fun beginnerMode(context: Context): Boolean = context.getSharedPreferences("workout_prefs", Context.MODE_PRIVATE).getBoolean("beginnerMode", true)
    fun setBeginnerMode(context: Context, on: Boolean) { context.getSharedPreferences("workout_prefs", Context.MODE_PRIVATE).edit().putBoolean("beginnerMode", on).apply() }
    fun effortSuggestion(effort: String, unit: String): String = when(effort) {
        "Too Easy" -> if (unit=="kg") "try +2.5 kg / +2 reps next time" else "try +5 lb / +2 reps next time"
        "Just Right" -> "try same weight next time"
        "Too Hard" -> "try lighter next time"
        else -> ""
    }
}

object SwapMap {
    // current exercise name -> (alternative name, equipment)
    val map: Map<String, Pair<String,String>> = mapOf(
        "Pike Push-up" to ("Incline Push-up" to "Bodyweight"),
        "DB Walking Lunge" to ("Bodyweight Lunge" to "Bodyweight"),
        "KB Swing (light)" to ("KB Deadlift (Light)" to "Kettlebell"),
        "Inverted Row" to ("Seated Cable Row (Light)" to "Machine"),
        "Leg Extension" to ("Sit-to-Stand" to "Bodyweight"),
        "KB Goblet Squat" to ("Bodyweight Squat" to "Bodyweight"),
        "KB Step-Up" to ("Bodyweight Step-Up" to "Bodyweight"),
        "Leg Press" to ("Sit-to-Stand" to "Bodyweight"),
        "Push-ups" to ("Incline Push-up" to "Bodyweight"),
        "Diamond Push-up" to ("Wall Push-up" to "Bodyweight"),
        "Incline DB Press" to ("Chest Press Machine (Light)" to "Machine"),
        "Chest Press Machine" to ("Incline Push-up" to "Bodyweight"),
        "Single-Arm KB Press" to ("DB Shoulder Press (Light)" to "Dumbbell"),
        "DB Staggered Romanian Deadlift" to ("Glute Bridge" to "Bodyweight"),
        "KB Romanian Deadlift" to ("Glute Bridge" to "Bodyweight"),
        "Cable Glute Kickback" to ("Glute Bridge" to "Bodyweight"),
        "Hip Thrust Machine" to ("Glute Bridge" to "Bodyweight"),
        "Wall Sit" to ("Bodyweight Squat" to "Bodyweight"),
        "Plank" to ("Knee Plank" to "Bodyweight"),
        "Lat Pulldown" to ("Seated Cable Row (Light)" to "Machine")
    )
    fun forExercise(name: String): Pair<String,String>? = map[name] ?: map.entries.firstOrNull { name.equals(it.key, ignoreCase=true) }?.value
}

object CsvExporter {
    private fun esc(s: String?): String {
        val v = s ?: ""
        return if (v.contains(",") || v.contains("\"") || v.contains("\n")) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }
    fun buildCsv(context: Context, db: DbHelper): String {
        val unit = WeekManager.unit(context)
        val sb = StringBuilder()
        sb.append("date,week_number,week_start,workout,exercise,exercise_order,exercise_type,equipment,set_number,weight,weight_unit,reps,completed,exercise_youtube_url,workout_duration_sec\n")
        for (s in db.getSessions().sortedBy { it.date }) {
            val dur = if (s.startedAt > 0) s.elapsedSec.toString() else ""
            for ((idx, ex) in db.getSessionExercises(s.id).withIndex()) {
                val sets = db.getSets(ex.id)
                if (sets.isEmpty()) {
                    sb.append(listOf(esc(s.date), s.weekNumber.toString(), esc(s.weekStart), esc(s.routineName), esc(ex.name), (idx+1).toString(), esc(ex.type), esc(ex.equipment), "", "", esc(unit), "", if(s.completed)"true" else "false", esc(ex.youtubeUrl), dur).joinToString(",")).append("\n")
                } else for (set in sets) {
                    val w = if (set.isBodyweight) "BW" else set.weight
                    sb.append(listOf(esc(s.date), s.weekNumber.toString(), esc(s.weekStart), esc(s.routineName), esc(ex.name), (idx+1).toString(), esc(ex.type), esc(ex.equipment), set.setNumber.toString(), esc(w), esc(unit), esc(set.reps), if(set.isDone)"true" else "false", esc(ex.youtubeUrl), dur).joinToString(",")).append("\n")
                }
            }
        }
        return sb.toString()
    }
}
