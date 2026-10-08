package com.mahesh.workouttracker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity

class WorkoutDetailActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var routineId: Long = -1
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); db=DbHelper(this); Seed.ensureSeeded(db); Seed.applyV2IfNeeded(this,db); routineId=intent.getLongExtra("routineId",-1); if(routineId<0){finish();return}; render() }
    override fun onResume(){ super.onResume(); if(::db.isInitialized && routineId>0) render() }

    private fun sectionPreviewCard(label: String, colorHex: String, countLine: String, lines: List<String>): LinearLayout {
        val card = cardLayout()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(View(this).apply { setBackgroundColor(Color.parseColor(colorHex)); layoutParams = LinearLayout.LayoutParams(dp(5), LinearLayout.LayoutParams.MATCH_PARENT).apply { setMargins(0, dp(2), dp(12), dp(2)) } })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        col.addView(makeText(label, 16f, true, Color.parseColor(colorHex)))
        col.addView(caption(countLine))
        for (l in lines) col.addView(bodyText(l))
        row.addView(col); card.addView(row)
        return card
    }

    private fun render(){
        val routine = db.getRoutine(routineId) ?: run{finish();return}
        val state = WeekManager.reconcile(this, db)
        val sessionsWeek = db.sessionsInWeek(state.weekStart)
        val inProg = sessionsWeek.firstOrNull{it.routineId==routine.id && !it.completed} ?: db.inProgressSessions().firstOrNull{it.routineId==routine.id}
        val completed = sessionsWeek.firstOrNull{it.routineId==routine.id && it.completed}
        val root=rootLayout(); setContentView(ScrollView(this).apply{addView(root)})
        root.addView(topBar(routine.name, DateUtil.dayName(routine.weekday)))

        // Start/Resume FIRST, prominent at top of content (per Mahesh)
        val startCard = cardLayout("#F59E0B")
        startCard.addView(makeText(if(inProg!=null) "● IN PROGRESS — pick up where you left off" else if(completed!=null) "✓ DONE THIS WEEK" else "○ NOT STARTED YET", 11f, true, Color.parseColor("#92400E")))
        startCard.addView(primaryButtonWithIcon(if(inProg!=null) "Resume Workout" else if(completed!=null) "Start Again (new session)" else "Start Workout", R.drawable.ic_play){
            val sid = if(inProg!=null) inProg.id else db.createSession(routine, state.weekNumber, state.weekStart)
            startActivity(Intent(this, WorkoutSectionsActivity::class.java).apply{putExtra("sessionId",sid)})
        })
        startCard.addView(caption("This page is just a preview — no workout starts until you tap above."))
        root.addView(startCard)

        // Hero card
        val hero = cardLayout()
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val muscles=db.routineMuscles(routine.id)
        row.addView(BodyMapView(this,muscles,true).apply{layoutParams=LinearLayout.LayoutParams(dp(110),dp(138))})
        val info=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL; layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f); setPadding(dp(12),0,0,0)}
        info.addView(makeText(routine.name,22f,true))
        val mainCount = db.getExercises(routine.id).count{it.type.equals("Main",true)}
        val totalCount = db.routineExerciseCount(routine.id)
        info.addView(caption("${routine.focus}\n${DateUtil.dayName(routine.weekday)} • $mainCount exercises ($totalCount total incl. warm-up & cool-down)"))
        info.addView(makeText("What you'll feel: ${musclesLabel(muscles)}",13f,true,Color.parseColor("#0369A1")))
        info.addView(statusPill(when{completed!=null->"Done"; inProg!=null->"In Progress"; else->"Pending"}))
        row.addView(info); hero.addView(row); root.addView(hero)

        if(inProg!=null || completed!=null){
            val sess = inProg ?: completed!!
            root.addView(sectionLabelText("Your progress"))
            root.addView(caption("Started ${DateUtil.display(sess.date)} • Week ${sess.weekNumber}. Sections show live progress."))
            for((label,type) in listOf("Warm Up" to "Warmup","Exercise" to "Main","Cool Down" to "Stretch")){
                val list=db.getSessionExercises(sess.id).filter{it.type.equals(type,true)}
                val done=list.count{db.effectiveStatus(it)=="done"}; val skipped=list.count{db.effectiveStatus(it)=="skipped"}
                val colorHex=when(type){"Warmup"->"#D97706";"Stretch"->"#0D9488";else->"#2563EB"}
                root.addView(sectionPreviewCard(label, colorHex, "${done+skipped} of ${list.size} done/skipped",
                    list.map{ ex -> val st=db.effectiveStatus(ex); "${if(st=="done")"✓" else if(st=="skipped")"↷" else "•"}  ${ex.name}" }))
            }
            root.addView(makeButton("Open Sections"){ startActivity(Intent(this,WorkoutSectionsActivity::class.java).apply{putExtra("sessionId",sess.id)}) })
        } else {
            root.addView(sectionLabelText("What you'll do"))
            val exs=db.getExercises(routine.id)
            for((label,type) in listOf("Warm Up" to "Warmup","Exercise" to "Main","Cool Down" to "Stretch")){
                val list=exs.filter{it.type.equals(type,true)}
                val colorHex=when(type){"Warmup"->"#D97706";"Stretch"->"#0D9488";else->"#2563EB"}
                val card = cardLayout()
                val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row2.addView(View(this).apply { setBackgroundColor(Color.parseColor(colorHex)); layoutParams = LinearLayout.LayoutParams(dp(5), LinearLayout.LayoutParams.MATCH_PARENT).apply { setMargins(0, dp(2), dp(12), dp(2)) } })
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
                col.addView(makeText("$label  •  ${list.size} activities",16f,true,Color.parseColor(colorHex)))
                for(e in list){
                    val r=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
                    r.addView(equipmentBadge(e.equipment))
                    r.addView(makeText(e.name,13f,false))
                    col.addView(r)
                    if(e.variation.isNotBlank()) col.addView(caption(e.variation))
                }
                row2.addView(col); card.addView(row2)
                root.addView(card)
            }
        }
    }
}
