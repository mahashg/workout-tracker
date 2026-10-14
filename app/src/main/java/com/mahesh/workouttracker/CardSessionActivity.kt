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

    override fun onPause() { super.onPause(); timerChip?.stopTicking(); timerChip?.foldNow(); photoLoopers.forEach { it.stop() } }
    override fun onResume() {
        super.onResume(); photoLoopers.forEach { it.start() }
        // v3.7: returning to the card (e.g. back from the form video)
        // re-renders so the scroller strip and counts reflect any
        // status changes. The first resume is onCreate's own render.
        if (firstResume) firstResume = false else if (::db.isInitialized && sessionId > 0) render()
    }
    override fun onDestroy() { super.onDestroy(); timerChip?.stopTicking(); photoLoopers.forEach { it.stop() } }

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
        // Views from the previous render are discarded below; stop their
        // photo crossfade loops first (v3.1).
        photoLoopers.forEach { it.stop() }; photoLoopers.clear()
        reloadDeck()
        if(index !in deck.indices){
            // v3.7: the deck is exhausted. If a LATER section still has
            // pending exercises, the mock's transition card takes over;
            // otherwise the plain completion state (fixed, no scrolling).
            val nextType = nextSectionWithPending()
            if (nextType != null) { renderTransition(nextType); return }
            val root=fitRoot(); setContentView(root)
            root.addView(topBar(sectionLabel(sectionType), "Section complete"))
            root.addView(makeText("${sectionLabel(sectionType)} complete!",26f,true,Color.parseColor("#15803D")))
            val done=deck.count{db.effectiveStatus(it)=="done"}; val skipped=deck.count{db.effectiveStatus(it)=="skipped"}
            root.addView(makeText("$done done • $skipped skipped",15f,false))
            if(undoStack.isNotEmpty()) root.addView(makeButton("Undo last action"){doUndo(); render()}.apply { (layoutParams as LinearLayout.LayoutParams).apply { height = dp(48); setMargins(0, dp(4), 0, 0) } })
            // v3.7: the sections screen left the flow — this goes to the hub.
            root.addView(makeButton("All Exercises"){navigateToHub()}.apply { (layoutParams as LinearLayout.LayoutParams).apply { height = dp(48); setMargins(0, dp(4), 0, 0) } })
            Toast.makeText(this,"${sectionLabel(sectionType)} complete!",Toast.LENGTH_SHORT).show()
            return
        }
        val ex=current()!!
        val sets=db.getSets(ex.id)
        val doneSets=sets.count{it.isDone}
        val root=fitRoot(); setContentView(root)
        root.addView(topBar(sectionLabel(sectionType), "Card ${index+1} of ${deck.size}"))
        // Top bar color-coded + workout timer chip. Explicit 96dp budget (v2.5.1):
        // progress line + bar + 30dp timer chip; deck (weight 1) absorbs the rest.
        val top=cardLayout(when(sectionType){"Warmup"->"#D97706";"Stretch"->"#0D9488";else->"#2563EB"})
        top.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(96)).apply { setMargins(0, 0, 0, 0) }
        top.setPadding(dp(12), dp(6), dp(12), dp(6))
        top.addView(makeText("${sectionLabel(sectionType)}  •  Card ${index+1} of ${deck.size}  •  ${deck.count{db.effectiveStatus(it)=="done"}} done • ${deck.count{db.effectiveStatus(it)=="skipped"}} skipped",14f,true).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        // v2.6.1: same position-based fill as Preview — (index+1)/total, never
        // an empty bar on card 1 (handled count can only raise it further).
        val handledCount = deck.count { db.effectiveStatus(it) != "pending" }
        top.addView(hProgress(deck.size, maxOf(handledCount, (index + 1).coerceAtMost(deck.size)), sectionColor(sectionType)).apply { (layoutParams as LinearLayout.LayoutParams).apply { height = dp(10); setMargins(0, dp(4), 0, 0) } })
        val sessForTimer = db.getSession(sessionId)
        if (sessForTimer != null && !sessForTimer.completed) {
            db.ensureSessionStarted(sessionId)
            // v3.7: the REST chip sits next to the elapsed timer while a
            // rest is active (RestTimer engine state), tap reopens the ring.
            val chipRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(30)).apply { setMargins(0, dp(4), 0, 0) }
            }
            val chip = TimerChipView(this)
            chip.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30))
            chip.bind(this, db, db.getSession(sessionId) ?: sessForTimer) {}
            chip.startTicking()
            timerChip = chip
            chipRow.addView(chip)
            val rc = RestChipView(this)
            rc.bind(this)
            rc.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(8), 0, 0, 0) }
            restChip = rc
            chipRow.addView(rc)
            top.addView(chipRow)
        }
        root.addView(top)

        // ---- Card deck (v2.2): ONE moving card over two static peek cards ----
        val deckBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(0, dp(8), 0, dp(6)) }
            clipChildren = false; clipToPadding = false
        }
        // Peek cards behind (far = smaller/lighter, near = closer), purely decorative.
        peekFar = View(this).apply {
            background = deckCardDrawable()
            alpha = 0.45f; scaleX = 0.92f; scaleY = 0.92f; translationY = dp(22).toFloat()
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        peekNear = View(this).apply {
            background = deckCardDrawable()
            alpha = 0.7f; scaleX = 0.96f; scaleY = 0.96f; translationY = dp(12).toFloat()
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        deckBox.addView(peekFar); deckBox.addView(peekNear)

        // The moving card: wrapper carries card surface + direction tint + faded labels,
        // so shadow, border, band and content all travel together.
        val wrap = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            clipChildren = false; clipToPadding = false
        }
        val dragCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // v2.6.1: top padding >= 14dp so the exercise name never touches the band.
            setPadding(dp(12), dp(14), dp(12), dp(12))
            background = deckCardDrawable()
            elevation = dp(8).toFloat()
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        cardSurface = dragCard
        dragCardBand(dragCard)
        val muscles=DbHelper.parseMuscles(ex.targetMuscles, ex.name)
        // v3: last performance PROMINENT at the top of the card (user ask) —
        // in v3.1 it lives in the header overlay below.
        val last=db.lastPerformedForExercise(ex.name, sessionId)
        val lastLine = if (last != null) lastTimeTitle(last) else "First time — no previous record. Start light."
        // v3.2 (approved mock): the photo band is aspect-matched to the
        // bundled photos (3:2) — height = content width x 2/3 — so the
        // ENTIRE frame is visible: CENTER_CROP in an aspect-matched box
        // crops nothing and stretches nothing. Both PhotoLooper frames
        // share the identical container, so the crossfade never shifts.
        // Tags (body part + equipment) sit top-left over the photo; the
        // name + last-time line ride the bottom gradient scrim. No photo
        // -> the body map gets the same band on the mist surface.
        val photoAsset = PhotoLib.assetForSessionExercise(this, db, ex)
        val photoBmp = photoAsset?.let { PhotoLib.bitmap(this, it) }
        val screenWDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        val rootPadH = if (isCompactScreen()) 28 else 40 // fitRoot horizontal padding, both sides
        val photoH = ((screenWDp - rootPadH - 24) * 2 / 3).coerceAtLeast(120) // minus dragCard padding 12+12
        val header = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(photoH)).apply { setMargins(0, 0, 0, dp(6)) }
            roundedClip(16)
        }
        if (photoBmp != null) {
            header.setBackgroundColor(Theme.surfaceVariant)
            header.addView(android.widget.ImageView(this).apply {
                setImageBitmap(photoBmp); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            })
            val photo2 = photoAsset?.let { PhotoLib.secondBitmap(this, it) }
            if (photo2 != null) {
                val iv2 = android.widget.ImageView(this).apply {
                    setImageBitmap(photo2); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; alpha = 0f
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                }
                header.addView(iv2)
                val looper = PhotoLooper(iv2); photoLoopers.add(looper); looper.start()
            }
            // v3.3: scrim is taller (112dp) and the name block is raised
            // 32dp so the bottom swipe cue (item 4) never overlaps it.
            header.addView(View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(112), Gravity.BOTTOM)
            })
            val overlay = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(12), dp(8))
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(0, 0, 0, dp(32)) }
            }
            overlay.addView(sectionPill())
            overlay.addView(makeText(ex.name, 20f, true, Color.WHITE).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0) })
            overlay.addView(makeText(lastLine, 12.5f, true, Color.WHITE).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0) })
            header.addView(overlay)
        } else {
            header.background = roundedBg("#EDF2F7", 16)
            val mapH = photoH * 3 / 4
            header.addView(BodyMapView(this, muscles, true).apply {
                layoutParams = FrameLayout.LayoutParams(dp(mapH * 76 / 96), dp(mapH), Gravity.CENTER_VERTICAL or Gravity.END).apply { setMargins(0, 0, dp(14), 0) }
            })
            val overlay = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(12), dp(8))
                // v3.3: raised 32dp so the bottom swipe cue never overlaps it.
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(0, 0, 0, dp(32)) }
            }
            overlay.addView(sectionPill())
            overlay.addView(makeText(ex.name, 20f, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0) })
            overlay.addView(makeText(lastLine, 12.5f, true, Theme.primary).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0) })
            header.addView(overlay)
        }
        // v3.7 (approved mock screen 3): the header's top row is now the
        // "☰ All exercises" chip (top-left, back to the hub) and the
        // form-video as a 38dp icon (top-right) — both dark translucent
        // pills (rgba(15,23,42,.55)), white ink. They replace the v3.2
        // part/equipment tags (body part still shows as the hub row tag
        // and in the section pill below; equipment rides the essentials
        // "Do it" context and the list view). Both are clickable
        // children, so they consume their own taps and never start a
        // card drag — same contract as the old video button.
        run {
            val darkPill = 0x8C0F172A.toInt() // rgba(15,23,42,.55)
            val allChip = TextView(this).apply {
                text = "☰ All exercises"; textSize = 12.5f; setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                background = GradientDrawable().apply { setColor(darkPill); cornerRadius = dp(999).toFloat() }
                setPadding(dp(11), dp(6), dp(11), dp(6)); maxLines = 1
                isClickable = true
                setOnClickListener { navigateToHub() }
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(dp(10), dp(10), 0, 0) }
            }
            header.addView(allChip)
            if (ex.youtubeUrl.isNotBlank()) {
                val vicon = TextView(this).apply {
                    text = "▶"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(darkPill) }
                    isClickable = true
                    setOnClickListener { openUrl(this@CardSessionActivity, ex.youtubeUrl) }
                    layoutParams = FrameLayout.LayoutParams(dp(38), dp(38), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(10), dp(10), 0) }
                }
                header.addView(vicon)
            }
        }
        // v3.3 (item 4): persistent swipe cues ON the photo band — the same
        // band body-map exercises use, so every card type gets them. Left =
        // Skip, right = Done (solid success green), bottom = Later. They are
        // click-through decoration: the card's drag handling lives on the
        // wrap, and non-clickable children never consume its touches. Live
        // session only — PreviewCardsActivity has its own header builder
        // and deliberately shows no cues (browse-only, gestures don't log).
        run {
            fun swipeCue(text: String, bgColor: Int, gravity: Int, mL: Int, mR: Int, mB: Int): TextView {
                return TextView(this).apply {
                    this.text = text; textSize = 11f; setTextColor(Color.WHITE)
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                    background = GradientDrawable().apply { setColor(bgColor); cornerRadius = dp(999).toFloat() }
                    setPadding(dp(10), dp(5), dp(10), dp(5)); maxLines = 1
                    alpha = cueRestAlpha; isClickable = false; isFocusable = false
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, gravity).apply { setMargins(dp(mL), 0, dp(mR), dp(mB)) }
                }
            }
            val cueBg = 0xCC101826.toInt() // dark translucent slate
            cueSkip = swipeCue("← ✕ Skip", cueBg, Gravity.START or Gravity.CENTER_VERTICAL, 8, 0, 0)
            cueDone = swipeCue("Done ✓ →", Theme.success, Gravity.END or Gravity.CENTER_VERTICAL, 0, 8, 0)
            cueLater = swipeCue("↓ Later", cueBg, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 0, 8)
            header.addView(cueSkip); header.addView(cueDone); header.addView(cueLater)
        }
        dragCard.addView(header)
        // Essentials under the photo band: variation, then one compact row —
        // Do-it + Sets lines on the left, set-progress dots on the right.
        // (The equipment badge moved onto the photo as a tag.)
        if(ex.variation.isNotBlank()) dragCard.addView(makeText(ex.variation,12f,false,Theme.textSecondary).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0) })
        val essRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(2), 0, 0) } }
        val essInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        essInfo.addView(makeText("Do it: ${doItLine(sets)}",14f,true,Color.parseColor("#92400E")).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0) })
        essInfo.addView(makeText("Sets: $doneSets of ${sets.size} done",12.5f,false,Theme.textSecondary).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0) })
        essRow.addView(essInfo)
        essRow.addView(makeText(sets.mapIndexed{i,s-> if(s.isDone) "●" else "○"}.joinToString("  "),18f,true,Color.parseColor("#15803D")).apply {
            (layoutParams as LinearLayout.LayoutParams).apply { width = LinearLayout.LayoutParams.WRAP_CONTENT; setMargins(dp(8), 0, 0, 0) }
        })
        dragCard.addView(essRow)

        // v2.6: no Show/Hide toggle — "Check your posture" is always visible.
        // v3.2 (approved mock): the posture check moves OUT of the photo
        // area into its own surface card directly below the essentials row:
        // flat mist fill + stroke inside the deck card. Bullet/cue caps keep
        // the fixed-height card from clipping at the ~546dp compact floor
        // (the v2.5 accepted how-to cap; full content lives in the list
        // view). Posture content itself is untouched.
        run {
            val how = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(Theme.surfaceVariant); cornerRadius = dp(16).toFloat(); setStroke(dp(1), Theme.stroke) }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), 0, 0) }
            }
            fun slim(v: TextView, topDp: Int = 1): TextView {
                (v.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(topDp), 0, dp(1))
                return v
            }
            // v3.7: no full-width video button here any more — the form
            // video is the ▶ icon at the photo's top-right (mock screen 3).
            how.addView(slim(makeText("What you'll feel: ${musclesLabel(muscles)}",12f,false,Color.parseColor("#0369A1")), 2))
            val posture = ex.postureCheck.ifBlank{ DbHelper.postureForName(ex.name, ex.type) }
            if(posture.isNotBlank()){
                val items = posture.split(";").map{it.trim()}.filter{it.isNotEmpty()}
                how.addView(slim(makeText("Check your posture:",13f,true,Color.parseColor("#15803D")), 4))
                for(c in items.take(if (isCompactScreen()) 2 else 3)) how.addView(slim(makeText("☐  $c",12f,false).apply { setLineSpacing(0f, 0.95f); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }))
            }
            val cues=ex.cues.ifBlank{DbHelper.cuesForName(ex.name,ex.equipment,ex.type)}
            if(cues.isNotBlank()){
                val items = cues.split(";").map{it.trim()}.filter{it.isNotEmpty()}
                for(c in items.take(1)) how.addView(slim(makeText("•  $c",11f,false,Theme.textSecondary).apply { setLineSpacing(0f, 0.95f); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }))
            }
            if(ex.equipment=="Machine"||ex.equipment=="Dumbbell"||ex.equipment=="Kettlebell") how.addView(slim(makeText("Start light: use the lightest weight that feels easy first.",11f,false,Color.parseColor("#92400E"))))
            dragCard.addView(how)
        }
        // v3.3: the old under-card swipe-hint line is gone — the direction
        // cues now live on the photo band itself (item 4). "Next up:" below
        // the button row stays — it's informative.

        wrap.addView(dragCard)
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
        skipLabel = dirLabel("✕ SKIP", "#DC2626", Gravity.TOP or Gravity.START, 10f)
        doneLabel = dirLabel("✓ DONE", "#15803D", Gravity.TOP or Gravity.END, -10f)
        upLabel = dirLabel("↑ LOG SET", "#2563EB", Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0f)
        downLabel = dirLabel("↓ LATER", "#7C3AED", Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0f)
        wrap.addView(skipLabel); wrap.addView(doneLabel); wrap.addView(upLabel); wrap.addView(downLabel)
        cardWrap = wrap
        deckBox.addView(wrap)
        root.addView(deckBox)

        // Drag handling on the whole card. Only reacts once movement passes touch slop,
        // so taps (e.g. Watch Form Video, which consumes its own touches as a child)
        // and light nudges never trigger a swipe action.
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        wrap.setOnTouchListener { v, ev ->
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

        // v3.7 scroller strip: directly under the card — every session
        // exercise as a tappable thumbnail. It sits OUTSIDE the swipe
        // card on purpose: inside it, the strip's own horizontal drags
        // would fight the card's swipe handling. The freed vertical
        // space also absorbs the removed "Next up:" line (the strip
        // itself now shows what is next).
        root.addView(buildStrip(ex))

        // v3.1 final card design (user-approved mock): NO Skip and NO Done
        // buttons anywhere — those two actions are swipe-only (left/right).
        // v3.3 (item 3): the Later BUTTON is gone too — Later survives as
        // the down-swipe only.
        // v3.7 (approved mock screen 3): the full-width Log set bar is
        // replaced by ONE simple compact "+ Log set" button, right-aligned
        // in the action row, with the short swipe hint at the row's left
        // (the on-photo cue pills remain as well). Up-swipe still opens
        // the same set-logger dialog.
        val wrapRef = wrap
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { setMargins(0, dp(4), 0, 0) }
        }
        btnRow.addView(makeText("← swipe Skip · Done → · ↓ Later", 12f, false, Theme.textSecondary).apply {
            (layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; setMargins(0, 0, dp(8), 0) }
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val logBtn = makeButton("+ Log set") { animateLogSetLift(wrapRef) { logSetDialog() } }
        (logBtn.layoutParams as LinearLayout.LayoutParams).apply { width = LinearLayout.LayoutParams.WRAP_CONTENT; setMargins(0, 0, 0, 0) }
        tightButton(logBtn, 48, 10, primary = true)
        logBtn.setPadding(dp(22), 0, dp(22), 0)
        btnRow.addView(logBtn)
        root.addView(btnRow)
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
     *  hub is an optional overview, not the flow's home: the ☰ chip,
     *  the transition's "See All Exercises" and the completion state's
     *  "All Exercises" come here; Start / Resume / Done never do. */
    private fun navigateToHub() {
        startActivity(Intent(this, WorkoutHubActivity::class.java).apply {
            putExtra("sessionId", sessionId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    /** The first section AFTER the current one (Warm-Up → Workout →
     *  Cool-Down) that still has pending exercises, or null. Drives the
     *  transition card; Cool-Down (or a fully-handled session) returns
     *  null and lands on the plain completion state instead. */
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

    /** v3.7 transition card (approved mock screen 2), shown when a card
     *  action resolves the LAST pending exercise of a section and the
     *  next section still has work: big green check, counts, an UP-NEXT
     *  card for the next section's first pending exercise, Start /
     *  See All Exercises, and the 3-dot section progress. */
    private fun renderTransition(nextType: String) {
        val root = fitRoot(); setContentView(root)
        val done = deck.count { db.effectiveStatus(it) == "done" }
        val skipped = deck.count { db.effectiveStatus(it) == "skipped" }
        val nextList = db.getSessionExercises(sessionId).filter { it.type.equals(nextType, true) }
        val nextPending = nextList.filter { db.effectiveStatus(it) == "pending" }
        val first = nextPending.firstOrNull()
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        val check = TextView(this).apply {
            text = "✓"; textSize = 34f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Theme.success) }
            layoutParams = LinearLayout.LayoutParams(dp(74), dp(74)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        root.addView(check)
        root.addView(makeText("${displaySectionName(sectionType)} complete", 23f, true, Theme.textPrimary).apply {
            gravity = Gravity.CENTER; (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(14), 0, 0)
        })
        val sess = db.getSession(sessionId)
        val elapsedTxt = if (sess != null) " · ${WorkoutTimer.formatDuration(WorkoutTimer.liveElapsedSec(this, sess))}" else ""
        root.addView(makeText("$done done · $skipped passed$elapsedTxt", 13.5f, false, Theme.textSecondary).apply {
            gravity = Gravity.CENTER; (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(2), 0, 0)
        })
        // UP-NEXT card (mock): kicker + first pending exercise of the
        // next section with its photo thumb and last-time line.
        if (first != null) {
            val card = cardLayout()
            (card.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(16), 0, dp(4))
            card.setPadding(dp(14), dp(12), dp(14), dp(12))
            card.addView(makeText("UP NEXT — ${displaySectionName(nextType).uppercase()} · ${nextPending.size} EXERCISE${if (nextPending.size == 1) "" else "S"}", 11f, true, Color.parseColor("#1E40AF")).apply {
                letterSpacing = 0.06f; (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, dp(8))
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val thumb = FrameLayout(this).apply {
                background = GradientDrawable().apply { setColor(Theme.surfaceVariant); cornerRadius = dp(8).toFloat() }
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(38))
                roundedClip(8)
            }
            val asset = PhotoLib.assetForSessionExercise(this, db, first)
            val bmp = asset?.let { PhotoLib.bitmap(this, it) }
            if (bmp != null) {
                thumb.addView(android.widget.ImageView(this).apply {
                    setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                })
            } else {
                thumb.addView(BodyMapView(this, DbHelper.parseMuscles(first.targetMuscles, first.name), true).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(28), dp(36), Gravity.CENTER)
                })
            }
            row.addView(thumb)
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(12), 0, 0, 0) }
            }
            col.addView(makeText(first.name, 15f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0) })
            val last = db.lastPerformedForExercise(first.name, sessionId)
            col.addView(makeText(if (last != null) "Last: ${lastTimeLine(last)}" else "New exercise", 12.5f, false, Theme.textSecondary).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; (layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(1), 0, 0)
            })
            row.addView(col)
            card.addView(row)
            root.addView(card)
            val startBtn = makeButton("Start ${displaySectionName(nextType)} Section") {
                sectionType = nextType
                reloadDeck()
                index = deck.indexOfFirst { it.id == first.id }.coerceAtLeast(0)
                animateEntryOnNextRender = true
                render()
            }
            (startBtn.layoutParams as LinearLayout.LayoutParams).apply { height = dp(52); setMargins(0, dp(10), 0, 0) }
            tightButton(startBtn, 52, 14, primary = true)
            root.addView(startBtn)
        }
        val seeAll = makeSecondaryButton("See All Exercises") { navigateToHub() }
        (seeAll.layoutParams as LinearLayout.LayoutParams).apply { height = dp(48); setMargins(0, dp(4), 0, 0) }
        tightButton(seeAll, 48, 12)
        root.addView(seeAll)
        if (undoStack.isNotEmpty()) {
            val undo = makeSecondaryButton("Undo last action") { doUndo(); render() }
            (undo.layoutParams as LinearLayout.LayoutParams).apply { height = dp(44); setMargins(0, dp(2), 0, 0) }
            tightButton(undo, 44, 12)
            root.addView(undo)
        }
        // 3-dot section progress: handled = green, up-next = blue,
        // upcoming = grey (mock screen 2).
        val dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(18), 0, 0) }
        }
        val all = db.getSessionExercises(sessionId)
        var firstDot = true
        for (t in listOf("Warmup", "Main", "Stretch")) {
            val list = all.filter { it.type.equals(t, true) }
            if (list.isEmpty()) continue
            if (!firstDot) dots.addView(makeText("→", 12f, false, Theme.textTertiary).apply {
                (layoutParams as LinearLayout.LayoutParams).apply { width = LinearLayout.LayoutParams.WRAP_CONTENT; setMargins(dp(8), 0, dp(8), 0) }
            })
            firstDot = false
            val handledAll = list.all { db.effectiveStatus(it) != "pending" }
            val dotColor = when {
                handledAll -> Theme.success
                t.equals(nextType, true) -> Theme.primary
                else -> Color.parseColor("#CBD8E4")
            }
            dots.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dotColor) }
                layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { setMargins(0, 0, dp(5), 0) }
            })
            dots.addView(makeText(displaySectionName(t), 11f, true, Theme.textSecondary).apply {
                (layoutParams as LinearLayout.LayoutParams).apply { width = LinearLayout.LayoutParams.WRAP_CONTENT; setMargins(0, 0, 0, 0) }
            })
        }
        root.addView(dots)
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
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

    /** v3.7 scroller strip (approved mock screen 3): every session
     *  exercise in order as a tappable thumbnail — photo when the
     *  exercise has one, body map otherwise — with a status dot and
     *  the current exercise ringed in primary. One of only two
     *  scrolling surfaces in the app (the hub list is the other). */
    private fun buildStrip(currentEx: SessionExercise): RecyclerView {
        val all = db.getSessionExercises(sessionId)
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@CardSessionActivity, LinearLayoutManager.HORIZONTAL, false)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(82)).apply { setMargins(0, dp(2), 0, 0) }
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
        return rv
    }

    private inner class StripHolder(val itemRoot: LinearLayout) : RecyclerView.ViewHolder(itemRoot) {
        lateinit var tile: FrameLayout
        lateinit var dot: TextView
        lateinit var nameTv: TextView
    }

    private inner class StripAdapter(val items: List<SessionExercise>, val currentId: Long) : RecyclerView.Adapter<StripHolder>() {
        override fun getItemCount(): Int = items.size
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): StripHolder {
            val item = LinearLayout(this@CardSessionActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.WRAP_CONTENT, RecyclerView.LayoutParams.WRAP_CONTENT)
                setPadding(dp(3), dp(2), dp(3), 0)
            }
            val holder = StripHolder(item)
            holder.tile = FrameLayout(this@CardSessionActivity).apply {
                background = GradientDrawable().apply { setColor(Theme.surfaceVariant); cornerRadius = dp(9).toFloat() }
                layoutParams = LinearLayout.LayoutParams(dp(66), dp(44))
                roundedClip(9)
            }
            holder.dot = TextView(this@CardSessionActivity).apply {
                textSize = 8.5f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                layoutParams = FrameLayout.LayoutParams(dp(15), dp(15), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(2), dp(2), 0) }
            }
            holder.nameTv = TextView(this@CardSessionActivity).apply {
                textSize = 9.5f; gravity = Gravity.CENTER
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(dp(66), dp(26)).apply { setMargins(0, dp(3), 0, 0) }
            }
            item.addView(holder.tile)
            // Name goes under the tile; the dot is added to the tile at
            // bind time (only handled exercises get one).
            item.addView(holder.nameTv)
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
                    layoutParams = FrameLayout.LayoutParams(dp(26), dp(34), Gravity.CENTER)
                })
            }
            when (db.effectiveStatus(ex)) {
                "done" -> {
                    holder.dot.text = "✓"
                    holder.dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Theme.success) }
                    holder.tile.addView(holder.dot)
                }
                "skipped" -> {
                    holder.dot.text = "✕"
                    holder.dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#94A3B8")) }
                    holder.tile.addView(holder.dot)
                }
            }
            // Current exercise ringed in primary (drawn over the tile).
            holder.tile.foreground = if (isCurrent) GradientDrawable().apply {
                setColor(Color.TRANSPARENT); cornerRadius = dp(9).toFloat()
                setStroke(dp(3), Theme.primary)
            } else null
            holder.nameTv.text = ex.name
            holder.nameTv.setTextColor(if (isCurrent) Theme.primary else Theme.textSecondary)
            holder.nameTv.typeface = if (isCurrent) android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                                       else android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
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
     *  completed the section, render() lands on the transition card
     *  (or the completion state) exactly as before; otherwise, in the
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
            RestTimer.show(this, WeekManager.restExerciseSec(this), "Next · ${nextEx.name}", cues) { advanceToPending(nextEx.id, doneId) }
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
        RestTimer.show(this, WeekManager.restSetSec(this), label, cues, after)
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

    /** v3.1 custom value editor for the set logger: a numeric EditText
     *  (decimal allowed for weight, integer for reps) prefilled with the
     *  current value; OK applies it to the set being logged. */
    private fun customValueDialog(decimal: Boolean, current: Double, title: String, apply: (Double) -> Unit) {
        val et = EditText(this).apply {
            inputType = if (decimal) android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                        else android.text.InputType.TYPE_CLASS_NUMBER
            setText(if (decimal) (if (current % 1.0 == 0.0) current.toInt().toString() else current.toString()) else current.toInt().toString())
            setSelection(text.length)
            styleEditText(this)
        }
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("OK") { _, _ ->
                val v = et.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                apply(if (decimal) v.coerceAtLeast(0.0) else v.coerceAtLeast(1.0))
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun logSetDialog(){
        val ex=current() ?: return
        val sets=db.getSets(ex.id)
        val next=sets.firstOrNull{!it.isDone}
        if(next==null){ applyDone(); return }
        val unit=WeekManager.unit(this)
        var count=1; var weightVal=next.weight.toDoubleOrNull() ?: 0.0; var repsVal=next.reps.filter{it.isDigit()}.toIntOrNull() ?: 10; var isBw=next.isBodyweight
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(12),dp(16),dp(4))}
        // v3: this exercise's last performance, with the next set's own
        // last-time value called out as the hint that matters mid-workout.
        val lastForSheet = db.lastPerformedForExercise(ex.name, sessionId)
        if (lastForSheet != null) {
            val pos = sets.indexOf(next)
            val ls = lastForSheet.sets.getOrNull(pos)
            box.addView(makeText("Last time (${DateUtil.display(lastForSheet.date)}): ${lastTimeLine(lastForSheet)}",12.5f,true,Theme.primary))
            if (ls != null) box.addView(makeText("Set ${next.setNumber} last time: ${setText(ls)}",12f,false,Theme.textSecondary))
        }
        // Sets finished stepper
        box.addView(makeText("How many sets finished?",14f,true))
        val countTv=makeText("1 set",22f,true,Color.parseColor("#92400E"))
        val cRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        cRow.addView(makeSmallButton("−"){count=(count-1).coerceAtLeast(1); countTv.text="$count set${if(count>1)"s" else ""}"})
        cRow.addView(countTv); cRow.addView(makeSmallButton("+"){count=(count+1).coerceAtMost(sets.count{!it.isDone}); countTv.text="$count set${if(count>1)"s" else ""}"})
        box.addView(cRow)
        // Weight stepper + chips
        box.addView(makeText("Weight used ($unit)",14f,true))
        val wStep=if(unit=="kg")2.5 else 5.0
        val wTv=makeText(if(isBw) "Bodyweight" else String.format(Locale.US,"%.1f %s",weightVal,unit),22f,true,Color.parseColor("#92400E"))
        // v3.1: the displayed value itself is tappable — opens the custom editor.
        wTv.setOnClickListener{ customValueDialog(true, weightVal, "Custom weight ($unit)") { v -> isBw=false; weightVal=v; wTv.text=String.format(Locale.US,"%.1f %s",weightVal,unit) } }
        val wRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        wRow.addView(makeSmallButton("−"){isBw=false; weightVal=(weightVal-wStep).coerceAtLeast(0.0); wTv.text=String.format(Locale.US,"%.1f %s",weightVal,unit)})
        wRow.addView(wTv); wRow.addView(makeSmallButton("+"){isBw=false; weightVal+=wStep; wTv.text=String.format(Locale.US,"%.1f %s",weightVal,unit)})
        box.addView(wRow)
        val wChips=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        wChips.addView(makeSmallButton("BW"){isBw=true; wTv.text="Bodyweight"})
        for(w in listOf(10,20,30,40,50)) wChips.addView(makeSmallButton("$w"){isBw=false; weightVal=w.toDouble(); wTv.text=String.format(Locale.US,"%.1f %s",weightVal,unit)})
        // v3.1: final chip opens the custom weight editor (any value).
        wChips.addView(makeSmallButton("Custom"){ customValueDialog(true, weightVal, "Custom weight ($unit)") { v -> isBw=false; weightVal=v; wTv.text=String.format(Locale.US,"%.1f %s",weightVal,unit) } })
        box.addView(wChips)
        // Reps stepper + chips
        box.addView(makeText("Reps done",14f,true))
        val rTv=makeText("$repsVal reps",22f,true,Color.parseColor("#92400E"))
        // v3.1: the displayed value itself is tappable — opens the custom editor.
        rTv.setOnClickListener{ customValueDialog(false, repsVal.toDouble(), "Custom reps") { v -> repsVal=v.toInt().coerceAtLeast(1); rTv.text="$repsVal reps" } }
        val rRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        rRow.addView(makeSmallButton("−"){repsVal=(repsVal-1).coerceAtLeast(1); rTv.text="$repsVal reps"})
        rRow.addView(rTv); rRow.addView(makeSmallButton("+"){repsVal++; rTv.text="$repsVal reps"})
        box.addView(rRow)
        val rChips=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        for(r in listOf(8,10,12,15)) rChips.addView(makeSmallButton("$r"){repsVal=r; rTv.text="$repsVal reps"})
        // v3.1: final chip opens the custom reps editor (any value).
        rChips.addView(makeSmallButton("Custom"){ customValueDialog(false, repsVal.toDouble(), "Custom reps") { v -> repsVal=v.toInt().coerceAtLeast(1); rTv.text="$repsVal reps" } })
        box.addView(rChips)
        // v3: no per-log rest picker — rest durations are the user's Settings
        // values (30s between sets / 60s between exercises by default) and
        // the Rest Ring starts automatically after saving (S2/S5).
        val typeEt=EditText(this).apply{hint="…or type weight / reps instead (optional)"; styleEditText(this); textSize=12f}
        box.addView(typeEt)
        // Compress the sheet so it fits a normal phone with no internal scrolling.
        for (i in 0 until box.childCount) {
            val v = box.getChildAt(i)
            (v.layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, dp(1), 0, dp(1))
            if (v is TextView) v.setPadding(0, dp(1), 0, dp(1))
        }
        // Bottom sheet (v2.3): anchored dialog, rounded top, drag handle, big steppers.
        val sheet = android.app.Dialog(this)
        val sheetRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Theme.surface); cornerRadii = floatArrayOf(dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), 0f, 0f, 0f, 0f) }
            setPadding(dp(20), dp(10), dp(20), dp(20))
        }
        sheetRoot.addView(View(this).apply { background = roundedBg("#D6DEE8", 4); layoutParams = LinearLayout.LayoutParams(dp(44), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, dp(2), 0, dp(12)) } })
        sheetRoot.addView(makeText("Log this set", 20f, true))
        sheetRoot.addView(box)
        // S1: one tap fills every set from last time and marks them logged
        // (rows stay editable in the list afterwards). Fewer last-time sets
        // than today's -> only those are filled.
        if (lastForSheet != null) {
            val repeatBtn = makeSecondaryButton("Repeat last time") {
                snapshot(ex)
                val mine = db.getSets(ex.id)
                for ((i, s) in mine.withIndex()) {
                    val ls = lastForSheet.sets.getOrNull(i) ?: continue
                    db.updateSet(s.copy(weight = ls.weight, reps = ls.reps, isBodyweight = ls.isBodyweight, isDone = true))
                }
                db.syncExerciseStatus(ex.id)
                sheet.dismiss()
                val remaining = db.getSets(ex.id).count { !it.isDone }
                if (remaining == 0) { db.setSessionExerciseStatus(ex.id, "done"); advanceWithRest() }
                else setRestThen(ex) { render() }
            }
            (repeatBtn.layoutParams as LinearLayout.LayoutParams).apply { height = dp(44); setMargins(0, dp(2), 0, 0) }
            sheetRoot.addView(repeatBtn)
        }
        val saveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cancelBtn = makeSecondaryButton("Cancel") { sheet.dismiss() }
        (cancelBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f }
        val saveBtn = makeButton("Save Set") {
            snapshot(ex)
            val targets=sets.filter{!it.isDone}.take(count)
            val typed=typeEt.text.toString().trim().split("/").map{it.trim()}
            val wStr=if(typed.size>=2&&typed[0].isNotEmpty()) typed[0] else if(isBw) "" else String.format(Locale.US,if(weightVal%1.0==0.0)"%.0f" else "%.1f",weightVal)
            val rStr=if(typed.size>=2&&typed[1].isNotEmpty()) typed[1].filter{it.isDigit()}.ifEmpty{repsVal.toString()} else repsVal.toString()
            for(s in targets) db.updateSet(s.copy(weight=wStr, reps=rStr, isBodyweight=isBw, isDone=true))
            db.syncExerciseStatus(ex.id)
            sheet.dismiss()
            val remaining=db.getSets(ex.id).count{!it.isDone}
            if(remaining==0){ db.setSessionExerciseStatus(ex.id,"done"); advanceWithRest() }
            else setRestThen(ex){ render() }
        }
        (saveBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f }
        saveRow.addView(cancelBtn); saveRow.addView(saveBtn)
        sheetRoot.addView(saveRow)
        sheet.setContentView(sheetRoot)
        sheet.window?.apply {
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        sheet.show()
    }

}
