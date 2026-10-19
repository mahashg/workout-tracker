package com.mahesh.workouttracker

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import kotlin.math.abs

data class UndoSnap(val exId: Long, val status: String, val effort: String, val sets: List<SessionSet>)

class CardSessionActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var sessionId: Long=-1
    private var sectionType: String="Main"
    private var deck: List<SessionExercise> = emptyList()
    private var index: Int=0
    private val undoStack = ArrayDeque<UndoSnap>()
    private var timerChip: TimerChipView? = null
    // v3.7: hub rows open the card AT a specific exercise.
    private var startExId: Long = -1
    private var restChip: RestChipView? = null
    private var pauseDisc: TextView? = null
    private var stripRv: androidx.recyclerview.widget.RecyclerView? = null
    private var firstResume = true
    private var downX=0f; private var downY=0f

    // --- Swipe-card (v2.2) state: the whole card is one moving view ---
    private var cardWrap: FrameLayout? = null      // dragged container (card + tint + labels)
    private var cardSurface: LinearLayout? = null   // the visible card itself (shadow/border/band/content)
    private var tintOverlay: View? = null
    private var doneLabel: TextView? = null
    private var skipLabel: TextView? = null
    private var upLabel: TextView? = null
    private var downLabel: TextView? = null
    // v3.3: persistent on-card swipe cues (pills on the photo band). At rest
    // they sit slightly translucent; during a drag the matching cue goes
    // fully opaque and scales up while the other two fade back.
    private var cueSkip: TextView? = null
    private var cueDone: TextView? = null
    private var cueLater: TextView? = null
    private val cueRestAlpha = 0.9f
    private val photoLoopers = mutableListOf<PhotoLooper>()
    private var peekNear: View? = null
    private var peekFar: View? = null
    private var draggingCard = false
    // v3.13: posture/cues disclosure on the card — the exercise whose
    // full Beginner content is expanded, or -1 when collapsed.
    private var postureOpenFor: Long = -1
    // v3.13: the inline logger panel's staged state, keyed by
    // "<exerciseId>:<setId>" so it survives render() passes for the
    // same next set but is rebuilt (via SetLogger.panelPrefill) the
    // moment the next set changes.
    private var panelState: SetLogger.SetPanelState? = null
    private var panelKey: String = ""
    private var cardAnimating = false
    private var thresholdHapticDone = false
    private var animateEntryOnNextRender = false

    /** Strict commit distance for horizontal (L/R) swipes: a light nudge never commits. */
    private fun commitThresholdX(v: View): Float = maxOf(0.36f * v.width, dp(130).toFloat())
    /** Strict commit distance for the up (Log Set) swipe. */
    private fun commitThresholdY(v: View): Float = maxOf(0.30f * v.height, dp(110).toFloat())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); db=DbHelper(this)
        sessionId=intent.getLongExtra("sessionId",-1); sectionType=intent.getStringExtra("sectionType") ?: "Main"
        startExId=intent.getLongExtra("exerciseId",-1)
        if(sessionId<0){finish();return}
        reloadDeck(); moveToPending()
        // v3.7: opened from the hub (or the strip) at a chosen exercise —
        // land on it even if it is already handled (viewing/re-logging).
        // v3.8.2: Start / Resume also land here, on the first pending.
        if (startExId > 0) {
            val pos = deck.indexOfFirst { it.id == startExId }
            if (pos >= 0) index = pos
        }
        render()
    }

    override fun onPause() { super.onPause(); timerChip?.stopTicking(); timerChip?.foldNow(); photoLoopers.forEach { it.stop() }; cancelInterstitial(); RestTimer.persistNow() }
    override fun onResume() {
        super.onResume(); photoLoopers.forEach { it.start() }
        // v3.7: returning to the card (e.g. back from the form video)
        // re-renders so the scroller strip and counts reflect any
        // status changes. The first resume is onCreate's own render.
        if (firstResume) firstResume = false else if (::db.isInitialized && sessionId > 0) render()
        // v3.12: re-attach to a rest that outlived this screen
        // (rotation reshows the ring; process death rebuilds the
        // engine from prefs). The factory mirrors the two show()
        // call sites: an exercise rest completes into
        // advanceToPending (whose stale-target guard keeps a late
        // fire safe), a set rest just re-renders.
        if (::db.isInitialized && sessionId > 0) {
            val s = db.getSession(sessionId)
            if (s != null && !s.completed) RestTimer.attach(this, sessionId) { kind, target, from ->
                if (kind == "exercise") ({ advanceToPending(target, from) }) else ({ render() })
            }
        }
    }
    override fun onDestroy() { super.onDestroy(); timerChip?.stopTicking(); photoLoopers.forEach { it.stop() }; cancelInterstitial() }

    /** Do-later applies while another pending exercise remains in this section. */
    private fun laterAvailable(): Boolean = deck.count { db.effectiveStatus(it) == "pending" } > 1

    private fun reloadDeck(){ deck=db.getSessionExercises(sessionId).filter{it.type.equals(sectionType,true)} }
    private fun moveToPending(){
        val i=deck.indexOfFirst{ db.effectiveStatus(it)=="pending" }
        index=if(i>=0) i else deck.size
    }
    private fun current(): SessionExercise? = deck.getOrNull(index)
    private fun snapshot(ex: SessionExercise){ undoStack.addLast(UndoSnap(ex.id, ex.status, ex.effort, db.getSets(ex.id))) }

    private fun dragCardBand(card: LinearLayout) {
        card.addView(View(this).apply { setBackgroundColor(sectionColor(sectionType)); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply { setMargins(0, 0, 0, dp(8)) } })
    }

    private fun render(){
        // v3.18 Simple Mode: read once per render. Every simple-mode
        // branch below is guarded by this flag so full mode renders
        // exactly as v3.17 did.
        val simple = SimpleMode.isOn(this)
        // Views from the previous render are discarded below; stop their
        // photo crossfade loops first (v3.1).
        photoLoopers.forEach { it.stop() }; photoLoopers.clear()
        // v3.9.2: any render supersedes a pending section interstitial
        // (renderInterstitial below re-arms its own countdown).
        cancelInterstitial()
        reloadDeck()
        if(index !in deck.indices){
            // The deck is exhausted. If a LATER section still has
            // pending exercises, the v3.9.2 auto-advancing interstitial
            // takes over (it replaced the v3.7 transition card);
            // otherwise the plain completion state (fixed, no scrolling).
            val nextType = nextSectionWithPending()
            if (nextType != null) { renderInterstitial(nextType); return }
            val root=fitRoot(); setContentView(root)
            root.addView(topBar(sectionLabel(sectionType), "Section complete"))
            root.addView(makeText("${sectionLabel(sectionType)} complete!",26f,true,Color.parseColor("#15803D")))
            val done=deck.count{db.effectiveStatus(it)=="done"}; val skipped=deck.count{db.effectiveStatus(it)=="skipped"}
            root.addView(makeText("$done done • $skipped skipped",15f,false))
            if(undoStack.isNotEmpty()) root.addView(makeButton("Undo last action"){doUndo(); render()}.apply { (layoutParams as LinearLayout.LayoutParams).apply { height = dp(48); setMargins(0, dp(4), 0, 0) } })
            // v3.7: the sections screen left the flow — this goes to the hub.
            // v3.18 Simple Mode: there is no hub in the simple flow, so
            // the completion state's primary action terminates the
            // workout instead (the card's End pill is the normal path;
            // this state is reached only by exhausting the deck).
            if (simple) {
                root.addView(makeButton("Finish workout"){doFinishWorkout()}.apply { (layoutParams as LinearLayout.LayoutParams).apply { height = dp(48); setMargins(0, dp(4), 0, 0) } })
            } else {
                root.addView(makeButton("All Exercises"){navigateToHub()}.apply { (layoutParams as LinearLayout.LayoutParams).apply { height = dp(48); setMargins(0, dp(4), 0, 0) } })
            }
            Toast.makeText(this,"${sectionLabel(sectionType)} complete!",Toast.LENGTH_SHORT).show()
            return
        }
        val ex=current()!!
        val sets=db.getSets(ex.id)
        val doneSets=sets.count{it.isDone}
        val root=fitRoot(); setContentView(root)
        // v3.13 (r4 Screen 4): the calm card — hairline, top row, ONE
        // circular-photo card with the logger panel INLINE. No top
        // 96dp card, no peek cards, no photo band, no button row:
        // Skip / Do later are quiet text actions under the panel and
        // the hub lives on the strip's circle (buildStrip).
        val allSessionExs = db.getSessionExercises(sessionId)
        val handledCount = allSessionExs.count { db.effectiveStatus(it) != "pending" }
        root.addView(BendHairlineView(this).apply {
            fraction = if (allSessionExs.isEmpty()) 0f else handledCount.toFloat() / allSessionExs.size
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        // Top row: routine name + exercise position on the left, the
        // existing timer / rest chips on the right.
        val session = db.getSession(sessionId)
        val sessionPos = allSessionExs.indexOfFirst { it.id == ex.id }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(12), 0, 0) }
        }
        val topLeft = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topLeft.addView(bendScreenName(session?.routineName?.takeIf { it.isNotBlank() } ?: sectionLabel(sectionType)).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
        })
        topLeft.addView(bendMicro("EXERCISE ${sessionPos + 1} OF ${allSessionExs.size}").apply {
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, dp(2), 0, 0)
        })
        topRow.addView(topLeft)
        if (session != null && !session.completed) {
            db.ensureSessionStarted(sessionId)
            // v3.7: the REST chip sits next to the elapsed timer while a
            // rest is active (RestTimer engine state), tap reopens the ring.
            val chipRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val chip = TimerChipView(this)
            // v3.18 Simple Mode: taller chip (38dp) + the 18sp label.
            chip.bigText = simple
            chip.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(if (simple) 38 else 30))
            chip.bind(this, db, db.getSession(sessionId) ?: session) { refreshPauseDisc() }
            // v3.12: swipe right on the timer bar = finish workout.
            chip.onFinishSwipe = { confirmFinishWorkout() }
            chip.startTicking()
            timerChip = chip
            chipRow.addView(chip)
            // v3.17: pause disc beside the timer chip — a visible
            // pause/resume control mirroring the chip's state (the
            // chip's tap/swipe gestures remain, via togglePause()).
            // v3.18 Simple Mode: the disc grows to a 54dp target with a
            // 23f glyph (full mode keeps 38dp / 15f). refreshPauseDisc()
            // re-asserts the same glyph size on every toggle.
            val discSize = if (simple) 54 else 38
            val disc = bendSoftCircle(if (WorkoutTimer.isPaused(this, sessionId)) "▶" else "⏸", discSize, if (simple) 23f else 15f)
            (disc.background as? GradientDrawable)?.setStroke(dp(1), Theme.stroke)
            disc.layoutParams = LinearLayout.LayoutParams(dp(discSize), dp(discSize)).apply { setMargins(dp(9), 0, 0, 0) }
            disc.isClickable = true
            disc.setOnClickListener { timerChip?.togglePause() }
            if (WorkoutTimer.isPaused(this, sessionId)) disc.setTextColor(Theme.primary)
            pauseDisc = disc
            chipRow.addView(disc)
            val rc = RestChipView(this)
            rc.bind(this)
            rc.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(8), 0, 0, 0) }
            restChip = rc
            chipRow.addView(rc)
            topRow.addView(chipRow)
        }
        root.addView(topRow)

        // ---- Card deck: ONE moving card (no peek cards behind it) ----
        val deckBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(0, dp(8), 0, dp(6)) }
            clipChildren = false; clipToPadding = false
        }
        peekFar = null; peekNear = null
        // The moving card: wrapper carries content + direction tint +
        // faded labels, so everything travels together.
        val wrap = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            clipChildren = false; clipToPadding = false
        }
        val wrapRef = wrap
        cardSurface = null
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val muscles = DbHelper.parseMuscles(ex.targetMuscles, ex.name)
        val last = db.lastPerformedForExercise(ex.name, sessionId)
        // 1. Circle block: circular photo (or centered body map) with
        // the sets-progress ring over it.
        run {
            val size = if (isCompactScreen()) 200 else 236
            val circleBox = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, dp(10), 0, 0) }
            }
            val frame = bendCircleFrame(size)
            val photoAsset = PhotoLib.assetForSessionExercise(this, db, ex)
            val photoBmp = photoAsset?.let { PhotoLib.bitmap(this, it) }
            if (photoBmp != null) {
                frame.addView(android.widget.ImageView(this).apply {
                    setImageBitmap(photoBmp); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                })
                val photo2 = photoAsset?.let { PhotoLib.secondBitmap(this, it) }
                if (photo2 != null) {
                    val iv2 = android.widget.ImageView(this).apply {
                        setImageBitmap(photo2); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; alpha = 0f
                        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    }
                    frame.addView(iv2)
                    val looper = PhotoLooper(iv2); photoLoopers.add(looper); looper.start()
                }
            } else {
                val mapH = (size * 0.58f).toInt()
                frame.addView(BodyMapView(this, muscles, true).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(mapH * 76 / 96), dp(mapH), Gravity.CENTER)
                })
            }
            circleBox.addView(frame)
            circleBox.addView(BendRingView(this).apply {
                fraction = if (sets.isEmpty()) 0f else doneSets.toFloat() / sets.size
                strokeDp = 4f
                layoutParams = FrameLayout.LayoutParams(dp(size), dp(size))
            })
            col.addView(circleBox)
        }
        // 2. Name.
        col.addView(bendName(ex.name).apply {
            (layoutParams as LinearLayout.LayoutParams).apply { width = LinearLayout.LayoutParams.MATCH_PARENT; setMargins(0, dp(14), 0, 0) }
        })
        // 3. The ONE cue line: the first posture check (else the first
        // cue), with a chevron when the disclosure holds more.
        val postureItems = (ex.postureCheck.ifBlank { DbHelper.postureForName(ex.name, ex.type) })
            .split(";").map { it.trim() }.filter { it.isNotEmpty() }
        val cueItems = (ex.cues.ifBlank { DbHelper.cuesForName(ex.name, ex.equipment, ex.type) })
            .split(";").map { it.trim() }.filter { it.isNotEmpty() }
        val firstLine = postureItems.firstOrNull() ?: cueItems.firstOrNull()
        val hasMore = postureItems.size + cueItems.size > 1 || musclesLabel(muscles).isNotBlank() ||
            ex.equipment == "Machine" || ex.equipment == "Dumbbell" || ex.equipment == "Kettlebell"
        val postureOpen = postureOpenFor == ex.id
        if (firstLine != null) {
            val cueView = bendCue("\"$firstLine\"" + when { !hasMore -> ""; postureOpen -> "  \u25b4"; else -> "  \u25be" })
            cueView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, 0) }
            if (hasMore) {
                cueView.isClickable = true
                cueView.setOnClickListener {
                    postureOpenFor = if (postureOpenFor == ex.id) -1 else ex.id
                    render()
                }
            }
            col.addView(cueView)
        }
        // 4. Watch line: the old photo \u25b6 icon's handlers, verbatim.
        if (ex.youtubeUrl.isNotBlank()) {
            val watch = bendWatchLine()
            watch.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(2), 0, 0) }
            watch.isClickable = true
            // v3.10 (Mahesh): a tap plays the entry's YouTube Short when
            // one was verified for it and falls back to the long video;
            // a long-press always opens the full video.
            watch.setOnClickListener {
                val short = JsonSeed.shortUrlForSessionExercise(this@CardSessionActivity, db, ex)
                openUrl(this@CardSessionActivity, short ?: ex.youtubeUrl)
            }
            watch.setOnLongClickListener {
                openUrl(this@CardSessionActivity, ex.youtubeUrl)
                true
            }
            col.addView(watch)
        }
        // 5. Disclosure panel: the FULL Beginner content (the old
        // `how` card, uncapped) — shown only while this exercise is
        // the open one (postureOpenFor == ex.id).
        if (postureOpen) {
            val panel = bendPanel()
            panel.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(12), 0, 0) }
            fun slim(v: TextView, topDp: Int = 1): TextView {
                (v.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(topDp), 0, dp(1))
                return v
            }
            panel.addView(slim(makeText("What you'll feel: ${musclesLabel(muscles)}", 12.5f, false, Theme.accentSky), 2))
            if (postureItems.isNotEmpty()) {
                panel.addView(slim(makeText("Check your posture:", 13f, true, Theme.success), 4))
                for (c in postureItems) panel.addView(slim(makeText("\u2610  $c", 12f, false)))
            }
            for (c in cueItems) panel.addView(slim(makeText("\u2022  $c", 11.5f, false, Theme.textSecondary)))
            if (ex.equipment == "Machine" || ex.equipment == "Dumbbell" || ex.equipment == "Kettlebell")
                panel.addView(slim(makeText("Start light: use the lightest weight that feels easy first.", 11.5f, false, Theme.warn)))
            col.addView(panel)
        }
        // 6. Logger zone: the shared panel INLINE for the next set.
        val nextSet = sets.firstOrNull { !it.isDone }
        if (nextSet != null) {
            val key = "${ex.id}:${nextSet.id}"
            if (panelKey != key) {
                panelState = SetLogger.panelPrefill(db, sessionId, ex, nextSet, sets.indexOf(nextSet))
                panelKey = key
            }
            val panel = SetLogger.buildSetPanel(
                this,
                "SET ${nextSet.setNumber}",
                headerRight = last?.let { lp -> val ls = lp.sets.getOrNull(sets.indexOf(nextSet)); if (ls != null) "last time ${setText(ls)}" else "last time ${lastTimeLine(lp)}" } ?: "first time",
                bwExercise = ex.equipment.equals("Bodyweight", true),
                unitIsKg = WeekManager.unit(this) == "kg",
                state = panelState!!,
                onUnitFlip = null,
                onStateChange = {},
                onLog = { logCardPanelSet(ex, nextSet) }
            )
            panel.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(14), 0, 0) }
            col.addView(panel)
            // v3.18 Simple Mode: no Repeat-last-time shortcut.
            if (last != null && !simple) {
                col.addView(bendTextAction("\u21bb Repeat last time") {
                    snapshot(ex)
                    val before = db.getSets(ex.id)
                    var newly = 0
                    for ((i, s) in before.withIndex()) {
                        val ls = last.sets.getOrNull(i) ?: continue
                        if (!s.isDone) newly++
                        db.updateSet(s.copy(weight = ls.weight, reps = ls.reps, isBodyweight = ls.isBodyweight, isDone = true))
                    }
                    db.syncExerciseStatus(ex.id)
                    panelKey = ""
                    val remaining = db.getSets(ex.id).count { !it.isDone }
                    when {
                        remaining == 0 -> { db.setSessionExerciseStatus(ex.id, "done"); advanceWithRest() }
                        newly > 0 -> setRestThen(ex) { render() }
                        else -> render()
                    }
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, dp(8), 0, 0) }
                })
            }
        } else {
            val panel = bendPanel()
            panel.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(14), 0, 0) }
            panel.addView(makeText("\u2713 All sets logged", 15f, true, Theme.success).apply {
                gravity = Gravity.CENTER
                (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0)
            })
            panel.addView(bendTextAction("Edit sets") {
                SetLogger.show(this, db, sessionId, ex, onSnapshot = { snapshot(ex) }) { res ->
                    when {
                        res.remaining == 0 -> { db.setSessionExerciseStatus(ex.id, "done"); advanceWithRest() }
                        res.newlyLogged > 0 -> setRestThen(ex) { render() }
                        else -> render()
                    }
                }
            }.apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, dp(4), 0, 0) }
            })
            col.addView(panel)
        }
        // 7. Quiet text actions: Skip (the left-swipe's exit
        // animation + apply, as the old code did) and Do later.
        // v3.18 Simple Mode: that row is REPLACED by two chunky pills —
        // "⏭ Skip" (the exact same exit-animation + applySkipped path
        // as the text action / left swipe) and "⏹ End" (the existing
        // confirm-finish flow). "Do later" does not exist in simple
        // mode, so laterAvailable() is never consulted here.
        if (simple) {
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(12), 0, 0) }
            }
            fun chunkyPill(label: String, fill: Int, ink: Int, withStroke: Boolean): TextView = TextView(this).apply {
                text = label; textSize = 16.5f; setTextColor(ink); gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                background = GradientDrawable().apply {
                    setColor(fill); cornerRadius = dp(999).toFloat()
                    if (withStroke) setStroke(dp(1), Theme.stroke)
                }
                setPadding(dp(26), dp(14), dp(26), dp(14))
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            actions.addView(chunkyPill("⏭ Skip", Theme.surface, Theme.textPrimary, withStroke = true).apply {
                setOnClickListener { animateExitThen(wrapRef, -1) { applySkipped() } }
            })
            actions.addView(chunkyPill("⏹ End", Theme.primary, Color.WHITE, withStroke = false).apply {
                (layoutParams as LinearLayout.LayoutParams).setMargins(dp(10), 0, 0, 0)
                setOnClickListener { confirmFinishWorkout() }
            })
            col.addView(actions)
        } else run {
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(10), 0, 0) }
            }
            actions.addView(bendTextAction("Skip exercise") { animateExitThen(wrapRef, -1) { applySkipped() } })
            actions.addView(TextView(this).apply {
                text = "\u00b7"; textSize = 13.5f; gravity = Gravity.CENTER; setTextColor(Theme.textTertiary)
            })
            if (laterAvailable()) actions.addView(bendTextAction("Do later") { doLater() })
            col.addView(actions)
        }
        wrap.addView(col)
        // Direction tint overlay (fades in with drag progress, tinted per direction)
        val tint = View(this).apply {
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20).toFloat() }
            alpha = 0f; isClickable = false
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        tintOverlay = tint; wrap.addView(tint)
        // Faded direction labels: stamped on the card, opacity grows with drag progress.
        fun dirLabel(text: String, color: String, gravity: Int, rot: Float): TextView {
            return TextView(this).apply {
                this.text = text; textSize = 26f; setTextColor(Color.parseColor(color)); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                setPadding(dp(12), dp(6), dp(12), dp(6)); alpha = 0f; rotation = rot; isClickable = false
                background = GradientDrawable().apply { setColor(0xE6F7F9FB.toInt()); cornerRadius = dp(10).toFloat(); setStroke(dp(2), Color.parseColor(color)) }
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, gravity).apply { setMargins(dp(14), dp(14), dp(14), dp(14)) }
            }
        }
        skipLabel = dirLabel("\u2715 SKIP", "#DC2626", Gravity.TOP or Gravity.START, 10f)
        doneLabel = dirLabel("\u2713 DONE", "#15803D", Gravity.TOP or Gravity.END, -10f)
        upLabel = dirLabel("\u2191 LOG SET", "#2563EB", Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0f)
        downLabel = dirLabel("\u2193 LATER", "#7C3AED", Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0f)
        wrap.addView(skipLabel); wrap.addView(doneLabel); wrap.addView(upLabel); wrap.addView(downLabel)
        cardWrap = wrap
        deckBox.addView(wrap)
        root.addView(deckBox)

        // Drag handling on the whole card. Only reacts once movement passes touch slop,
        // so taps (e.g. Watch Form Video, which consumes its own touches as a child)
        // and light nudges never trigger a swipe action.
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        // v3.18 Simple Mode: swipe gestures are disabled entirely — the
        // listener is never installed, so the card cannot be dragged
        // and no swipe can commit. (It lived on the wrap container
        // only, so the card children's own clicks are unaffected.)
        if (!simple) wrap.setOnTouchListener { v, ev ->
            if (cardAnimating) return@setOnTouchListener true
            when(ev.action){
                MotionEvent.ACTION_DOWN -> {
                    downX=ev.rawX; downY=ev.rawY; draggingCard=false; thresholdHapticDone=false
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.pivotX = v.width / 2f; v.pivotY = v.height.toFloat() // pivot near bottom centre: feels like turning a card
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx=ev.rawX-downX; val dy=ev.rawY-downY
                    if(!draggingCard && kotlin.math.hypot(dx, dy) > slop) draggingCard=true
                    if(draggingCard){
                        val tx = commitThresholdX(v); val ty = commitThresholdY(v)
                        v.translationX = dx; v.translationY = dy
                        v.rotation = (dx / v.width.coerceAtLeast(1) * 7f).coerceIn(-8f, 8f)
                        updateDragFeedback(v, dx, dy, tx, ty)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx=ev.rawX-downX; val dy=ev.rawY-downY
                    val wasDragging = draggingCard; draggingCard=false
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    if(wasDragging){
                        val tx = commitThresholdX(v); val ty = commitThresholdY(v)
                        // Strict commit: distance only (velocity never commits), dominant axis must win clearly.
                        when {
                            abs(dx) >= tx && abs(dx) >= 1.3f * abs(dy) -> if(dx > 0) requestDone(v) else animateExitThen(v, -1) { applySkipped() }
                            dy < 0 && abs(dy) >= ty && abs(dy) >= 1.3f * abs(dx) -> animateLogSetLift(v) { logSetDialog() }
                            // v3.1: down-swipe = Do later (same as the Later button).
                            dy > 0 && abs(dy) >= ty && abs(dy) >= 1.3f * abs(dx) -> if (laterAvailable()) animateLaterDrop(v) { doLater() } else snapCardBack(v)
                            else -> snapCardBack(v)
                        }
                    }
                    true
                }
                else -> false
            }
        }
        // Entry animation: next card rises/settles into place after an advance.
        if(animateEntryOnNextRender){
            animateEntryOnNextRender=false
            wrap.alpha=0f; wrap.scaleX=0.96f; wrap.scaleY=0.96f; wrap.translationY=dp(18).toFloat()
            wrap.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        }

        // v3.13 scroller strip: directly under the card — every session
        // exercise as a tappable circular thumbnail, plus the ☰ hub
        // circle. It sits OUTSIDE the swipe card on purpose: inside it,
        // the strip's own horizontal drags would fight the card's swipe
        // handling. (The old btnRow — "+ Log set" button + swipe hint —
        // is gone: logging is the inline panel now, and the up-swipe
        // still opens the shared SetLogger sheet via logSetDialog().)
        // v3.18 Simple Mode: no strip at all — no ☰ hub circle, no
        // thumbnails, no Finish pill; the card flows straight to the
        // next exercise and End lives in the actions row above.
        if (!simple) root.addView(buildStrip(ex))
    }

    // ---- v3.7: hub navigation, section transitions, scroller strip ----

    /** v3.7 section pill on the photo (mock screen 3): white 92% pill,
     *  #1E40AF ink — "<SECTION> · n of m" for the current exercise. */
    private fun sectionPill(): TextView {
        return TextView(this).apply {
            text = "${displaySectionName(sectionType).uppercase()} · ${index + 1} of ${deck.size}"
            textSize = 11f; setTextColor(Color.parseColor("#1E40AF"))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(0xEBFFFFFF.toInt()); cornerRadius = dp(999).toFloat()
                setStroke(dp(1), Theme.stroke)
            }
            setPadding(dp(9), dp(4), dp(9), dp(4)); maxLines = 1
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(6)) }
        }
    }

    /** Mock display names: Warm-up / Workout / Cool-Down. */
    private fun displaySectionName(type: String): String = when {
        type.equals("Warmup", true) -> "Warm-up"
        type.equals("Stretch", true) -> "Cool-Down"
        else -> "Workout"
    }

    /** To the hub overview, wherever this card was opened from
     *  (CLEAR_TOP reuses the hub below us when there is one, and
     *  creates it when the card came from elsewhere). Since v3.8.2 the
     *  hub is an optional overview, not the flow's home: the ☰ chip
     *  on every card and the completion state's "All Exercises" come
     *  here; Start / Resume / Done never do. (v3.9.2 removed the
     *  transition card, the third caller this used to list.) */
    private fun navigateToHub() {
        startActivity(Intent(this, WorkoutHubActivity::class.java).apply {
            putExtra("sessionId", sessionId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    /** v3.17: the header pause disc mirrors the chip's pause state
     *  (⏸ while running, ▶ while paused). Called from the chip's
     *  bind onToggle — the one path every pause change takes. */
    private fun refreshPauseDisc() {
        val disc = pauseDisc ?: return
        val paused = WorkoutTimer.isPaused(this, sessionId)
        disc.text = if (paused) "▶" else "⏸"
        disc.setTextColor(if (paused) Theme.primary else Theme.primaryDark)
        // v3.18: the disc's layout size is baked in at creation (54dp
        // simple / 38dp full, matching render()); the glyph size is
        // re-asserted here from the same SimpleMode read so creation
        // and every pause toggle can never disagree.
        disc.textSize = if (SimpleMode.isOn(this)) 23f else 15f
    }

    /** v3.12: the timer bar's swipe-right lands here — confirm, then
     *  finish from the card screen (previously only the hub could
     *  finish a workout). */
    private fun confirmFinishWorkout() {
        AlertDialog.Builder(this).setTitle("Finish workout?")
            .setMessage("End this workout now? Everything you've logged is saved.")
            .setNegativeButton("Keep going", null)
            .setPositiveButton("Finish") { _, _ -> doFinishWorkout() }
            .show()
    }

    /** v3.12: the standard finish path (identical semantics to the
     *  hub's doFinish): any active rest is cancelled WITHOUT firing
     *  its onDone — a mid-flight rest must never advance a session
     *  that is ending — then timer finalize, mark completed, Drive
     *  backup + Health Connect sync, and the celebrating Summary. */
    private fun doFinishWorkout() {
        RestTimer.cancelActive()
        val fresh = db.getSession(sessionId)
        if (fresh != null) WorkoutTimer.finish(this, db, fresh)
        db.setSessionCompleted(sessionId, true)
        DriveBackup.backupSilently(this, db)
        HealthSync.syncSessionIfEnabled(this, db, sessionId)
        startActivity(Intent(this, SummaryActivity::class.java).apply { putExtra("sessionId", sessionId); putExtra("celebrate", true) })
        finish()
    }

    /** The first section AFTER the current one (Warm-Up → Workout →
     *  Cool-Down) that still has pending exercises, or null. Drives the
     *  section interstitial; Cool-Down (or a fully-handled session)
     *  returns null and lands on the plain completion state instead. */
    private fun nextSectionWithPending(): String? {
        val order = listOf("Warmup", "Main", "Stretch")
        val all = db.getSessionExercises(sessionId)
        val from = order.indexOfFirst { it.equals(sectionType, true) }
        for (t in order.subList((from + 1).coerceIn(0, order.size), order.size)) {
            if (t.equals(sectionType, true)) continue
            if (all.any { it.type.equals(t, true) && db.effectiveStatus(it) == "pending" }) return t
        }
        return null
    }

    // ---- v3.9.2: section interstitial (replaces the v3.7 transition card) ----

    private val interstitialHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var interstitialStep: Runnable? = null
    private var interstitialTarget: String? = null
    private var interstitialCount = 0

    /** Kill any pending countdown advance. Called at the top of
     *  render() (any render supersedes the interstitial;
     *  renderInterstitial re-arms its own countdown), from
     *  onPause/onDestroy, and by the advance itself before it
     *  fires — so the interstitial can never double-advance or
     *  fire into a dead screen. */
    private fun cancelInterstitial() {
        interstitialStep?.let { interstitialHandler.removeCallbacks(it) }
        interstitialStep = null
        interstitialTarget = null
    }

    /** v3.9.2 (Mahesh): finishing a section no longer parks the user
     *  on a transition card with buttons. Instead a full-page
     *  message — the next section's icon + "Starting <Section>" —
     *  counts down 3-2-1 in the Movie deck's countdown language
     *  (170dp primary circle, 96sp number with the pop, 800ms per
     *  number, ~2.4s total) and then advances on its own to the
     *  next section's first pending card. A tap anywhere skips the
     *  wait. No buttons anywhere on the page; the old card's counts,
     *  UP-NEXT card, Start / See All Exercises and Undo are gone
     *  with it (Undo still lives on the completion state, and the
     *  hub stays one tap away on every card's chip). The end of
     *  Cool-Down is untouched — render()'s completion state.
     *
     *  Rest composition: no rest is ever SCHEDULED across a section
     *  boundary — advanceWithRest shows the Rest Ring only when the
     *  SAME section still has pending work (pendingLeft == 0 goes
     *  straight to render()), so the interstitial neither starts
     *  nor cancels one, and there is no pending advance callback to
     *  lose. An earlier rest still counting in the engine while the
     *  interstitial is up (ring hidden, then a strip jump finished
     *  the section) expires into advanceToPending, whose v3.8.2
     *  stale-target guard just calls render() — which cancels and
     *  rebuilds the interstitial — and once the next section's
     *  first card renders, its top-bar REST chip binds to the
     *  still-running engine exactly as before. */
    private fun renderInterstitial(nextType: String) {
        cancelInterstitial()
        interstitialTarget = nextType
        interstitialCount = 3
        drawInterstitial(nextType)
        scheduleInterstitialStep(nextType)
    }

    private fun drawInterstitial(nextType: String) {
        val root = fitRoot(); setContentView(root)
        // Tap anywhere = skip the wait (the page has no buttons).
        root.isClickable = true
        root.setOnClickListener { advanceFromInterstitial(nextType) }
        val toMain = nextType.equals("Main", true)
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(makeText(if (toMain) "\uD83C\uDFCB\uFE0F" else "\uD83E\uDDD8", 64f, false).apply { gravity = Gravity.CENTER })
        root.addView(makeText(if (toMain) "Starting Workout" else "Starting Cool Down", 28f, true, Theme.textPrimary).apply {
            gravity = Gravity.CENTER; (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(10), 0, 0)
        })
        // The countdown circle — the Movie deck's, verbatim in look:
        // 170dp primary oval, 96sp white number, Overshoot pop.
        val circleWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(170), dp(170)).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, dp(26), 0, 0) }
        }
        circleWrap.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Theme.primary) }
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        })
        val num = makeText(interstitialCount.toString(), 96f, true, Color.WHITE).apply {
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        circleWrap.addView(num)
        root.addView(circleWrap)
        num.scaleX = 0.7f; num.scaleY = 0.7f
        num.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(1.4f)).start()
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
    }

    private fun scheduleInterstitialStep(nextType: String) {
        val step = Runnable {
            if (interstitialTarget != nextType) return@Runnable // superseded / cancelled
            interstitialCount--
            if (interstitialCount <= 0) advanceFromInterstitial(nextType)
            else { drawInterstitial(nextType); scheduleInterstitialStep(nextType) }
        }
        interstitialStep = step
        interstitialHandler.postDelayed(step, 800)
    }

    /** The one way out of the interstitial — the countdown reaching
     *  zero, or a tap. Lands on the next section's first pending
     *  card: exactly where the old transition card's Start button
     *  landed (first pending of the section, entry animation on). */
    private fun advanceFromInterstitial(nextType: String) {
        if (interstitialTarget != nextType) return
        cancelInterstitial()
        sectionType = nextType
        reloadDeck()
        moveToPending()
        animateEntryOnNextRender = true
        render()
    }

    /** Strip jump: same section switches the deck in place; another
     *  section reopens the card screen there. Logged sets are DB rows,
     *  so switching mid-exercise loses nothing. */
    private fun jumpToExercise(target: SessionExercise) {
        if (target.type.equals(sectionType, true)) {
            val pos = deck.indexOfFirst { it.id == target.id }
            if (pos >= 0) { index = pos; animateEntryOnNextRender = true; render() }
        } else {
            startActivity(Intent(this, CardSessionActivity::class.java).apply {
                putExtra("sessionId", sessionId); putExtra("sectionType", target.type); putExtra("exerciseId", target.id)
            })
            finish()
        }
    }

    /** v3.13 scroller strip (r4 Screen 4): every session exercise in
     *  order as a tappable 34dp CIRCLE — photo when the exercise has
     *  one, body map otherwise — plus the \u2630 hub circle at the row's
     *  end. Non-current tiles sit at half alpha, the current tile gets
     *  an oval primary ring, and handled exercises keep the existing
     *  15dp status dot (\u2713/\u2715) for state legibility — a deliberate
     *  micro-deviation from the mock, which has no dot. The exercise
     *  NAME no longer rides under the tile. One of only two scrolling
     *  surfaces in the app (the hub list is the other). */
    private fun buildStrip(currentEx: SessionExercise): LinearLayout {
        val all = db.getSessionExercises(sessionId)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(2), 0, 0) }
        }
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@CardSessionActivity, LinearLayoutManager.HORIZONTAL, false)
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
            itemAnimator = null
            clipToPadding = false
            setPadding(dp(2), 0, dp(2), 0)
        }
        rv.adapter = StripAdapter(all, currentEx.id)
        val pos = all.indexOfFirst { it.id == currentEx.id }
        if (pos >= 0) rv.post {
            (rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(pos, dp(12))
        }
        stripRv = rv
        row.addView(rv)
        val hubBtn = bendSoftCircle("\u2630", 34, 15f)
        hubBtn.isClickable = true
        hubBtn.setOnClickListener { navigateToHub() }
        (hubBtn.layoutParams as LinearLayout.LayoutParams).setMargins(dp(8), 0, 0, 0)
        row.addView(hubBtn)
        // v3.17: the Finish pill lives at the strip's end, beside the
        // ☰ list circle; it runs the card's existing confirm-finish
        // flow — the same one the timer-bar swipe-right runs.
        if (db.getSession(sessionId)?.completed != true) {
            val fin = TextView(this).apply {
                text = "⏹ Finish"; textSize = 13.5f; setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(Theme.primary); cornerRadius = dp(999).toFloat() }
                setPadding(dp(16), dp(9), dp(16), dp(9))
                isClickable = true
                setOnClickListener { confirmFinishWorkout() }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(8), 0, 0, 0) }
            }
            row.addView(fin)
        }
        return row
    }

    private inner class StripHolder(val itemRoot: LinearLayout) : RecyclerView.ViewHolder(itemRoot) {
        lateinit var tile: FrameLayout
        lateinit var dot: TextView
    }

    private inner class StripAdapter(val items: List<SessionExercise>, val currentId: Long) : RecyclerView.Adapter<StripHolder>() {
        override fun getItemCount(): Int = items.size
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): StripHolder {
            val item = LinearLayout(this@CardSessionActivity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.WRAP_CONTENT, RecyclerView.LayoutParams.MATCH_PARENT)
                setPadding(dp(3), 0, dp(3), 0)
            }
            val holder = StripHolder(item)
            holder.tile = bendCircleFrame(34)
            holder.dot = TextView(this@CardSessionActivity).apply {
                textSize = 8.5f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                layoutParams = FrameLayout.LayoutParams(dp(15), dp(15), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(2), dp(2), 0) }
            }
            item.addView(holder.tile)
            // The dot is added to the tile at bind time (only handled
            // exercises get one).
            return holder
        }
        override fun onBindViewHolder(holder: StripHolder, position: Int) {
            val ex = items[position]
            val isCurrent = ex.id == currentId
            holder.tile.removeAllViews()
            val asset = PhotoLib.assetForSessionExercise(this@CardSessionActivity, db, ex)
            val bmp = asset?.let { PhotoLib.bitmap(this@CardSessionActivity, it) }
            if (bmp != null) {
                holder.tile.addView(android.widget.ImageView(this@CardSessionActivity).apply {
                    setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                })
            } else {
                holder.tile.addView(BodyMapView(this@CardSessionActivity, DbHelper.parseMuscles(ex.targetMuscles, ex.name), true).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(20), dp(26), Gravity.CENTER)
                })
            }
            when (db.effectiveStatus(ex)) {
                "done" -> {
                    holder.dot.text = "\u2713"
                    holder.dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Theme.success) }
                    holder.tile.addView(holder.dot)
                }
                "skipped" -> {
                    holder.dot.text = "\u2715"
                    holder.dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#94A3B8")) }
                    holder.tile.addView(holder.dot)
                }
            }
            // Non-current tiles fade back; the current tile carries an
            // oval primary ring (drawn over the tile).
            holder.tile.alpha = if (isCurrent) 1f else 0.5f
            holder.tile.foreground = if (isCurrent) GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), Theme.primary)
            } else null
            holder.itemRoot.setOnClickListener { jumpToExercise(ex) }
        }
    }

    // ---- Drag feedback + card animations (v2.2) ----

    /** Faded direction label + card tint/border grow with drag progress toward the commit threshold. */
    private fun updateDragFeedback(v: View, dx: Float, dy: Float, tx: Float, ty: Float) {
        val px = if (tx > 0) abs(dx) / tx else 0f
        val py = if (ty > 0) abs(dy) / ty else 0f
        val horizontal = px >= py
        val progress = (if (horizontal) px else py).coerceIn(0f, 1f)
        val dirColor = when {
            !horizontal && dy < 0 -> Color.parseColor("#2563EB")
            !horizontal -> Color.parseColor("#7C3AED")
            dx >= 0 -> Color.parseColor("#15803D")
            else -> Color.parseColor("#DC2626")
        }
        doneLabel?.alpha = if (horizontal && dx > 0) progress else 0f
        skipLabel?.alpha = if (horizontal && dx < 0) progress else 0f
        upLabel?.alpha = if (!horizontal && dy < 0) progress else 0f
        downLabel?.alpha = if (!horizontal && dy > 0) progress else 0f
        val active = when { horizontal && dx > 0 -> doneLabel; horizontal && dx < 0 -> skipLabel; !horizontal && dy < 0 -> upLabel; !horizontal && dy > 0 -> downLabel; else -> null }
        active?.let { val s = 0.92f + 0.13f * progress; it.scaleX = s; it.scaleY = s }
        // v3.3: the on-photo cue pills mirror the same progress — the cue
        // for the committed direction goes fully opaque and scales up to
        // ~1.15x; the other two fade back. (Up = Log set has no cue: it
        // has the button, so all three simply fade.)
        updateSwipeCues(when {
            horizontal && dx > 0 -> cueDone
            horizontal && dx < 0 -> cueSkip
            !horizontal && dy > 0 -> cueLater
            else -> null
        }, progress)
        // Border strengthens toward the direction color; tint washes the card face.
        cardSurface?.background = deckCardDrawable(if (progress > 0.02f) dirColor else null)
        tintOverlay?.let {
            (it.background as? GradientDrawable)?.setColor(dirColor)
            it.alpha = 0.13f * progress
        }
        // Peek card behind swells slightly as this card commits to leaving.
        peekNear?.let { val s = 0.96f + 0.03f * progress; it.scaleX = s; it.scaleY = s; it.translationY = dp(12).toFloat() - dp(5) * progress; it.alpha = 0.7f + 0.3f * progress }
        if (progress >= 1f && !thresholdHapticDone) {
            thresholdHapticDone = true
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    /** v3.3: drive the photo-band cue pills from drag progress (see
     *  updateDragFeedback). [active] is the cue for the dominant direction,
     *  or null when dragging up (Log set — button-only, no cue). */
    private fun updateSwipeCues(active: TextView?, progress: Float) {
        for (cue in listOf(cueSkip, cueDone, cueLater)) {
            cue ?: continue
            if (cue === active) {
                cue.alpha = cueRestAlpha + (1f - cueRestAlpha) * progress
                val s = 1f + 0.15f * progress; cue.scaleX = s; cue.scaleY = s
            } else {
                cue.alpha = cueRestAlpha * (1f - 0.72f * progress)
                cue.scaleX = 1f; cue.scaleY = 1f
            }
        }
    }

    /** v3.3: cue pills settle back to their rest state (translucent, 1x). */
    private fun resetSwipeCues() {
        for (cue in listOf(cueSkip, cueDone, cueLater)) {
            cue?.animate()?.alpha(cueRestAlpha)?.scaleX(1f)?.scaleY(1f)?.setDuration(140)?.start()
        }
    }

    /** Below-threshold release: gentle spring back to rest, labels/tint fade away. */
    private fun snapCardBack(v: View) {
        v.animate().translationX(0f).translationY(0f).rotation(0f)
            .setDuration(220).setInterpolator(OvershootInterpolator(0.8f)).start()
        fadeDragFeedback()
    }

    private fun fadeDragFeedback() {
        doneLabel?.animate()?.alpha(0f)?.setDuration(140)?.start()
        skipLabel?.animate()?.alpha(0f)?.setDuration(140)?.start()
        upLabel?.animate()?.alpha(0f)?.setDuration(140)?.start()
        downLabel?.animate()?.alpha(0f)?.setDuration(140)?.start()
        tintOverlay?.animate()?.alpha(0f)?.setDuration(140)?.start()
        resetSwipeCues()
        peekNear?.animate()?.scaleX(0.96f)?.scaleY(0.96f)?.translationY(dp(12).toFloat())?.alpha(0.7f)?.setDuration(180)?.start()
        cardSurface?.background = deckCardDrawable()
    }

    /** Commit exit: card flies off in its direction, THEN the action is applied. */
    private fun animateExitThen(v: View, dir: Int, after: () -> Unit) {
        if (cardAnimating) return
        cardAnimating = true
        val targetX = dir * (v.width + dp(80)).toFloat()
        v.animate().translationX(targetX).rotation(dir * 8f).alpha(0f)
            .setDuration(260).setInterpolator(AccelerateInterpolator(1.1f))
            .withEndAction {
                cardAnimating = false
                animateEntryOnNextRender = true
                after()
            }.start()
        peekNear?.animate()?.scaleX(1f)?.scaleY(1f)?.translationY(0f)?.alpha(1f)?.setDuration(240)?.start()
    }

    /** Up-swipe treatment: the card does NOT exit (the log dialog is the focus).
     *  It lifts ~48dp, fades slightly, the dialog opens, and the card settles back. */
    private fun animateLogSetLift(v: View, after: () -> Unit) {
        if (cardAnimating) return
        cardAnimating = true
        fadeDragFeedback()
        v.animate().translationY(-dp(48).toFloat()).translationX(0f).rotation(0f).scaleX(0.98f).scaleY(0.98f).alpha(0.85f)
            .setDuration(180).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                cardAnimating = false
                after()
                v.animate().translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(200).setInterpolator(DecelerateInterpolator()).start()
            }.start()
    }

    /** Down-swipe / Later-button treatment (v3.1): the card sinks and
     *  fades, THEN Do later is applied and the next card enters. */
    private fun animateLaterDrop(v: View, after: () -> Unit) {
        if (cardAnimating) return
        cardAnimating = true
        fadeDragFeedback()
        v.animate().translationY(v.height * 0.35f).translationX(0f).rotation(0f).alpha(0f)
            .setDuration(240).setInterpolator(AccelerateInterpolator(1.1f))
            .withEndAction {
                cardAnimating = false
                animateEntryOnNextRender = true
                after()
            }.start()
    }

    // ---- Actions (applied after the card animation, or directly from dialogs) ----

    private fun applySkipped(){
        val ex=current() ?: return; snapshot(ex)
        db.setSessionExerciseStatus(ex.id,"skipped")
        Toast.makeText(this,"Skipped ${ex.name}",Toast.LENGTH_SHORT).show()
        // v3.3 (item 1): a skip NEVER rests — straight to the next card.
        advanceAfterSkip()
    }
    /** v3.3 (item 2): the Done gate for the right swipe. Done completes
     *  instantly UNLESS this is a Main exercise with sets still unlogged —
     *  then the card snaps back and a confirmation dialog decides. */
    private fun requestDone(v: View){
        val ex=current() ?: return
        if (!doneConfirmNeeded(ex)) { animateExitThen(v, 1) { applyDone() }; return }
        snapCardBack(v)
        showDoneConfirm(ex) { animateExitThen(v, 1) { applyDone() } }
    }
    /** True only for Main-section exercises with logged sets < planned
     *  sets. Warm-up and Cool-down/Stretch items are EXEMPT by the user's
     *  explicit scoping — their Done never asks. Zero logged sets still
     *  counts (0 < planned), so it asks too. */
    private fun doneConfirmNeeded(ex: SessionExercise): Boolean {
        if (!ex.type.equals("Main", true)) return false
        val sets = db.getSets(ex.id)
        return sets.count { it.isDone } < sets.size
    }
    /** "Mark done?" — Mist-styled confirmation with the per-set picture:
     *  logged sets show their weight×reps, the rest read "not logged".
     *  Keep logging dismisses and changes nothing; Mark done proceeds
     *  with the normal completion (applyDone + the usual advance/rest). */
    private fun showDoneConfirm(ex: SessionExercise, onConfirm: () -> Unit){
        val sets = db.getSets(ex.id)
        val logged = sets.count { it.isDone }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        box.addView(makeText("You've logged $logged of ${sets.size} sets for ${ex.name}.", 14f, false, Theme.textPrimary).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, dp(4)) })
        for (s in sets) {
            val line = if (s.isDone) makeText("Set ${s.setNumber} · ${setText(s)} ✓", 12.5f, true, Theme.success)
                       else makeText("Set ${s.setNumber} · not logged", 12.5f, false, Theme.textTertiary)
            (line.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(1), 0, dp(1))
            box.addView(line)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Mark done?").setView(box)
            .setNegativeButton("Keep logging", null)
            .setPositiveButton("Mark done") { _, _ -> onConfirm() }
            .create()
        dialog.setOnShowListener {
            // Mist palette on the system buttons, same treatment as the Rest Ring.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply { setTextColor(Theme.primary); textSize = 15f }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply { setTextColor(Theme.textSecondary); textSize = 15f }
        }
        dialog.show()
    }
    private fun applyDone(){
        val ex=current() ?: return; snapshot(ex)
        for(s in db.getSets(ex.id)){ if(!s.isDone) db.updateSet(s.copy(isDone=true)) }
        db.setSessionExerciseStatus(ex.id,"done")
        // v3.1: no effort/feedback prompt — finishing never interrupts.
        advanceWithRest()
    }
    /** v3 "Do later": current exercise moves to the end of this section's
     *  remaining list; the next exercise slides in. No status change. */
    private fun doLater(){
        val ex=current() ?: return
        db.moveSessionExerciseToEndOfSection(ex.id)
        reloadDeck()
        // Land on the next pending card at/after the old position.
        val nextIdx = deck.indices.firstOrNull { it >= index && db.effectiveStatus(deck[it]) == "pending" }
        if (nextIdx != null) index = nextIdx else moveToPending()
        animateEntryOnNextRender = true
        Toast.makeText(this,"Moved ${ex.name} to later",Toast.LENGTH_SHORT).show()
        render()
    }
    /** v3.3 (item 1): skip advance — the same index walk as advanceWithRest
     *  but with NO Rest Ring and no rest prompt, ever. This is the only
     *  advance applySkipped() calls; the rest code below is unreachable
     *  from every skip path. */
    private fun advanceAfterSkip(){
        index++; moveToPendingFrom(index)
        render()
    }
    /** Navigation after REAL work (Done, or logging the last set of an
     *  exercise). v3.7 sent the user back to the hub here; v3.8.2
     *  restores the direct card flow Mahesh asked for: finishing one
     *  workout takes you to the NEXT workout's card. If this Done
     *  completed the section, render() lands on the section
     *  interstitial (v3.9.2; the completion state after Cool-Down)
     *  exactly as before; otherwise, in the
     *  Workout section the between-exercises Rest Ring runs first
     *  (default 60s, Settings-adjustable, next exercise as the target
     *  — S2/S5) and its GO / Skip-rest fires the advance ONCE;
     *  Warm-Up / Cool-Down dones advance directly (they never rested).
     *  The top-bar REST chip is engine-driven, so it keeps ticking on
     *  whatever card is showing while a hidden rest runs out. Never
     *  called from a skip path. */
    private fun advanceWithRest(){
        reloadDeck()
        val pendingLeft = deck.count { db.effectiveStatus(it) == "pending" }
        if (pendingLeft == 0) { index = deck.size; render(); return }
        val nextEx = deck.firstOrNull { db.effectiveStatus(it) == "pending" } ?: run { moveToPending(); render(); return }
        val doneId = current()?.id ?: -1L
        if (sectionType == "Main") {
            val cues = nextEx.cues.ifBlank { DbHelper.cuesForName(nextEx.name, nextEx.equipment, nextEx.type) }
                .split(";").map { it.trim() }.filter { it.isNotEmpty() }
            // v3.12: the rest carries its persistence identity so
            // attach() can rebuild this exact advance after rotation
            // or process death (kind/target/from mirror the lambda).
            RestTimer.show(this, WeekManager.restExerciseSec(this), "Next · ${nextEx.name}", cues,
                sessionId = sessionId, kind = "exercise", targetId = nextEx.id, fromId = doneId,
                onDone = { advanceToPending(nextEx.id, doneId) })
            return
        }
        advanceToPending(nextEx.id, doneId)
    }
    /** v3.8.2: land on the next pending exercise's card. [fromExId] is
     *  the exercise whose completion scheduled this advance: when the
     *  Rest Ring's callback fires after the ring was hidden, the user
     *  may already have navigated elsewhere (a scroller-strip jump) —
     *  then don't yank them back; just refresh the card they're on.
     *  A dead window likewise swallows the advance. */
    private fun advanceToPending(targetId: Long, fromExId: Long){
        if (isFinishing || isDestroyed) return
        reloadDeck()
        if (fromExId > 0) {
            val cur = current()
            if (cur == null || cur.id != fromExId) { render(); return }
        }
        val pos = deck.indexOfFirst { it.id == targetId }
        if (pos >= 0) { index = pos; animateEntryOnNextRender = true } else moveToPending()
        render()
    }
    /** S2: after a set is logged with more sets to go, the Rest Ring runs the
     *  between-sets duration (default 30s) and shows the next set's last-time
     *  target under the ring. */
    private fun setRestThen(ex: SessionExercise, after: () -> Unit) {
        if (sectionType != "Main") { after(); return }
        val sets = db.getSets(ex.id)
        val nextSet = sets.firstOrNull { !it.isDone }
        val last = db.lastPerformedForExercise(ex.name, sessionId)
        val label = if (nextSet != null) {
            val pos = sets.indexOf(nextSet)
            val ls = last?.sets?.getOrNull(pos)
            "Next · Set ${nextSet.setNumber}" + if (ls != null) " — last time ${setText(ls)}" else ""
        } else ""
        val cues = ex.cues.ifBlank { DbHelper.cuesForName(ex.name, ex.equipment, ex.type) }
            .split(";").map { it.trim() }.filter { it.isNotEmpty() }
        // v3.12: persisted like the exercise rest (kind "set"); its
        // attach()-time completion is just a re-render, exactly what
        // [after] does here.
        RestTimer.show(this, WeekManager.restSetSec(this), label, cues,
            sessionId = sessionId, kind = "set", targetId = ex.id, fromId = ex.id, onDone = after)
    }
    private fun moveToPendingFrom(from:Int){
        // index already advanced; if that card is handled, jump to first pending
        if(index !in deck.indices || db.effectiveStatus(deck[index])!="pending") moveToPending()
    }
    private fun doUndo(){
        val snap=undoStack.removeLastOrNull() ?: return
        db.setSessionExerciseStatus(snap.exId, snap.status)
        db.setSessionExerciseEffort(snap.exId, snap.effort)
        for(s in snap.sets) db.updateSet(s)
        // Reposition to that exercise
        reloadDeck(); index=deck.indexOfFirst{it.id==snap.exId}.coerceAtLeast(0)
        Toast.makeText(this,"Undone",Toast.LENGTH_SHORT).show()
    }


    /** v3.13: the inline panel's hero ✓ — write the staged panel
     *  state for [set] as logged (BW writes the empty weight string,
     *  as everywhere), sync the exercise status, then run the same
     *  branch the sheet runs: last set → exercise done +
     *  advanceWithRest; otherwise the between-sets rest, after which
     *  the card re-renders on the next set (panelKey reset forces a
     *  fresh panelPrefill for it). */
    private fun logCardPanelSet(ex: SessionExercise, set: SessionSet) {
        val st = panelState ?: return
        snapshot(ex)
        db.updateSet(set.copy(weight = if (st.isBw) "" else SetLogger.fmtWeight(st.weight), reps = st.reps.toString(), isBodyweight = st.isBw, isDone = true))
        db.syncExerciseStatus(ex.id)
        panelKey = ""
        val remaining = db.getSets(ex.id).count { !it.isDone }
        when {
            remaining == 0 -> { db.setSessionExerciseStatus(ex.id, "done"); advanceWithRest() }
            else -> setRestThen(ex) { render() }
        }
    }

    /** v3.9: the set logger is the shared [SetLogger] sheet now (mock
     *  frame 6) — this wrapper keeps the old dialog's contract 1:1.
     *  The old dialog opened only when an unlogged set remained (else
     *  the card's Done path runs), snapshotted for undo before any
     *  write, and after a write: all sets logged -> mark the exercise
     *  done + advanceWithRest (the v3.7/v3.8.2 advance machinery,
     *  Rest Ring included); some newly logged -> the 30s set rest via
     *  setRestThen; nothing newly logged -> just re-render (the old
     *  Cancel, plus value-only edits the old dialog couldn't make). */
    private fun logSetDialog(){
        val ex=current() ?: return
        val sets=db.getSets(ex.id)
        if(sets.none{!it.isDone}){ applyDone(); return }
        SetLogger.show(this, db, sessionId, ex, onSnapshot = { snapshot(ex) }) { res ->
            when {
                res.remaining == 0 -> { db.setSessionExerciseStatus(ex.id, "done"); advanceWithRest() }
                res.newlyLogged > 0 -> setRestThen(ex) { render() }
                else -> render()
            }
        }
    }
}
