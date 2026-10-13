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
    private var downX=0f; private var downY=0f

    // --- Swipe-card (v2.2) state: the whole card is one moving view ---
    private var cardWrap: FrameLayout? = null      // dragged container (card + tint + labels)
    private var cardSurface: LinearLayout? = null   // the visible card itself (shadow/border/band/content)
    private var tintOverlay: View? = null
    private var doneLabel: TextView? = null
    private var skipLabel: TextView? = null
    private var upLabel: TextView? = null
    private var downLabel: TextView? = null
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
        if(sessionId<0){finish();return}
        reloadDeck(); moveToPending(); render()
    }

    override fun onPause() { super.onPause(); timerChip?.stopTicking(); timerChip?.foldNow(); photoLoopers.forEach { it.stop() } }
    override fun onResume() { super.onResume(); photoLoopers.forEach { it.start() } }
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
            // Completion state (fixed screen, no scrolling)
            val root=fitRoot(); setContentView(root)
            root.addView(topBar(sectionLabel(sectionType), "Section complete"))
            root.addView(makeText("${sectionLabel(sectionType)} complete!",26f,true,Color.parseColor("#15803D")))
            val done=deck.count{db.effectiveStatus(it)=="done"}; val skipped=deck.count{db.effectiveStatus(it)=="skipped"}
            root.addView(makeText("$done done • $skipped skipped",15f,false))
            if(undoStack.isNotEmpty()) root.addView(makeButton("Undo last action"){doUndo(); render()}.apply { (layoutParams as LinearLayout.LayoutParams).apply { height = dp(48); setMargins(0, dp(4), 0, 0) } })
            root.addView(makeButton("Back to Sections"){finish()}.apply { (layoutParams as LinearLayout.LayoutParams).apply { height = dp(48); setMargins(0, dp(4), 0, 0) } })
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
            val chip = TimerChipView(this)
            chip.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30)).apply { setMargins(0, dp(4), 0, 0) }
            chip.bind(this, db, db.getSession(sessionId) ?: sessForTimer) {}
            chip.startTicking()
            timerChip = chip
            top.addView(chip)
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
            setPadding(dp(14), dp(14), dp(14), dp(12))
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
        // v3.1: the exercise photo is the BACKGROUND of the card header —
        // a CENTER_CROP ImageView filling a rounded-corner header frame
        // (never stretched), exercise name + last-time line overlaid at the
        // bottom on a dark gradient scrim. Where the dataset shipped a
        // second frame it crossfade-loops (PhotoLooper). No photo -> the
        // body map gets the same header space on the mist surface.
        val photoAsset = PhotoLib.assetForSessionExercise(this, db, ex)
        val photoBmp = photoAsset?.let { PhotoLib.bitmap(this, it) }
        val header = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(148)).apply { setMargins(0, 0, 0, dp(6)) }
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
            header.addView(View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(84), Gravity.BOTTOM)
            })
            val overlay = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(12), dp(8))
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            }
            overlay.addView(makeText(ex.name, 20f, true, Color.WHITE).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0) })
            overlay.addView(makeText(lastLine, 12.5f, true, Color.WHITE).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0) })
            header.addView(overlay)
        } else {
            header.background = roundedBg("#EDF2F7", 16)
            header.addView(BodyMapView(this, muscles, true).apply {
                layoutParams = FrameLayout.LayoutParams(dp(76), dp(96), Gravity.CENTER_VERTICAL or Gravity.END).apply { setMargins(0, 0, dp(14), 0) }
            })
            val overlay = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(12), dp(8))
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            }
            overlay.addView(makeText(ex.name, 20f, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0) })
            overlay.addView(makeText(lastLine, 12.5f, true, Theme.primary).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; (layoutParams as LinearLayout.LayoutParams).setMargins(0, 0, 0, 0) })
            header.addView(overlay)
        }
        dragCard.addView(header)
        // Essentials under the header: badge, variation, do-it, set dots.
        val essInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        essInfo.addView(equipmentBadge(ex.equipment))
        if(ex.variation.isNotBlank()) essInfo.addView(makeText(ex.variation,12f,false,Theme.textSecondary))
        essInfo.addView(makeText("Do it: ${doItLine(sets)}",14f,true,Color.parseColor("#92400E")))
        essInfo.addView(makeText("Sets: $doneSets of ${sets.size} done",13f,true))
        essInfo.addView(makeText(sets.mapIndexed{i,s-> if(s.isDone) "●" else "○"}.joinToString("  "),18f,true,Color.parseColor("#15803D")))
        dragCard.addView(essInfo)

        // v2.6: no Show/Hide toggle — "Check your posture" is always visible.
        // Compact posture block: video button, feel line, <=4 posture bullets,
        // <=2 cue lines, start-light hint. Fits inside the fixed-height card.
        run {
            val how = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(2), dp(4), 0) }
            if(ex.youtubeUrl.isNotBlank()) {
                val vid = primaryButtonWithIcon("Watch Form Video", R.drawable.ic_play){openUrl(this,ex.youtubeUrl)}
                (vid.layoutParams as LinearLayout.LayoutParams).height = dp(44)
                tightButton(vid, 44, 12, primary = true); vid.setPadding(dp(8), 0, dp(8), 0)
                how.addView(vid)
            }
            how.addView(makeText("What you'll feel: ${musclesLabel(muscles)}",12f,false,Color.parseColor("#0369A1")))
            val posture = ex.postureCheck.ifBlank{ DbHelper.postureForName(ex.name, ex.type) }
            if(posture.isNotBlank()){
                val items = posture.split(";").map{it.trim()}.filter{it.isNotEmpty()}
                how.addView(makeText("Check your posture:",13f,true,Color.parseColor("#15803D")))
                // v3: with a photo on the card, trim to 3 checks / 1 cue so the
                // fixed-height card never clips (posture content itself intact).
                for(c in items.take(if (photoBmp != null) 3 else 4)) how.addView(makeText("☐  $c",12f,false).apply { setLineSpacing(0f, 0.95f); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            }
            val cues=ex.cues.ifBlank{DbHelper.cuesForName(ex.name,ex.equipment,ex.type)}
            if(cues.isNotBlank()){
                val items = cues.split(";").map{it.trim()}.filter{it.isNotEmpty()}
                for(c in items.take(if (photoBmp != null) 1 else 2)) how.addView(makeText("•  $c",11f,false,Theme.textSecondary).apply { setLineSpacing(0f, 0.95f); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            }
            if(ex.equipment=="Machine"||ex.equipment=="Dumbbell"||ex.equipment=="Kettlebell") how.addView(makeText("Start light: use the lightest weight that feels easy first.",11f,false,Color.parseColor("#92400E")))
            dragCard.addView(how)
        }
        // v3.1: one small swipe-hint line sits under the button row (Skip /
        // Done are swipe-only now, so the hint earns its place). "Next up:"
        // stays — it's informative.

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
                            abs(dx) >= tx && abs(dx) >= 1.3f * abs(dy) -> if(dx > 0) animateExitThen(v, 1) { applyDone() } else animateExitThen(v, -1) { applySkipped() }
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

        // v3.1 final card design (user-approved mock): NO Skip and NO Done
        // buttons anywhere — those two actions are swipe-only (left/right).
        // The only buttons on this screen are Log set (primary) and Later
        // (secondary) in one slim row; up-swipe also opens Log set and
        // down-swipe is Later, the same two actions.
        val wrapRef = wrap
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)) }
        val logBtn = makeButton("Log set") { animateLogSetLift(wrapRef) { logSetDialog() } }
        (logBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; setMargins(dp(2), 0, dp(2), 0) }
        tightButton(logBtn, 46, 12, primary = true)
        btnRow.addView(logBtn)
        if (laterAvailable()) {
            val laterBtn = makeSecondaryButton("Later") { animateLaterDrop(wrapRef) { doLater() } }
            (laterBtn.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; setMargins(dp(2), 0, dp(2), 0) }
            tightButton(laterBtn, 46, 12)
            btnRow.addView(laterBtn)
        }
        root.addView(btnRow)
        root.addView(makeText("Swipe ← Skip · → Done · ↓ Later", 11.5f, false, Theme.textSecondary).apply {
            gravity = Gravity.CENTER
            (layoutParams as LinearLayout.LayoutParams).apply { height = dp(18); setMargins(0, dp(2), 0, 0) }
        })
        val next = deck.getOrNull(index + 1)
        if (next != null) root.addView(makeText("Next up: ${next.name}", 12f, false, Color.parseColor("#0369A1")).apply {
            (layoutParams as LinearLayout.LayoutParams).apply { height = dp(20); setMargins(0, 0, 0, 0) }
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
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
        advanceWithRest()
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
    /** Advance to the next pending card. In the Exercises phase, crossing to
     *  a new exercise first shows the Rest Ring for the between-exercises
     *  duration (default 60s, Settings-adjustable) with the next exercise's
     *  name as the target line (S2/S5). */
    private fun advanceWithRest(){
        index++; moveToPendingFrom(index)
        val nextEx = current()
        if (sectionType == "Main" && nextEx != null && db.effectiveStatus(nextEx) == "pending") {
            val cues = nextEx.cues.ifBlank { DbHelper.cuesForName(nextEx.name, nextEx.equipment, nextEx.type) }
                .split(";").map { it.trim() }.filter { it.isNotEmpty() }
            RestTimer.show(this, WeekManager.restExerciseSec(this), "Next · ${nextEx.name}", cues) { render() }
        } else render()
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
        sheetRoot.addView(makeText("Set done - nice! Log this set", 12f, false, Theme.textSecondary))
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
