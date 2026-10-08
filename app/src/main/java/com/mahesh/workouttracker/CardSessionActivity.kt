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
import android.widget.ScrollView
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
    private val howToExpanded = mutableSetOf<Long>() // exercise ids with "Show me how" open (card mode)
    private var howToDefaultApplied = false
    private var timerChip: TimerChipView? = null
    private var downX=0f; private var downY=0f

    // --- Swipe-card (v2.2) state: the whole card is one moving view ---
    private var cardWrap: FrameLayout? = null      // dragged container (card + tint + labels)
    private var cardSurface: LinearLayout? = null   // the visible card itself (shadow/border/band/content)
    private var tintOverlay: View? = null
    private var doneLabel: TextView? = null
    private var skipLabel: TextView? = null
    private var upLabel: TextView? = null
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

    override fun onPause() { super.onPause(); timerChip?.stopTicking(); timerChip?.foldNow() }
    override fun onDestroy() { super.onDestroy(); timerChip?.stopTicking() }

    private fun reloadDeck(){ deck=db.getSessionExercises(sessionId).filter{it.type.equals(sectionType,true)} }
    private fun moveToPending(){
        val i=deck.indexOfFirst{ db.effectiveStatus(it)=="pending" }
        index=if(i>=0) i else deck.size
    }
    private fun current(): SessionExercise? = deck.getOrNull(index)
    private fun snapshot(ex: SessionExercise){ undoStack.addLast(UndoSnap(ex.id, ex.status, ex.effort, db.getSets(ex.id))) }

    private fun dragCardBand(card: LinearLayout) {
        card.addView(View(this).apply { setBackgroundColor(sectionColor(sectionType)); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply { setMargins(0, 0, 0, dp(12)) } })
    }

    private fun render(){
        reloadDeck()
        if(index !in deck.indices){
            // Completion state
            val root=rootLayout(); setContentView(ScrollView(this).apply{addView(root)})
            root.addView(topBar(sectionLabel(sectionType), "Section complete"))
            root.addView(makeText("${sectionLabel(sectionType)} complete!",26f,true,Color.parseColor("#15803D")))
            val done=deck.count{db.effectiveStatus(it)=="done"}; val skipped=deck.count{db.effectiveStatus(it)=="skipped"}
            root.addView(makeText("You finished this section.\n$done done • $skipped skipped\nGreat job — head back to pick the next section.",15f,false))
            if(undoStack.isNotEmpty()) root.addView(makeButton("Undo last action"){doUndo(); render()})
            root.addView(makeButton("Back to Sections"){finish()})
            Toast.makeText(this,"${sectionLabel(sectionType)} complete!",Toast.LENGTH_SHORT).show()
            return
        }
        val ex=current()!!
        val sets=db.getSets(ex.id)
        val doneSets=sets.count{it.isDone}
        val unit=WeekManager.unit(this)
        val root=rootLayout(); setContentView(ScrollView(this).apply{addView(root)})
        root.addView(topBar(sectionLabel(sectionType), "Card ${index+1} of ${deck.size}"))
        // Top bar color-coded + workout timer chip
        val top=cardLayout(when(sectionType){"Warmup"->"#D97706";"Stretch"->"#0D9488";else->"#2563EB"})
        top.addView(makeText("${sectionLabel(sectionType)}  •  Card ${index+1} of ${deck.size}  •  ${deck.count{db.effectiveStatus(it)=="done"}} done • ${deck.count{db.effectiveStatus(it)=="skipped"}} skipped",14f,true))
        top.addView(hProgress(deck.size, deck.count{db.effectiveStatus(it)!="pending"}, sectionColor(sectionType)))
        val sessForTimer = db.getSession(sessionId)
        if (sessForTimer != null && !sessForTimer.completed) {
            db.ensureSessionStarted(sessionId)
            val chip = TimerChipView(this)
            chip.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, 0) }
            chip.bind(this, db, db.getSession(sessionId) ?: sessForTimer) {}
            chip.startTicking()
            timerChip = chip
            top.addView(chip)
        }
        root.addView(top)

        // ---- Card deck (v2.2): ONE moving card over two static peek cards ----
        val deckBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(10), 0, dp(8)) }
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
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            clipChildren = false; clipToPadding = false
        }
        val dragCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = deckCardDrawable()
            elevation = dp(8).toFloat()
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        cardSurface = dragCard
        dragCardBand(dragCard)
        val muscles=DbHelper.parseMuscles(ex.targetMuscles, ex.name)
        // Essentials only up front (approved mockup): name, do-it, dots, badge, map, last done.
        val essRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        essRow.addView(BodyMapView(this,muscles,true).apply{layoutParams=LinearLayout.LayoutParams(dp(104),dp(130))})
        val essInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(12),0,0,0) }
        essInfo.addView(equipmentBadge(ex.equipment))
        essInfo.addView(makeText(ex.name,24f,true))
        if(ex.variation.isNotBlank()) essInfo.addView(makeText(ex.variation,12f,false,Theme.textSecondary))
        essInfo.addView(makeText("Do it: ${doItLine(sets)}",16f,true,Color.parseColor("#92400E")))
        essInfo.addView(makeText("Sets: $doneSets of ${sets.size} done",14f,true))
        essInfo.addView(makeText(sets.mapIndexed{i,s-> if(s.isDone) "●" else "○"}.joinToString("  "),22f,true,Color.parseColor("#15803D")))
        essRow.addView(essInfo)
        dragCard.addView(essRow)
        // Last done
        val last=db.lastPerformedForExercise(ex.name, sessionId)
        if(last!=null){
            val summary=last.sets.joinToString(", "){s-> if(s.isBodyweight) "BW x${s.reps}" else "${s.weight} $unit x ${s.reps}"}
            val effortBit=if(last.effort.isNotBlank()) " • ${last.effort} — ${Beginner.effortSuggestion(last.effort,unit)}" else ""
            dragCard.addView(makeText("Last done: ${DateUtil.display(last.date)} • $summary$effortBit",12f,false,Theme.textSecondary))
        } else dragCard.addView(makeText("First time — no previous record. Start light.",12f,false,Theme.textSecondary))

        // "Show me how" — educational content, collapsible. First card of the section
        // defaults to expanded, the rest collapsed; remembered per exercise in this visit.
        if (!howToDefaultApplied) { howToDefaultApplied = true; howToExpanded.add(ex.id) }
        val howExpanded = howToExpanded.contains(ex.id)
        val howToggle = makeSecondaryButton(if (howExpanded) "Hide how-to  ▴" else "Show me how  ▾") {
            if (howToExpanded.contains(ex.id)) howToExpanded.remove(ex.id) else howToExpanded.add(ex.id)
            render()
        }
        dragCard.addView(howToggle)
        if (howExpanded) {
            val how = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(6), dp(4), 0) }
            if(ex.youtubeUrl.isNotBlank()) how.addView(primaryButtonWithIcon("Watch Form Video", R.drawable.ic_play){openUrl(this,ex.youtubeUrl)})
            how.addView(makeText("What you'll feel: ${musclesLabel(muscles)}",13f,false,Color.parseColor("#0369A1")))
            if(Beginner.beginnerMode(this)){
                val posture = ex.postureCheck.ifBlank{ DbHelper.postureForName(ex.name, ex.type) }
                if(posture.isNotBlank()){
                    how.addView(makeText("Check your posture:",15f,true,Color.parseColor("#15803D")))
                    for(c in posture.split(";").map{it.trim()}.filter{it.isNotEmpty()}) how.addView(makeText("☐  $c",13f,false))
                }
                val cues=ex.cues.ifBlank{DbHelper.cuesForName(ex.name,ex.equipment,ex.type)}
                if(cues.isNotBlank()){
                    how.addView(makeText("Form cues:",14f,true,Color.parseColor("#0369A1")))
                    for(c in cues.split(";").map{it.trim()}.filter{it.isNotEmpty()}) how.addView(makeText("•  $c",13f,false,Theme.textSecondary))
                }
                if(ex.equipment=="Machine"||ex.equipment=="Dumbbell"||ex.equipment=="Kettlebell") how.addView(makeText("Start light: use the lightest weight that feels easy first.",12f,false,Color.parseColor("#92400E")))
            } else {
                val cues=ex.cues.ifBlank{DbHelper.cuesForName(ex.name,ex.equipment,ex.type)}
                if(cues.isNotBlank()){ how.addView(makeText("Form cues:",14f,true,Color.parseColor("#0369A1"))); for(c in cues.split(";").map{it.trim()}.filter{it.isNotEmpty()}) how.addView(makeText("•  $c",13f,false,Theme.textSecondary)) }
            }
            dragCard.addView(how)
        }
        // Swipe hint
        dragCard.addView(makeText("Swipe:  Skip  •  Done  •  Log Set   (or use buttons below)",11f,false,Theme.textSecondary))

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
        wrap.addView(skipLabel); wrap.addView(doneLabel); wrap.addView(upLabel)
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

        // Buttons (every gesture has a button): same animated exits as the swipes.
        val wrapRef = wrap
        val row1=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        fun actionBtn(label: String, act: () -> Unit): android.widget.Button {
            val b = makeSmallButton(label, act)
            (b.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(52) }
            return b
        }
        row1.addView(smallButtonWithIcon("Skip", R.drawable.ic_close, Theme.textPrimary) { animateExitThen(wrapRef, -1) { applySkipped() } }.apply { (layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(52) } })
        row1.addView(smallButtonWithIcon("Log Set", R.drawable.ic_arrow_up, Theme.primary) { animateLogSetLift(wrapRef) { logSetDialog() } }.apply { (layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(52) } })
        row1.addView(smallButtonWithIcon("Done", R.drawable.ic_check, Theme.success) { animateExitThen(wrapRef, 1) { applyDone() } }.apply { (layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; height = dp(52) } })
        root.addView(row1)
        val swap=SwapMap.forExercise(ex.name)
        if(swap!=null && sectionType=="Main"){
            root.addView(makeButton("Too hard / Busy? Swap → ${swap.first}"){
                AlertDialog.Builder(this).setTitle("Swap exercise?").setMessage("Replace ${ex.name} with ${swap.first}? Your logged sets stay.").setPositiveButton("Swap"){_,_-> snapshot(ex); db.swapSessionExercise(ex.id,swap.first,swap.second); Toast.makeText(this,"Swapped to ${swap.first}",Toast.LENGTH_SHORT).show(); render()}.setNegativeButton("Cancel",null).show()
            })
        }
        if(undoStack.isNotEmpty()) root.addView(makeButton("Undo"){doUndo(); render()})
        root.addView(makeButton("View as list / edit sets"){startActivity(Intent(this,SessionActivity::class.java).apply{putExtra("sessionId",sessionId)})})
        val next=deck.getOrNull(index+1)
        if(next!=null) root.addView(makeText("Next up: ${next.name}",13f,false,Color.parseColor("#0369A1")))
        root.addView(makeButton("Back to Sections"){finish()})
    }

    // ---- Drag feedback + card animations (v2.2) ----

    /** Faded direction label + card tint/border grow with drag progress toward the commit threshold. */
    private fun updateDragFeedback(v: View, dx: Float, dy: Float, tx: Float, ty: Float) {
        val px = if (tx > 0) abs(dx) / tx else 0f
        val py = if (dy < 0 && ty > 0) abs(dy) / ty else 0f
        val horizontal = px >= py
        val progress = (if (horizontal) px else py).coerceIn(0f, 1f)
        val dirColor = when {
            !horizontal -> Color.parseColor("#2563EB")
            dx >= 0 -> Color.parseColor("#15803D")
            else -> Color.parseColor("#DC2626")
        }
        doneLabel?.alpha = if (horizontal && dx > 0) progress else 0f
        skipLabel?.alpha = if (horizontal && dx < 0) progress else 0f
        upLabel?.alpha = if (!horizontal) progress else 0f
        val active = when { horizontal && dx > 0 -> doneLabel; horizontal && dx < 0 -> skipLabel; !horizontal -> upLabel; else -> null }
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

    // ---- Actions (applied after the card animation, or directly from dialogs) ----

    private fun applySkipped(){
        val ex=current() ?: return; snapshot(ex)
        db.setSessionExerciseStatus(ex.id,"skipped")
        Toast.makeText(this,"Skipped ${ex.name}",Toast.LENGTH_SHORT).show()
        index++; moveToPendingFrom(index); render()
    }
    private fun applyDone(){
        val ex=current() ?: return; snapshot(ex)
        for(s in db.getSets(ex.id)){ if(!s.isDone) db.updateSet(s.copy(isDone=true)) }
        db.setSessionExerciseStatus(ex.id,"done")
        askEffort(ex){ index++; moveToPendingFrom(index); render() }
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

    private fun askEffort(ex: SessionExercise, after: ()->Unit){
        val opts=arrayOf("Too Easy","Just Right","Too Hard")
        AlertDialog.Builder(this).setTitle("How was that?")
            .setItems(opts){_,which-> db.setSessionExerciseEffort(ex.id,opts[which]); after()}
            .setNegativeButton("Skip"){_,_->after()}.setCancelable(false).show()
    }

    private fun logSetDialog(){
        val ex=current() ?: return
        val sets=db.getSets(ex.id)
        val next=sets.firstOrNull{!it.isDone}
        if(next==null){ applyDone(); return }
        val unit=WeekManager.unit(this)
        var count=1; var weightVal=next.weight.toDoubleOrNull() ?: 0.0; var repsVal=next.reps.filter{it.isDigit()}.toIntOrNull() ?: 10; var restSec=60; var isBw=next.isBodyweight
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(12),dp(16),dp(4))}
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
        val wRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        wRow.addView(makeSmallButton("−"){isBw=false; weightVal=(weightVal-wStep).coerceAtLeast(0.0); wTv.text=String.format(Locale.US,"%.1f %s",weightVal,unit)})
        wRow.addView(wTv); wRow.addView(makeSmallButton("+"){isBw=false; weightVal+=wStep; wTv.text=String.format(Locale.US,"%.1f %s",weightVal,unit)})
        box.addView(wRow)
        val wChips=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        wChips.addView(makeSmallButton("BW"){isBw=true; wTv.text="Bodyweight"})
        for(w in listOf(10,20,30,40,50)) wChips.addView(makeSmallButton("$w"){isBw=false; weightVal=w.toDouble(); wTv.text=String.format(Locale.US,"%.1f %s",weightVal,unit)})
        box.addView(wChips)
        // Reps stepper + chips
        box.addView(makeText("Reps done",14f,true))
        val rTv=makeText("$repsVal reps",22f,true,Color.parseColor("#92400E"))
        val rRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        rRow.addView(makeSmallButton("−"){repsVal=(repsVal-1).coerceAtLeast(1); rTv.text="$repsVal reps"})
        rRow.addView(rTv); rRow.addView(makeSmallButton("+"){repsVal++; rTv.text="$repsVal reps"})
        box.addView(rRow)
        val rChips=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        for(r in listOf(8,10,12,15)) rChips.addView(makeSmallButton("$r"){repsVal=r; rTv.text="$repsVal reps"})
        box.addView(rChips)
        // Rest chips
        box.addView(makeText("Rest before next set",14f,true))
        val restTv=makeText("60 sec rest",18f,true,Color.parseColor("#0369A1"))
        val restRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        for(sec in listOf(30,60,90)) restRow.addView(makeSmallButton("${sec}s"){restSec=sec; restTv.text="$sec sec rest"})
        restRow.addView(makeSmallButton("−"){restSec=(restSec-10).coerceAtLeast(10); restTv.text="$restSec sec rest"})
        restRow.addView(makeSmallButton("+"){restSec+=10; restTv.text="$restSec sec rest"})
        box.addView(restRow); box.addView(restTv)
        val typeEt=EditText(this).apply{hint="…or type weight / reps instead (optional)"; styleEditText(this); textSize=12f}
        box.addView(typeEt)
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
        sheetRoot.addView(ScrollView(this).apply { addView(box) })
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
            if(remaining==0){ db.setSessionExerciseStatus(ex.id,"done"); askEffort(ex){ startRest(restSec){ render() } } }
            else startRest(restSec){ render() }
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

    private fun startRest(seconds: Int, after: ()->Unit){
        if(seconds<=0){ after(); return }
        var remaining=seconds
        val dlg=AlertDialog.Builder(this).setTitle("Rest before next set").setMessage("Rest $remaining sec…").setPositiveButton("Skip"){_,_->after()}.setCancelable(false).create()
        dlg.show(); lbl[0]=dlg
        object: CountDownTimer(seconds*1000L,1000){
            override fun onTick(ms: Long){ remaining=(ms/1000).toInt(); if(dlg.isShowing) dlg.setMessage("Rest $remaining sec… ${if(remaining<=10) "Get ready!" else ""}") }
            override fun onFinish(){ if(dlg.isShowing) dlg.dismiss(); Toast.makeText(this@CardSessionActivity,"Rest done - go!",Toast.LENGTH_SHORT).show(); after() }
        }.start()
    }
    private val lbl=arrayOfNulls<AlertDialog>(1)
}
