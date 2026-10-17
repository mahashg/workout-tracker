package com.mahesh.workouttracker

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class HistoryActivity : AppCompatActivity() {
    private lateinit var db: DbHelper

    // v3.12 calendar + filter state. These are plain instance fields:
    // they start folded / unfiltered on every fresh entry (field
    // initializers below) and survive onResume re-renders, because
    // onResume only calls render() again — it never resets them.
    private var calOpen: Boolean = false
    private var viewYear: Int = 0
    private var viewMonth: Int = 0 // 0-based, Calendar.MONTH
    private var filterDate: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = DbHelper(this)
        // v3.12: the viewed month starts at today's month.
        val today = DateUtil.today()
        viewYear = today.get(Calendar.YEAR)
        viewMonth = today.get(Calendar.MONTH)
        render()
    }

    override fun onResume() { super.onResume(); if (::db.isInitialized) render() }

    /** v3.12 session kind (mock r2): the same detection the rest of the
     *  app uses — circuit sessions carry a "circuit:" focus, home
     *  sessions are the Movie deck, everything else is a gym session.
     *  Drives both the calendar dots and the per-row session logo. */
    private enum class SessionKind { GYM, HOME, CIRCUIT }

    private fun kindOf(s: SessionInfo): SessionKind = when {
        CircuitsData.isCircuit(s) -> SessionKind.CIRCUIT
        s.routineName == MovieDeck.ROUTINE_NAME -> SessionKind.HOME
        else -> SessionKind.GYM
    }

    /** Exact dot colors from the approved mock r2 (.dot.gym/.home/.circ). */
    private fun kindColor(kind: SessionKind): Int = when (kind) {
        SessionKind.GYM -> Theme.primary
        SessionKind.HOME -> Color.parseColor("#DE9FBB")
        SessionKind.CIRCUIT -> Color.parseColor("#4C9A57")
    }

    private fun oval(color: Int): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun dotView(color: Int, sizeDp: Int): View = View(this).apply {
        background = oval(color)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun render() {
        val root = tabScaffold("History")
        root.addView(screenTitle("History").apply {
            (layoutParams as LinearLayout.LayoutParams).apply { height = dp(40); setMargins(0, 0, 0, 0) }
            gravity = Gravity.CENTER_VERTICAL
        })
        val sessions = db.getSessions()

        // v3.12: History is an approved scroll context — one ScrollView
        // holds the calendar bar, the (optional) calendar card, the
        // (optional) filter line and the full session list. The old
        // 5-per-page pager is gone; the list shows every session, or
        // just the tapped day's when a calendar filter is active.
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(box)
        }
        root.addView(scroll)

        box.addView(calendarBar(sessions))
        if (calOpen) box.addView(calendarCard(sessions))

        val sel = filterDate
        val visible = if (sel != null) sessions.filter { it.date == sel } else sessions
        if (sel != null) box.addView(filterLine(sel, visible.size))

        if (sessions.isEmpty()) {
            box.addView(caption("No sessions yet."))
        } else if (sel != null && visible.isEmpty()) {
            // v3.12: a tapped day with nothing logged is a rest day,
            // not an error — one quiet centered line (mock r2 note).
            box.addView(TextView(this).apply {
                text = "🌙 Rest day — no workouts"; textSize = 13.5f; setTextColor(Theme.textSecondary)
                gravity = Gravity.CENTER
                setPadding(0, dp(18), 0, dp(18))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
        }
        for (s in visible) {
            // v3.14: completed workouts open the READ-ONLY history
            // detail (done exercises only, scrollable, discardable,
            // no editing). In-progress sessions still open the live
            // SessionActivity, where the workout is worked on.
            val open = {
                val target = if (s.completed) HistoryDetailActivity::class.java else SessionActivity::class.java
                val i = Intent(this, target); i.putExtra("sessionId", s.id); startActivity(i)
            }
            val card = cardLayout(tappable = true) { open() }
            // v3.8.3 ROOT-CAUSE FIX: the cards were given height 0 +
            // weight 1 inside this page-filling box, so a page with ONE
            // session stretched that session's card to the full screen
            // height — and with the inner row at MATCH_PARENT height +
            // CENTER_VERTICAL gravity, the entry floated in the middle
            // of a giant empty card (the user's screenshot). Cards now
            // wrap their content and list top-down like a normal
            // history list; any leftover page space stays empty below.
            (card.layoutParams as LinearLayout.LayoutParams).apply { height = LinearLayout.LayoutParams.WRAP_CONTENT; weight = 0f; setMargins(0, dp(4), 0, dp(4)) }
            card.setPadding(dp(12), dp(10), dp(12), dp(10))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
            // v3.12: the mock's session logo — a pastel squircle with
            // the kind's emoji — leads every row. Deliberate choice:
            // filtered (single-day) rows keep this exact standard row
            // format; the filter changes WHICH rows show, never how a
            // row looks.
            val (logoEmoji, logoTint) = when (kindOf(s)) {
                SessionKind.CIRCUIT -> "⚡" to IconTints.GREEN
                SessionKind.HOME -> "🏠" to IconTints.PINK
                SessionKind.GYM -> "🏋️" to IconTints.BLUE
            }
            val logo = settingsIcon(logoEmoji, logoTint, 44, 21f)
            (logo.layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, 0, dp(12), 0)
            row.addView(logo)
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            info.addView(makeText("${DateUtil.display(s.date)} • Week ${s.weekNumber}", 11f, true, Theme.textSecondary))
            val name = makeText(s.routineName, 15f, true)
            name.maxLines = 1; name.ellipsize = android.text.TextUtils.TruncateAt.END
            info.addView(name)
            val durText = if (s.startedAt > 0) WorkoutTimer.formatDuration(if (s.completed) s.elapsedSec else WorkoutTimer.liveElapsedSec(this, s)) else ""
            // v3 sessions store focus as part ids ("back,biceps") — show names.
            // v3.11: circuit sessions store focus as "circuit:<id>" — label
            // them as a circuit instead of leaking the raw token.
            val focusLabel = when {
                s.focus.startsWith(CircuitsData.FOCUS_PREFIX) -> "⚡ Circuit"
                else -> s.focus.split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ") { tok -> BodyParts.byId(tok)?.name ?: tok }
            }
            // v3.8.3: build the sub-line from non-empty segments only —
            // a Movie Movements session has no focus parts, and the old
            // concatenation rendered a stray leading "· 8 min".
            val sub = makeText(listOf(focusLabel, durText).filter { it.isNotBlank() }.joinToString(" • "), 11f, false, Theme.textSecondary)
            sub.maxLines = 1; sub.ellipsize = android.text.TextUtils.TruncateAt.END
            info.addView(sub)
            row.addView(info)
            row.addView(statusPill(if (s.completed) "Done" else "In Progress"))
            row.addView(iconView(R.drawable.ic_chevron, 20))
            card.addView(row)
            box.addView(card)
        }
    }

    /** v3.12 calendar bar (mock .calbar): always visible, folded by
     *  default. Closed it is a slim white row naming the current month
     *  and its completed-session day count; open it gains the primary
     *  stroke, loses its bottom corners (the calendar card attaches
     *  below) and its sub-text becomes the fold hint. */
    private fun calendarBar(sessions: List<SessionInfo>): LinearLayout {
        val today = DateUtil.today()
        val monthPrefix = SimpleDateFormat("yyyy-MM", Locale.US).format(today.time)
        val activeDays = sessions.filter { it.completed && it.date.startsWith(monthPrefix) }.map { it.date }.distinct().size
        val monthName = SimpleDateFormat("MMMM", Locale.US).format(today.time)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, 0) }
            val strokePx = (1.5f * resources.displayMetrics.density).toInt()
            background = GradientDrawable().apply {
                setColor(Theme.surface)
                if (calOpen) {
                    val r = dp(16).toFloat()
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                } else {
                    cornerRadius = dp(16).toFloat()
                }
                setStroke(strokePx, if (calOpen) Theme.primary else Theme.stroke)
            }
            isClickable = true
            setOnClickListener {
                calOpen = !calOpen
                if (calOpen) {
                    // Opening lands on today's month — or on the
                    // filtered day's month when a filter is active, so
                    // the day the user is looking at stays in view.
                    val target = filterDate?.let { DateUtil.parse(it) } ?: DateUtil.today()
                    viewYear = target.get(Calendar.YEAR)
                    viewMonth = target.get(Calendar.MONTH)
                }
                render()
            }
        }
        bar.addView(TextView(this).apply {
            text = "📅"; textSize = 20f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        bar.addView(makeText("Calendar", 15f, true).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(11), 0, 0, 0) }
        })
        bar.addView(makeText(if (calOpen) "tap again to hide" else "$monthName · $activeDays active days", 12f, false, Theme.textSecondary).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(6), 0, 0, 0) }
        })
        bar.addView(TextView(this).apply {
            text = if (calOpen) "⌄" else "›"; textSize = 17f; setTextColor(Theme.textTertiary); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        return bar
    }

    /** v3.12 expanded calendar (mock .calcard): attached to the bar —
     *  only its bottom corners are rounded (20dp) and it carries the
     *  same primary stroke. Dots mark COMPLETED sessions only, one per
     *  kind present that day, in gym / home / circuit order. */
    private fun calendarCard(sessions: List<SessionInfo>): LinearLayout {
        val today = DateUtil.today()
        val todayStr = DateUtil.fmt(today)
        val completedByDate = sessions.filter { it.completed }.groupBy { it.date }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            val strokePx = (1.5f * resources.displayMetrics.density).toInt()
            val r = dp(20).toFloat()
            background = GradientDrawable().apply {
                setColor(Theme.surface)
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
                setStroke(strokePx, Theme.primary)
            }
        }

        // ---- Header: "<Month> <Year>" + ‹ › month arrows ----
        // The arrows stop at the data's range: the oldest session's
        // month on the left, today's month on the right.
        val oldestDate = sessions.minByOrNull { it.date }?.date
        val earliestCal = oldestDate?.let { DateUtil.parse(it) } ?: today
        val earliestIdx = earliestCal.get(Calendar.YEAR) * 12 + earliestCal.get(Calendar.MONTH)
        val latestIdx = today.get(Calendar.YEAR) * 12 + today.get(Calendar.MONTH)
        val viewIdx = viewYear * 12 + viewMonth
        val viewCal = Calendar.getInstance().apply { clear(); set(viewYear, viewMonth, 1); DateUtil.zero(this) }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        header.addView(makeText(SimpleDateFormat("MMMM yyyy", Locale.US).format(viewCal.time), 15.5f, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 0, 0) }
        })
        fun monthArrow(glyph: String, delta: Int, disabled: Boolean): TextView = TextView(this).apply {
            text = glyph; textSize = 14f; setTextColor(Theme.primaryDark); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = oval(Theme.surfaceVariant)
            layoutParams = LinearLayout.LayoutParams(dp(29), dp(29)).apply { if (delta > 0) setMargins(dp(6), 0, 0, 0) }
            alpha = if (disabled) 0.35f else 1f
            if (!disabled) {
                isClickable = true
                setOnClickListener {
                    val c = Calendar.getInstance().apply { clear(); set(viewYear, viewMonth, 1) }
                    c.add(Calendar.MONTH, delta)
                    viewYear = c.get(Calendar.YEAR); viewMonth = c.get(Calendar.MONTH)
                    render()
                }
            }
        }
        header.addView(monthArrow("‹", -1, viewIdx <= earliestIdx))
        header.addView(monthArrow("›", 1, viewIdx >= latestIdx))
        card.addView(header)

        // ---- Weekday header (Sunday first, matching DateUtil) ----
        val dow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(9), 0, 0) }
        }
        for (letter in listOf("S", "M", "T", "W", "T", "F", "S")) {
            dow.addView(TextView(this).apply {
                text = letter; textSize = 10.5f; setTextColor(Theme.textTertiary); gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        card.addView(dow)

        // ---- Day grid ----
        // Leading/trailing cells are the adjacent months' days, shown
        // muted and inert; future days in the viewed month are muted
        // and inert too. Everything else is a filter tap target.
        val leading = DateUtil.weekdayIndex(viewCal)
        val daysInMonth = viewCal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val prevCal = (viewCal.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
        val daysInPrev = prevCal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val totalCells = leading + daysInMonth
        val trailing = (7 - totalCells % 7) % 7

        val cells = mutableListOf<Triple<Int, Boolean, String?>>() // day number, in-month, date string
        for (i in 0 until leading) cells.add(Triple(daysInPrev - leading + 1 + i, false, null))
        for (d in 1..daysInMonth) {
            val c = (viewCal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, d) }
            cells.add(Triple(d, true, DateUtil.fmt(c)))
        }
        for (d in 1..trailing) cells.add(Triple(d, false, null))

        for (week in cells.chunked(7)) {
            val weekRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            for ((dayNum, inMonth, dateStr) in week) {
                val isFuture = dateStr != null && dateStr > todayStr
                val isToday = dateStr != null && dateStr == todayStr
                val isSelected = dateStr != null && dateStr == filterDate
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
                    if (isSelected) background = roundedBg(Theme.surfaceVariant, 11)
                    if (inMonth && !isFuture && dateStr != null) {
                        isClickable = true
                        setOnClickListener { filterDate = dateStr; render() }
                    }
                }
                cell.addView(TextView(this).apply {
                    text = dayNum.toString(); textSize = 14f; gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif-medium", if (isToday) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(when {
                        !inMonth -> Color.parseColor("#C4BCD2")
                        isFuture -> Color.parseColor("#B9B0CC")
                        isToday -> Theme.primaryDark
                        else -> Theme.textPrimary
                    })
                    if (isToday) background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(2), Theme.primary)
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(29), dp(25))
                })
                val dots = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(6)).apply { setMargins(0, dp(1), 0, 0) }
                }
                if (inMonth && dateStr != null) {
                    val kindsPresent = completedByDate[dateStr].orEmpty().map { kindOf(it) }.distinct()
                    for (kind in listOf(SessionKind.GYM, SessionKind.HOME, SessionKind.CIRCUIT)) {
                        if (kind in kindsPresent) {
                            val dot = dotView(kindColor(kind), 6)
                            if (dots.childCount > 0) (dot.layoutParams as LinearLayout.LayoutParams).setMargins(dp(3), 0, 0, 0)
                            dots.addView(dot)
                        }
                    }
                }
                cell.addView(dots)
                weekRow.addView(cell)
            }
            card.addView(weekRow)
        }

        // ---- Legend ----
        card.addView(View(this).apply {
            setBackgroundColor(Theme.surfaceVariant)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(0, dp(7), 0, 0) }
        })
        val legend = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), 0, 0) }
        }
        for ((kind, label) in listOf(SessionKind.GYM to "Gym", SessionKind.HOME to "At home", SessionKind.CIRCUIT to "Circuits")) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp(13), 0) }
            }
            item.addView(dotView(kindColor(kind), 8))
            item.addView(makeText(label, 11f, false, Theme.textSecondary).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(5), 0, 0, 0) }
            })
            legend.addView(item)
        }
        card.addView(legend)
        return card
    }

    /** v3.12 filter line (mock .filterline): names the tapped day and
     *  how many sessions it holds, with a pill that clears the filter
     *  back to the full list. */
    private fun filterLine(dateStr: String, count: Int): LinearLayout {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(14), 0, dp(2)) }
        }
        val dayLabel = SimpleDateFormat("EEE, MMM d", Locale.US).format(DateUtil.parse(dateStr).time)
        line.addView(makeText("$dayLabel — $count workout${if (count == 1) "" else "s"}", 14f, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 0, 0) }
        })
        line.addView(TextView(this).apply {
            text = "✕ Show all"; textSize = 12.5f; setTextColor(Theme.primary); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = roundedBg(Theme.surfaceVariant, 999)
            setPadding(dp(11), dp(5), dp(11), dp(5))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            isClickable = true
            setOnClickListener { filterDate = null; render() }
        })
        return line
    }
}
