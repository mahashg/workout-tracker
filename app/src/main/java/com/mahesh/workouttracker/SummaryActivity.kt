package com.mahesh.workouttracker

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity

class SummaryActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var sessionId: Long=-1
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); db=DbHelper(this); sessionId=intent.getLongExtra("sessionId",-1); if(sessionId<0){finish();return}
        val session=db.getSession(sessionId) ?: run{finish();return}
        val exs=db.getSessionExercises(sessionId)
        val root=rootLayout(); setContentView(ScrollView(this).apply{addView(root)})
        root.addView(topBar("Workout Summary", "${DateUtil.display(session.date)} • Week ${session.weekNumber}"))

        val celeb=heroCard()
        celeb.addView(makeText("Workout complete!",26f,true,android.graphics.Color.WHITE).apply { gravity = Gravity.CENTER })
        celeb.addView(makeText("${session.routineName}\nNice work showing up and getting it done.",13f,false,android.graphics.Color.parseColor("#DBEAFE")).apply { gravity = Gravity.CENTER })
        root.addView(celeb)

        val done=exs.count{db.effectiveStatus(it)=="done"}; val skipped=exs.count{db.effectiveStatus(it)=="skipped"}; val setsDone=exs.sumOf{db.getSets(it.id).count{s->s.isDone}}; val setsTotal=exs.sumOf{db.getSets(it.id).size}
        val muscles=linkedSetOf<String>(); for(ex in exs) if(db.effectiveStatus(ex)=="done") muscles.addAll(DbHelper.parseMuscles(ex.targetMuscles,ex.name))
        val card=cardLayout("#15803D")
        card.addView(overline("Today you trained"))
        card.addView(cardTitle(musclesLabel(muscles)))
        card.addView(BodyMapView(this,muscles,true).apply{layoutParams=LinearLayout.LayoutParams(dp(150),dp(185)).apply{gravity=Gravity.CENTER_HORIZONTAL}})
        val stats=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        fun stat(v:String,l:String): LinearLayout { val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; layoutParams=LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f)}; c.addView(makeText(v,22f,true,Theme.success).apply{gravity=Gravity.CENTER}); c.addView(caption(l).apply{gravity=Gravity.CENTER}); return c }
        stats.addView(stat("$done","exercises done")); stats.addView(stat("$setsDone/$setsTotal","sets done")); stats.addView(stat("$skipped","skipped"))
        if (session.startedAt > 0) stats.addView(stat(WorkoutTimer.formatDuration(session.elapsedSec),"time"))
        card.addView(stats)
        root.addView(card)

        val perCard=cardLayout()
        perCard.addView(overline("By section"))
        for(label in listOf("Warm Up" to "Warmup","Exercise" to "Main","Cool Down" to "Stretch")){
            val list=exs.filter{it.type.equals(label.second,true)}; if(list.isEmpty()) continue
            perCard.addView(bodyText("${label.first}: ${list.count{db.effectiveStatus(it)=="done"}} done, ${list.count{db.effectiveStatus(it)=="skipped"}} skipped, ${list.count{db.effectiveStatus(it)=="pending"}} not done"))
        }
        root.addView(perCard)
        root.addView(makeText("Every workout counts — see you next session.",13f,false,Theme.textSecondary))
        root.addView(makeButton("Done"){ finish() })
        root.addView(makeSecondaryButton("View as list"){ startActivity(Intent(this,SessionActivity::class.java).apply{putExtra("sessionId",sessionId)}); finish() })
    }
}
