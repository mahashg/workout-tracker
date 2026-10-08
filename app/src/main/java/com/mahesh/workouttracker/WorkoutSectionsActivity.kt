package com.mahesh.workouttracker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class WorkoutSectionsActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var sessionId: Long = -1
    private var timerChip: TimerChipView? = null
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); db=DbHelper(this); sessionId=intent.getLongExtra("sessionId",-1); if(sessionId<0){finish();return} }
    override fun onResume(){ super.onResume(); if(::db.isInitialized && sessionId>0) render() }
    override fun onPause(){ super.onPause(); timerChip?.stopTicking(); timerChip?.foldNow() }

    private fun render(){
        val session = db.getSession(sessionId) ?: run{finish();return}
        val exs = db.getSessionExercises(sessionId)
        val root=rootLayout(); setContentView(ScrollView(this).apply{addView(root)})
        root.addView(topBar(session.routineName, "${DateUtil.display(session.date)} • Week ${session.weekNumber}"))
        val muscles = linkedSetOf<String>(); for(ex in exs) muscles.addAll(DbHelper.parseMuscles(ex.targetMuscles, ex.name))
        val header=cardLayout()
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        row.addView(BodyMapView(this,muscles,true).apply{layoutParams=LinearLayout.LayoutParams(dp(96),dp(120))})
        val info=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL; layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f); setPadding(dp(12),0,0,0)}
        info.addView(makeText(session.routineName,20f,true))
        info.addView(caption("${session.focus}\nWhat you'll feel: ${musclesLabel(muscles)}"))
        info.addView(statusPill(if(session.completed) "Done" else if(exs.any{db.effectiveStatus(it)!="pending"}) "In Progress" else "Pending"))
        row.addView(info); header.addView(row)
        if (!session.completed) {
            db.ensureSessionStarted(sessionId)
            val chip = TimerChipView(this)
            chip.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), 0, 0) }
            chip.bind(this, db, db.getSession(sessionId) ?: session) {}
            chip.startTicking()
            timerChip = chip
            header.addView(chip)
        } else if (session.startedAt > 0) {
            header.addView(caption("Time: ${WorkoutTimer.formatDuration(session.elapsedSec)}"))
        }
        root.addView(header)

        val allHandled = exs.isNotEmpty() && exs.all{db.effectiveStatus(it)!="pending"}
        if (Beginner.beginnerMode(this)) {
            val tip = cardLayout("#D97706")
            tip.addView(makeText("Beginner Mode is ON", 13f, true, Color.parseColor("#92400E")))
            tip.addView(caption("Each card shows what to do, posture checks, and what you should feel. Stop if you feel sharp pain."))
            root.addView(tip)
        }

        root.addView(sectionLabelText("Today's sections"))
        for((label,type) in listOf("Warm Up" to "Warmup","Exercise" to "Main","Cool Down" to "Stretch")){
            val list=exs.filter{it.type.equals(type,true)}; if(list.isEmpty()) continue
            val done=list.count{db.effectiveStatus(it)=="done"}; val skipped=list.count{db.effectiveStatus(it)=="skipped"}; val handled=done+skipped
            val colorHex=when(type){"Warmup"->"#D97706";"Stretch"->"#0D9488";else->"#2563EB"}
            val iconRes=when(type){"Warmup"->R.drawable.ic_arrow_up;"Stretch"->R.drawable.ic_check;else->R.drawable.ic_play}
            val openCards = { startActivity(Intent(this,CardSessionActivity::class.java).apply{putExtra("sessionId",sessionId); putExtra("sectionType",type)}) }
            val card=cardLayout(colorHex, tappable=true) { openCards() }
            val row2=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            row2.addView(View(this).apply{setBackgroundColor(Color.parseColor(colorHex)); layoutParams=LinearLayout.LayoutParams(dp(6),LinearLayout.LayoutParams.MATCH_PARENT).apply{setMargins(0,dp(2),dp(12),dp(2))}})
            val col=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL; layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)}
            val titleRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL}
            titleRow.addView(iconView(iconRes, 20, Color.parseColor(colorHex)))
            titleRow.addView(makeText("  $label",20f,true,Color.parseColor(colorHex)))
            titleRow.addView(iconView(R.drawable.ic_chevron, 20, Theme.textTertiary))
            col.addView(titleRow)
            col.addView(caption("$handled of ${list.size} done/skipped  •  $done done • $skipped skipped"))
            col.addView(hProgress(list.size, handled, Color.parseColor(colorHex)))
            col.addView(caption(list.joinToString("\n"){ex->"${if(db.effectiveStatus(ex)=="done")"✓" else if(db.effectiveStatus(ex)=="skipped")"↷ skipped" else "•"} ${ex.name}"}))
            row2.addView(col); card.addView(row2)
            card.addView(makeButton("Open $label Cards") { openCards() })
            root.addView(card)
        }
        root.addView(makeSecondaryButton("View as list / edit sets"){ startActivity(Intent(this,SessionActivity::class.java).apply{putExtra("sessionId",sessionId)}) })
        val finishBtn=makeButton("Finish Workout"){
            if(!allHandled){ AlertDialog.Builder(this).setTitle("Finish early?").setMessage("Some exercises are not done or skipped yet. Finish anyway?").setPositiveButton("Finish"){_,_->doFinish()}.setNegativeButton("Keep going",null).show() }
            else doFinish()
        }
        if(allHandled) finishBtn.background=roundedBg("#15803D",16)
        root.addView(finishBtn)
    }
    private fun doFinish(){ val fresh=db.getSession(sessionId); if(fresh!=null) WorkoutTimer.finish(this,db,fresh); db.setSessionCompleted(sessionId,true); startActivity(Intent(this,SummaryActivity::class.java).apply{putExtra("sessionId",sessionId)}); finish() }
}
