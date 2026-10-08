package com.mahesh.workouttracker

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SessionActivity : AppCompatActivity() {
    private lateinit var db: DbHelper
    private var sessionId: Long = -1
    private var timer: CountDownTimer? = null
    private var timerDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = DbHelper(this)
        sessionId = intent.getLongExtra("sessionId", -1)
        if (sessionId < 0) { finish(); return }
        render()
    }
    override fun onDestroy() { super.onDestroy(); timer?.cancel(); timerDialog?.dismiss() }

    private fun render() {
        val session = db.getSession(sessionId) ?: run { finish(); return }
        val unit = WeekManager.unit(this)
        val root = rootLayout()
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(topBar(session.routineName, "${DateUtil.display(session.date)} • Week ${session.weekNumber}"))

        // Header with larger body map
        val header = cardLayout("#2563EB")
        val hRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val allMuscles = linkedSetOf<String>()
        val exs0 = db.getSessionExercises(sessionId)
        for (e in exs0) allMuscles.addAll(DbHelper.parseMuscles(e.targetMuscles, e.name))
        hRow.addView(BodyMapView(this, allMuscles, true).apply { layoutParams = LinearLayout.LayoutParams(dp(110), dp(138)) })
        val hInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(10),0,0,0) }
        hInfo.addView(makeText(if (session.completed) "Workout (Completed)" else "Workout of the Day", 12f, false, Theme.textSecondary))
        hInfo.addView(makeText(session.routineName, 21f, true))
        hInfo.addView(makeText("${session.focus}\n${DateUtil.display(session.date)} • Week ${session.weekNumber}\nTargets: ${musclesLabel(allMuscles)}", 12f, false, Theme.textSecondary))
        hRow.addView(hInfo); header.addView(hRow); root.addView(header)

        // Top actions (reachable without scrolling)
        addActionButtons(root, session.completed)

        val exs = exs0
        if (exs.isEmpty()) root.addView(makeText("No exercises in this session.", 14f))
        var lastSection = ""
        for ((idx, ex) in exs.withIndex()) {
            val section = when(ex.type.lowercase()){ "warmup"->"Warm Up"; "stretch"->"Cool Down"; else->"Exercise" }
            if (section != lastSection) {
                root.addView(sectionHeader(section, when(section){"Warm Up"->"get blood flowing"; "Cool Down"->"cool down & relax"; else->"main work"}))
                lastSection = section
            }
            val card = cardLayout()
            // Title row with mini body map + badge
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val exMuscles = DbHelper.parseMuscles(ex.targetMuscles, ex.name)
            if (exMuscles.isNotEmpty()) top.addView(BodyMapView(this, exMuscles).apply { layoutParams = LinearLayout.LayoutParams(dp(52), dp(66)) })
            val tInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setPadding(dp(8),0,0,0) }
            val titleTv = makeText("${idx+1}. ${ex.name}", 16f, true)
            tInfo.addView(titleTv)
            val badgeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            badgeRow.addView(equipmentBadge(ex.equipment))
            badgeRow.addView(makeText(ex.type, 11f, true, Color.parseColor("#92400E")))
            tInfo.addView(badgeRow)
            if (ex.variation.isNotBlank()) tInfo.addView(makeText(ex.variation, 12f, false, Theme.textSecondary))
            if (exMuscles.isNotEmpty()) tInfo.addView(makeText("Targets: ${musclesLabel(exMuscles)}", 11f, false, Color.parseColor("#0369A1")))
            // Last performed
            val last = db.lastPerformedForExercise(ex.name, sessionId)
            if (last != null) {
                val summary = last.sets.joinToString(", ") { s -> if (s.isBodyweight) "BW x${s.reps.ifBlank{"?"}}" else "${s.weight.ifBlank{"?"}} $unit x${s.reps.ifBlank{"?"}}" }
                val eff = if(last.effort.isNotBlank()) " • ${last.effort} — ${Beginner.effortSuggestion(last.effort,unit)}" else ""
                tInfo.addView(makeText("Last done: ${DateUtil.display(last.date)}  •  $summary$eff", 12f, false, Color.parseColor("#15803D")))
            } else {
                tInfo.addView(makeText("First time — no previous record", 12f, false, Theme.textSecondary))
            }
            top.addView(tInfo); card.addView(top)
            if (ex.youtubeUrl.isNotBlank()) card.addView(makeButton("▶  Watch Form Video") { openUrl(this, ex.youtubeUrl) })
            card.addView(makeText("Do it: ${doItLine(db.getSets(ex.id))}",14f,true,Color.parseColor("#92400E")))
            card.addView(makeText("What you'll feel: ${musclesLabel(exMuscles)}",12f,false,Color.parseColor("#0369A1")))
            if (db.effectiveStatus(ex)=="skipped") card.addView(makeText("↷ Skipped",13f,true,Color.parseColor("#92400E")))
            if (Beginner.beginnerMode(this)) {
                val posture = ex.postureCheck.ifBlank{ DbHelper.postureForName(ex.name, ex.type) }
                if (posture.isNotBlank()) { card.addView(makeText("✓ Check your posture:",14f,true,Color.parseColor("#15803D"))); for(c in posture.split(";").map{it.trim()}.filter{it.isNotEmpty()}) card.addView(makeText("☐  $c",12f,false)) }
                val cues = ex.cues.ifBlank{DbHelper.cuesForName(ex.name,ex.equipment,ex.type)}
                if (cues.isNotBlank()) for(c in cues.split(";").map{it.trim()}.filter{it.isNotEmpty()}) card.addView(makeText("•  $c",12f,false,Theme.textSecondary))
            }

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (idx > 0) row.addView(makeSmallButton("↑ Up") { db.swapSessionExerciseOrder(ex, exs[idx-1]); render() })
            if (idx < exs.size-1) row.addView(makeSmallButton("↓ Down") { db.swapSessionExerciseOrder(ex, exs[idx+1]); render() })
            if (row.childCount>0) card.addView(row)

            val sets = db.getSets(ex.id)
            strike(titleTv, sets.isNotEmpty() && sets.all { it.isDone })
            for (set in sets) {
                val sRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)); background = roundedBg("#EEF2F7", 12); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, dp(4)) } }
                sRow.addView(TextView(this).apply { text="Set ${set.setNumber}"; setTextColor(Theme.textPrimary); textSize=13f; layoutParams=LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT) })
                val wEt = EditText(this).apply { setText(set.weight); hint="wt"; styleEditText(this); textSize=13f; isEnabled=!set.isBodyweight; layoutParams=LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT) }
                val rEt = EditText(this).apply { setText(set.reps); hint="reps"; styleEditText(this); textSize=13f; layoutParams=LinearLayout.LayoutParams(dp(62), LinearLayout.LayoutParams.WRAP_CONTENT) }
                val bwCb = CheckBox(this).apply { text="BW"; setTextColor(Theme.textSecondary); textSize=12f; isChecked=set.isBodyweight; minHeight=dp(44) }
                val doneCb = CheckBox(this).apply { text="Done"; setTextColor(Theme.textPrimary); textSize=13f; isChecked=set.isDone; minHeight=dp(48); minWidth=dp(76) }
                wEt.addTextChangedListener(simpleWatcher { db.updateSet(set.copy(weight=it, isBodyweight=bwCb.isChecked, isDone=doneCb.isChecked)) })
                rEt.addTextChangedListener(simpleWatcher { db.updateSet(set.copy(weight=wEt.text.toString(), reps=it, isBodyweight=bwCb.isChecked, isDone=doneCb.isChecked)) })
                bwCb.setOnCheckedChangeListener { _, checked -> wEt.isEnabled=!checked; db.updateSet(set.copy(weight=wEt.text.toString(), reps=rEt.text.toString(), isBodyweight=checked, isDone=doneCb.isChecked)) }
                doneCb.setOnCheckedChangeListener { _, checked ->
                    db.updateSet(set.copy(weight=wEt.text.toString(), reps=rEt.text.toString(), isBodyweight=bwCb.isChecked, isDone=checked))
                    db.syncExerciseStatus(ex.id)
                    if (checked) startRestTimer()
                    render()
                }
                val wStep = if(unit=="kg") 2.5 else 5.0
                sRow.addView(makeSmallButton("−"){ val v=(wEt.text.toString().toDoubleOrNull()?:0.0-wStep).coerceAtLeast(0.0); wEt.setText(if(v%1.0==0.0) v.toInt().toString() else v.toString()); db.updateSet(set.copy(weight=wEt.text.toString(), isBodyweight=false, isDone=doneCb.isChecked)) })
                sRow.addView(wEt)
                sRow.addView(makeSmallButton("+"){ val v=(wEt.text.toString().toDoubleOrNull()?:0.0)+wStep; wEt.setText(if(v%1.0==0.0) v.toInt().toString() else v.toString()); db.updateSet(set.copy(weight=wEt.text.toString(), isBodyweight=false, isDone=doneCb.isChecked)) })
                sRow.addView(TextView(this).apply { text=" $unit × "; setTextColor(Theme.textSecondary) })
                sRow.addView(makeSmallButton("−"){ val v=((rEt.text.toString().filter{it.isDigit()}.toIntOrNull()?:0)-1).coerceAtLeast(0); rEt.setText(v.toString()) })
                sRow.addView(rEt)
                sRow.addView(makeSmallButton("+"){ val v=(rEt.text.toString().filter{it.isDigit()}.toIntOrNull()?:0)+1; rEt.setText(v.toString()) })
                sRow.addView(bwCb); sRow.addView(doneCb)
                card.addView(sRow)
            }
            val setBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            setBtnRow.addView(makeSmallButton("+ Add Set") { db.addSet(ex.id); db.syncExerciseStatus(ex.id); render() })
            setBtnRow.addView(makeSmallButton("− Remove Set") { db.removeLastSet(ex.id); db.syncExerciseStatus(ex.id); render() })
            card.addView(setBtnRow)
            root.addView(card)
        }
        addActionButtons(root, session.completed, bottom = true)
    }

    private fun addActionButtons(root: LinearLayout, completed: Boolean, bottom: Boolean = false) {
        val session = db.getSession(sessionId) ?: return
        root.addView(makeButton("💾 Save Order as Default") {
            db.saveSessionOrderAsDefault(sessionId, session.routineId); Toast.makeText(this,"Saved as default order",Toast.LENGTH_SHORT).show()
        })
        if (!completed) {
            root.addView(makeButton("✅ Finish Workout") {
                AlertDialog.Builder(this).setTitle("Finish workout?").setMessage("Mark as completed? Unchecked sets stay as-is in history/CSV.")
                    .setPositiveButton("Finish"){_,_-> db.setSessionCompleted(sessionId,true); startActivity(android.content.Intent(this,SummaryActivity::class.java).apply{putExtra("sessionId",sessionId)}); finish()}.setNegativeButton("Cancel",null).show()
            })
        } else {
            root.addView(makeButton("↩ Re-open (mark In Progress)") { db.setSessionCompleted(sessionId,false); render() })
        }
        root.addView(makeButton("Save & Exit") { finish() })
        if (bottom) root.addView(makeButton("🗑 Discard Session") {
            AlertDialog.Builder(this).setTitle("Discard session?").setMessage("Delete this session and its sets?")
                .setPositiveButton("Discard"){_,_-> db.deleteSession(sessionId); finish()}.setNegativeButton("Cancel",null).show()
        })
    }
    private fun simpleWatcher(onChange:(String)->Unit): TextWatcher = object: TextWatcher {
        override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
        override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
        override fun afterTextChanged(s:Editable?){ onChange(s?.toString()?:"") }
    }
    private fun startRestTimer() {
        timer?.cancel(); timerDialog?.dismiss()
        val tv = TextView(this).apply { text="60"; textSize=34f; setTextColor(Theme.textPrimary); setPadding(dp(24),dp(24),dp(24),dp(24)); gravity=Gravity.CENTER }
        val dlg = AlertDialog.Builder(this).setTitle("Rest 60 sec").setView(tv).setNegativeButton("Skip"){_,_->timer?.cancel()}.setPositiveButton("Cancel Timer"){_,_->timer?.cancel()}.create()
        timerDialog=dlg; dlg.show()
        timer = object: CountDownTimer(60000,1000){ override fun onTick(ms:Long){tv.text="${ms/1000}"} override fun onFinish(){tv.text="Done!"; Toast.makeText(this@SessionActivity,"Rest done",Toast.LENGTH_SHORT).show()} }.start()
    }
}
