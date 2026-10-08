package com.mahesh.workouttracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class Routine(val id: Long, val name: String, val focus: String, val weekday: Int, val youtubeUrl: String, val notes: String)
data class Exercise(val id: Long, val routineId: Long, val name: String, val type: String, val equipment: String, val defaultSets: Int, val targetReps: String, val defaultWeight: String, val variation: String, val youtubeUrl: String, val sortOrder: Int, val targetMuscles: String = "", val cues: String = "", val postureCheck: String = "")
data class SessionInfo(val id: Long, val routineId: Long, val routineName: String, val focus: String, val date: String, val weekNumber: Int, val weekStart: String, val completed: Boolean)
data class SessionExercise(val id: Long, val sessionId: Long, val originExerciseId: Long, val name: String, val type: String, val equipment: String, val variation: String, val youtubeUrl: String, val sortOrder: Int, val targetMuscles: String = "", val status: String = "pending", val cues: String = "", val effort: String = "", val postureCheck: String = "")
data class SessionSet(val id: Long, val sessionExerciseId: Long, val setNumber: Int, val weight: String, val reps: String, val isBodyweight: Boolean, val isDone: Boolean)
data class LastPerformed(val date: String, val sets: List<SessionSet>, val effort: String = "")

object DateUtil {
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    fun fmt(c: Calendar): String = fmt.format(c.time)
    fun parse(s: String): Calendar {
        val c = Calendar.getInstance()
        try { c.time = fmt.parse(s)!! } catch (e: Exception) {}
        zero(c); return c
    }
    fun zero(c: Calendar) { c.set(Calendar.HOUR_OF_DAY,0); c.set(Calendar.MINUTE,0); c.set(Calendar.SECOND,0); c.set(Calendar.MILLISECOND,0) }
    fun today(): Calendar { val c = Calendar.getInstance(); zero(c); return c }
    fun sundayOf(input: Calendar): Calendar {
        val c = input.clone() as Calendar; zero(c)
        val dow = c.get(Calendar.DAY_OF_WEEK)
        c.add(Calendar.DAY_OF_YEAR, -(dow - Calendar.SUNDAY))
        return c
    }
    fun saturdayOf(sunday: Calendar): Calendar { val c = sunday.clone() as Calendar; c.add(Calendar.DAY_OF_YEAR,6); return c }
    fun display(dateStr: String): String {
        return try {
            val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val outFmt = SimpleDateFormat("EEE MMM d", Locale.US)
            outFmt.format(inFmt.parse(dateStr)!!)
        } catch (e: Exception) { dateStr }
    }
    fun weekdayIndex(c: Calendar): Int = c.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
    val dayNames = arrayOf("Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Unassigned")
    fun dayName(weekday: Int): String = if (weekday in 0..6) dayNames[weekday] else "Unassigned"
}

class DbHelper(private val appContext: Context) : SQLiteOpenHelper(appContext, "workout.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE routines(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT,focus TEXT,weekday INTEGER,youtubeUrl TEXT,notes TEXT)")
        db.execSQL("CREATE TABLE exercises(id INTEGER PRIMARY KEY AUTOINCREMENT,routineId INTEGER,name TEXT,type TEXT,equipment TEXT,defaultSets INTEGER,targetReps TEXT,defaultWeight TEXT,variation TEXT,youtubeUrl TEXT,sortOrder INTEGER,targetMuscles TEXT,cues TEXT,postureCheck TEXT)")
        db.execSQL("CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT,routineId INTEGER,routineName TEXT,focus TEXT,date TEXT,weekNumber INTEGER,weekStart TEXT,completed INTEGER)")
        db.execSQL("CREATE TABLE session_exercises(id INTEGER PRIMARY KEY AUTOINCREMENT,sessionId INTEGER,originExerciseId INTEGER,name TEXT,type TEXT,equipment TEXT,variation TEXT,youtubeUrl TEXT,sortOrder INTEGER,targetMuscles TEXT,status TEXT,cues TEXT,effort TEXT,postureCheck TEXT)")
        db.execSQL("CREATE TABLE session_sets(id INTEGER PRIMARY KEY AUTOINCREMENT,sessionExerciseId INTEGER,setNumber INTEGER,weight TEXT,reps TEXT,isBodyweight INTEGER,isDone INTEGER)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v2: add targetMuscles + status only. Preserve all existing rows.
            try { db.execSQL("ALTER TABLE exercises ADD COLUMN targetMuscles TEXT") } catch (e: Exception) {}
            try { db.execSQL("ALTER TABLE session_exercises ADD COLUMN targetMuscles TEXT") } catch (e: Exception) {}
            try { db.execSQL("ALTER TABLE session_exercises ADD COLUMN status TEXT") } catch (e: Exception) {}
            try { db.execSQL("ALTER TABLE exercises ADD COLUMN cues TEXT") } catch (e: Exception) {}
            try { db.execSQL("ALTER TABLE session_exercises ADD COLUMN cues TEXT") } catch (e: Exception) {}
            try { db.execSQL("ALTER TABLE session_exercises ADD COLUMN effort TEXT") } catch (e: Exception) {}
            try { db.execSQL("ALTER TABLE exercises ADD COLUMN postureCheck TEXT") } catch (e: Exception) {}
            try { db.execSQL("ALTER TABLE session_exercises ADD COLUMN postureCheck TEXT") } catch (e: Exception) {}
        }
    }

    fun getRoutines(): List<Routine> {
        val list = mutableListOf<Routine>()
        readableDatabase.rawQuery("SELECT id,name,focus,weekday,youtubeUrl,notes FROM routines ORDER BY CASE WHEN weekday=-1 THEN 7 ELSE weekday END, name", null).use { c ->
            while (c.moveToNext()) list.add(Routine(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getInt(3), c.getString(4) ?: "", c.getString(5) ?: ""))
        }
        return list
    }
    fun getRoutine(id: Long): Routine? = getRoutines().firstOrNull { it.id == id }
    fun insertRoutine(r: Routine): Long {
        val v = ContentValues().apply { put("name",r.name); put("focus",r.focus); put("weekday",r.weekday); put("youtubeUrl",r.youtubeUrl); put("notes",r.notes) }
        return writableDatabase.insert("routines", null, v)
    }
    fun updateRoutine(r: Routine) {
        val v = ContentValues().apply { put("name",r.name); put("focus",r.focus); put("weekday",r.weekday); put("youtubeUrl",r.youtubeUrl); put("notes",r.notes) }
        writableDatabase.update("routines", v, "id=?", arrayOf(r.id.toString()))
    }
    fun deleteRoutine(id: Long) {
        val db = writableDatabase; db.delete("exercises","routineId=?",arrayOf(id.toString())); db.delete("routines","id=?",arrayOf(id.toString()))
    }
    fun deleteExercisesForRoutine(routineId: Long) { writableDatabase.delete("exercises","routineId=?",arrayOf(routineId.toString())) }

    private fun exerciseFrom(c: android.database.Cursor): Exercise = Exercise(c.getLong(0),c.getLong(1),c.getString(2)?:"",c.getString(3)?:"Main",c.getString(4)?:"None",c.getInt(5),c.getString(6)?:"",c.getString(7)?:"",c.getString(8)?:"",c.getString(9)?:"",c.getInt(10), if (c.columnCount>11) c.getString(11)?:"" else "", if (c.columnCount>12) c.getString(12)?:"" else "", if (c.columnCount>13) c.getString(13)?:"" else "")
    fun getExercises(routineId: Long): List<Exercise> {
        val list = mutableListOf<Exercise>()
        readableDatabase.rawQuery("SELECT id,routineId,name,type,equipment,defaultSets,targetReps,defaultWeight,variation,youtubeUrl,sortOrder,targetMuscles,cues,postureCheck FROM exercises WHERE routineId=? ORDER BY sortOrder,id", arrayOf(routineId.toString())).use { c ->
            while (c.moveToNext()) list.add(exerciseFrom(c))
        }
        return list
    }
    fun getExercise(id: Long): Exercise? {
        readableDatabase.rawQuery("SELECT id,routineId,name,type,equipment,defaultSets,targetReps,defaultWeight,variation,youtubeUrl,sortOrder,targetMuscles,cues,postureCheck FROM exercises WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) return exerciseFrom(c)
        }
        return null
    }
    fun insertExercise(e: Exercise): Long {
        val v = ContentValues().apply { put("routineId",e.routineId); put("name",e.name); put("type",e.type); put("equipment",e.equipment); put("defaultSets",e.defaultSets); put("targetReps",e.targetReps); put("defaultWeight",e.defaultWeight); put("variation",e.variation); put("youtubeUrl",e.youtubeUrl); put("sortOrder",e.sortOrder); put("targetMuscles",e.targetMuscles); put("cues",e.cues); put("postureCheck",e.postureCheck) }
        return writableDatabase.insert("exercises", null, v)
    }
    fun updateExercise(e: Exercise) {
        val v = ContentValues().apply { put("routineId",e.routineId); put("name",e.name); put("type",e.type); put("equipment",e.equipment); put("defaultSets",e.defaultSets); put("targetReps",e.targetReps); put("defaultWeight",e.defaultWeight); put("variation",e.variation); put("youtubeUrl",e.youtubeUrl); put("sortOrder",e.sortOrder); put("targetMuscles",e.targetMuscles); put("cues",e.cues); put("postureCheck",e.postureCheck) }
        writableDatabase.update("exercises", v, "id=?", arrayOf(e.id.toString()))
    }
    fun deleteExercise(id: Long) { writableDatabase.delete("exercises","id=?",arrayOf(id.toString())) }
    fun swapExerciseOrder(a: Exercise, b: Exercise) {
        val db = writableDatabase
        var v = ContentValues().apply { put("sortOrder", b.sortOrder) }; db.update("exercises", v, "id=?", arrayOf(a.id.toString()))
        v = ContentValues().apply { put("sortOrder", a.sortOrder) }; db.update("exercises", v, "id=?", arrayOf(b.id.toString()))
    }

    fun getSessions(): List<SessionInfo> {
        val list = mutableListOf<SessionInfo>()
        readableDatabase.rawQuery("SELECT id,routineId,routineName,focus,date,weekNumber,weekStart,completed FROM sessions ORDER BY date DESC, id DESC", null).use { c ->
            while (c.moveToNext()) list.add(SessionInfo(c.getLong(0),c.getLong(1),c.getString(2)?:"",c.getString(3)?:"",c.getString(4)?:"",c.getInt(5),c.getString(6)?:"",c.getInt(7)==1))
        }
        return list
    }
    fun getSession(id: Long): SessionInfo? = getSessions().firstOrNull { it.id == id }
    fun hasCompletedInWeek(weekStart: String): Boolean {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM sessions WHERE weekStart=? AND completed=1", arrayOf(weekStart)).use { c -> if (c.moveToFirst()) return c.getInt(0) > 0 }
        return false
    }
    fun sessionsInWeek(weekStart: String): List<SessionInfo> = getSessions().filter { it.weekStart == weekStart }
    fun inProgressSessions(): List<SessionInfo> = getSessions().filter { !it.completed }
    fun findSession(weekStart: String, routineId: Long, completed: Boolean?): SessionInfo? {
        return getSessions().firstOrNull { it.weekStart == weekStart && it.routineId == routineId && (completed == null || it.completed == completed) }
    }
    fun createSession(routine: Routine, weekNumber: Int, weekStart: String): Long {
        val db = writableDatabase
        val todayStr = DateUtil.fmt(DateUtil.today())
        val v = ContentValues().apply { put("routineId",routine.id); put("routineName",routine.name); put("focus",routine.focus); put("date",todayStr); put("weekNumber",weekNumber); put("weekStart",weekStart); put("completed",0) }
        val sessionId = db.insert("sessions", null, v)
        val exs = getExercises(routine.id)
        for ((idx, e) in exs.withIndex()) {
            val ev = ContentValues().apply { put("sessionId",sessionId); put("originExerciseId",e.id); put("name",e.name); put("type",e.type); put("equipment",e.equipment); put("variation",e.variation); put("youtubeUrl",e.youtubeUrl); put("sortOrder",idx); put("targetMuscles",e.targetMuscles); put("status","pending"); put("cues",e.cues); put("effort",""); put("postureCheck",e.postureCheck) }
            val seId = db.insert("session_exercises", null, ev)
            val recentWeight = mostRecentWeight(e.name) ?: e.defaultWeight
            val isBw = e.equipment.equals("Bodyweight", true) || e.defaultWeight.equals("BW", true)
            val setsCount = if (e.type.equals("Warmup",true) || e.type.equals("Stretch",true)) 1 else maxOf(1, e.defaultSets)
            for (s in 1..setsCount) {
                val sv = ContentValues().apply { put("sessionExerciseId",seId); put("setNumber",s); put("weight", if (isBw) "" else recentWeight); put("reps", e.targetReps); put("isBodyweight", if (isBw) 1 else 0); put("isDone",0) }
                db.insert("session_sets", null, sv)
            }
        }
        return sessionId
    }
    fun mostRecentWeight(exerciseName: String): String? {
        val sql = "SELECT ss.weight FROM session_sets ss JOIN session_exercises se ON ss.sessionExerciseId=se.id JOIN sessions s ON se.sessionId=s.id WHERE lower(trim(se.name))=lower(trim(?)) AND ss.weight IS NOT NULL AND ss.weight != '' ORDER BY s.date DESC, s.id DESC, ss.setNumber ASC LIMIT 1"
        readableDatabase.rawQuery(sql, arrayOf(exerciseName)).use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }
    fun lastPerformedForExercise(exerciseName: String, excludeSessionId: Long): LastPerformed? {
        val key = exerciseName.trim().lowercase(Locale.US)
        for (s in getSessions()) {
            if (s.id == excludeSessionId) continue
            val exs = getSessionExercises(s.id).filter { it.name.trim().lowercase(Locale.US) == key }
            for (ex in exs) {
                val doneSets = getSets(ex.id).filter { it.isDone }
                if (doneSets.isNotEmpty()) return LastPerformed(s.date, doneSets, ex.effort)
            }
        }
        return null
    }
    fun getSessionExercises(sessionId: Long): List<SessionExercise> {
        val list = mutableListOf<SessionExercise>()
        readableDatabase.rawQuery("SELECT id,sessionId,originExerciseId,name,type,equipment,variation,youtubeUrl,sortOrder,targetMuscles,status,cues,effort,postureCheck FROM session_exercises WHERE sessionId=? ORDER BY sortOrder,id", arrayOf(sessionId.toString())).use { c ->
            while (c.moveToNext()) list.add(SessionExercise(c.getLong(0),c.getLong(1),c.getLong(2),c.getString(3)?:"",c.getString(4)?:"Main",c.getString(5)?:"None",c.getString(6)?:"",c.getString(7)?:"",c.getInt(8), if(c.columnCount>9) c.getString(9)?:"" else "", if(c.columnCount>10) c.getString(10)?:"pending" else "pending", if(c.columnCount>11) c.getString(11)?:"" else "", if(c.columnCount>12) c.getString(12)?:"" else "", if(c.columnCount>13) c.getString(13)?:"" else ""))
        }
        return list
    }
    fun getSets(sessionExerciseId: Long): List<SessionSet> {
        val list = mutableListOf<SessionSet>()
        readableDatabase.rawQuery("SELECT id,sessionExerciseId,setNumber,weight,reps,isBodyweight,isDone FROM session_sets WHERE sessionExerciseId=? ORDER BY setNumber,id", arrayOf(sessionExerciseId.toString())).use { c ->
            while (c.moveToNext()) list.add(SessionSet(c.getLong(0),c.getLong(1),c.getInt(2),c.getString(3)?:"",c.getString(4)?:"",c.getInt(5)==1,c.getInt(6)==1))
        }
        return list
    }
    fun updateSet(s: SessionSet) {
        val old = getSets(s.sessionExerciseId).firstOrNull { it.id == s.id }
        val v = ContentValues().apply { put("setNumber",s.setNumber); put("weight",s.weight); put("reps",s.reps); put("isBodyweight", if(s.isBodyweight)1 else 0); put("isDone", if(s.isDone)1 else 0) }
        writableDatabase.update("session_sets", v, "id=?", arrayOf(s.id.toString()))
        if (old != null && old.isDone && !s.isDone) { /* no XP removal - forgiving, no punishment */ }
    }
    fun addSet(sessionExerciseId: Long): Long {
        val sets = getSets(sessionExerciseId)
        val next = (sets.maxOfOrNull { it.setNumber } ?: 0) + 1
        val last = sets.lastOrNull()
        val v = ContentValues().apply { put("sessionExerciseId",sessionExerciseId); put("setNumber",next); put("weight", last?.weight ?: ""); put("reps", last?.reps ?: ""); put("isBodyweight", if(last?.isBodyweight==true)1 else 0); put("isDone",0) }
        return writableDatabase.insert("session_sets", null, v)
    }
    fun removeLastSet(sessionExerciseId: Long) {
        val sets = getSets(sessionExerciseId); if (sets.size <= 1) return
        writableDatabase.delete("session_sets","id=?",arrayOf(sets.last().id.toString()))
    }
    fun swapSessionExerciseOrder(a: SessionExercise, b: SessionExercise) {
        val db = writableDatabase
        var v = ContentValues().apply { put("sortOrder", b.sortOrder) }; db.update("session_exercises", v, "id=?", arrayOf(a.id.toString()))
        v = ContentValues().apply { put("sortOrder", a.sortOrder) }; db.update("session_exercises", v, "id=?", arrayOf(b.id.toString()))
    }
    fun saveSessionOrderAsDefault(sessionId: Long, routineId: Long) {
        val ses = getSessionExercises(sessionId)
        val db = writableDatabase
        for ((idx, se) in ses.withIndex()) {
            if (se.originExerciseId > 0) {
                val v = ContentValues().apply { put("sortOrder", idx) }
                db.update("exercises", v, "id=?", arrayOf(se.originExerciseId.toString()))
            }
        }
    }
    fun setSessionCompleted(sessionId: Long, completed: Boolean) {
        val v = ContentValues().apply { put("completed", if(completed)1 else 0) }
        writableDatabase.update("sessions", v, "id=?", arrayOf(sessionId.toString()))
    }
    fun deleteSession(sessionId: Long) {
        val db = writableDatabase
        val exIds = getSessionExercises(sessionId).map { it.id }
        for (eid in exIds) db.delete("session_sets","sessionExerciseId=?",arrayOf(eid.toString()))
        db.delete("session_exercises","sessionId=?",arrayOf(sessionId.toString()))
        db.delete("sessions","id=?",arrayOf(sessionId.toString()))
    }
    fun setSessionExerciseStatus(sessionExerciseId: Long, status: String) {
        val before = getSessionExercisesForOne(sessionExerciseId)?.let { effectiveStatus(it) }
        val v = ContentValues().apply { put("status", status) }
        writableDatabase.update("session_exercises", v, "id=?", arrayOf(sessionExerciseId.toString()))
    }
    fun setSessionExerciseEffort(sessionExerciseId: Long, effort: String) {
        val v = ContentValues().apply { put("effort", effort) }
        writableDatabase.update("session_exercises", v, "id=?", arrayOf(sessionExerciseId.toString()))
    }
    fun swapSessionExercise(sessionExerciseId: Long, newName: String, newEquipment: String) {
        val ex = getSessionExercisesForOne(sessionExerciseId) ?: return
        val v = ContentValues().apply {
            put("name", newName); put("equipment", newEquipment)
            put("cues", cuesForName(newName, newEquipment, ex.type)); put("postureCheck", postureForName(newName, ex.type))
            put("variation", "Easier swap • " + ex.variation)
            put("status", "pending")
        }
        writableDatabase.update("session_exercises", v, "id=?", arrayOf(sessionExerciseId.toString()))
    }
    fun sectionHandledCount(sessionId: Long, type: String): Pair<Int,Int> {
        val list = getSessionExercises(sessionId).filter { it.type.equals(type, true) }
        return Pair(list.count { effectiveStatus(it) != "pending" }, list.size)
    }
    fun effectiveStatus(ex: SessionExercise): String {
        if (ex.status.equals("skipped", true)) return "skipped"
        val sets = getSets(ex.id)
        if (sets.isNotEmpty() && sets.all { it.isDone }) return "done"
        if (ex.status.equals("done", true) && sets.isEmpty()) return "done"
        return "pending"
    }
    fun syncExerciseStatus(sessionExerciseId: Long) {
        val ex = getSessionExercisesForOne(sessionExerciseId) ?: return
        if (ex.status.equals("skipped", true)) return
        val sets = getSets(sessionExerciseId)
        setSessionExerciseStatus(sessionExerciseId, if (sets.isNotEmpty() && sets.all { it.isDone }) "done" else "pending")
    }
    fun getSessionExercisesForOne(sessionExerciseId: Long): SessionExercise? {
        readableDatabase.rawQuery("SELECT id,sessionId FROM session_exercises WHERE id=?", arrayOf(sessionExerciseId.toString())).use { c ->
            if (c.moveToFirst()) {
                val sid = c.getLong(1)
                return getSessionExercises(sid).firstOrNull { it.id == sessionExerciseId }
            }
        }
        return null
    }
    fun routineExerciseCount(routineId: Long): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM exercises WHERE routineId=?", arrayOf(routineId.toString())).use { c -> if (c.moveToFirst()) return c.getInt(0) }
        return 0
    }
    fun routineMuscles(routineId: Long): Set<String> {
        val out = linkedSetOf<String>()
        for (e in getExercises(routineId)) out.addAll(parseMuscles(e.targetMuscles, e.name))
        return out
    }
    companion object {
        fun parseMuscles(targetMuscles: String, nameHint: String = ""): Set<String> {
            val out = linkedSetOf<String>()
            val raw = (targetMuscles.ifBlank { deriveMuscles(nameHint) }).lowercase(Locale.US)
            for (part in raw.split(",")) { val k = part.trim(); if (k.isNotEmpty()) out.add(k) }
            return out
        }
        fun deriveMuscles(text: String): String {
            val t = text.lowercase(Locale.US)
            val m = mutableListOf<String>()
            fun has(vararg keys: String) = keys.any { t.contains(it) }
            if (has("shoulder press","lateral raise","pike","halo","scap push","arm circle")) m.add("shoulders")
            if (has("shrug","trap","neck")) m.add("traps")
            if (has("chest","push-up","push up","pushup","doorway chest","floor press","diamond")) m.add("chest")
            if (has("tricep","pushdown")) m.add("triceps")
            if (has("bicep","curl")) m.add("biceps")
            if (has("lat pulldown","row","pull-up","pull up","scapula pull","cat-cow","child")) m.add("back")
            if (has("dead bug","plank","core","cobra")) m.add("core")
            if (has("leg press","goblet","lunge","extension","step-up","step up","wall sit","squat","quad")) m.add("quads")
            if (has("leg curl","romanian","swing","hamstring")) { m.add("hamstrings") }
            if (has("glute","kickback","hip thrust")) m.add("glutes")
            if (has("calf")) m.add("calves")
            return m.distinct().joinToString(",")
        }
        fun postureForName(name: String, type: String = "Main"): String {
            val t = name.lowercase(Locale.US)
            val checks: List<String> = when {
                t.contains("chest press") || t.contains("floor press") || t.contains("incline db press") -> listOf("Your back stays flat on the bench (or floor), not arched hard","Feet flat on the floor, planted and still","Wrists straight, in line with your forearms","Only your arms move - shoulders stay down away from ears")
                t.contains("overhead triceps") || t.contains("triceps extension") -> listOf("Keep your upper arm still and vertical, close to your head","Elbow pointing forward, in line with your body","Only your forearm moves - upper arm does not swing","Stand tall, ribs down, no leaning back")
                t.contains("pushdown") -> listOf("Elbows tucked at your sides and completely still","Upper arms vertical - only forearms move down","Stand tall, shoulders down away from ears","Wrist straight, grip firm")
                t.contains("push-up") || t.contains("push up") || t.contains("pushup") || t.contains("diamond") || t.contains("pike") -> listOf("Body in one straight line head-to-heels - hips not sagging or piked (unless Pike)","Hands under shoulders, fingers spread","Head neutral - look at floor, not forward","Only arms bend - torso stays rigid like a plank")
                t.contains("shoulder press") || t.contains("kb press") -> listOf("Feet hip-width, glutes squeezed, ribs down","Wrists straight over elbows as you press","Head moves slightly back then through - no shrugging to ears","Lower to shoulder height with control")
                t.contains("lateral raise") -> listOf("Stand tall, slight bend in elbows that stays fixed","Shoulders down away from ears the whole time","Lift only to shoulder height - thumbs level or slightly down","No swinging from hips - torso completely still")
                t.contains("pec deck") || t.contains("reverse pec") -> listOf("Back flat on pad, chest tall","Soft bend in elbows that stays fixed","Shoulders down and back - not rolled forward","Move from the shoulder joint only")
                t.contains("shrug") -> listOf("Stand tall, arms straight and still at sides","Lift shoulders straight up to ears - no rolling","Head neutral, chin level, no jutting forward","Pause at top - feel it in upper traps")
                t.contains("lat pulldown") -> listOf("Sit tall, chest up, slight lean back (not rocking)","Shoulders down away from ears before you pull","Pull to upper chest - elbows go down and back","Control all the way up to a full stretch")
                t.contains("row") || t.contains("inverted") -> listOf("Flat back - hinge at hips, spine long and neutral","Chest tall, shoulders down away from ears","Elbows track close to body, squeeze shoulder blades","No rounding of lower back at any point")
                t.contains("curl") -> listOf("Elbows fixed at your sides - they do not move forward","Upper arms completely still - only forearms curl","Stand tall, no swinging from hips or back","Lower all the way to straight arms slowly")
                t.contains("leg press") -> listOf("Whole foot flat on plate, heels never lift","Knees track exactly over toes - never cave inward","Lower back stays on pad - stop before it rounds","Feet shoulder-width, toes slightly out")
                t.contains("goblet") || t.contains("bodyweight squat") || (t.contains("squat") && !t.contains("wall")) -> listOf("Feet shoulder-width, toes slightly turned out","Chest up, flat back - look straight ahead","Knees track over toes - never cave inward","Weight in heels and mid-foot - heels stay down")
                t.contains("wall sit") -> listOf("Entire back flat against wall, head touching","Feet shoulder-width, knees directly over ankles","Thighs parallel to floor if you can","Hands off thighs - arms across chest")
                t.contains("extension") -> listOf("Knee joint lined up with machine pivot point","Back flat on pad, grip handles to stay still","No kicking - lift smoothly and pause at top","Lower slowly until weight nearly touches")
                t.contains("leg curl") -> listOf("Hips pressed into pad the whole time","Knees lined up with machine pivot","Curl smoothly - no jerking from lower back","Lower slowly to near-straight legs")
                t.contains("lunge") || t.contains("step-up") || t.contains("step up") -> listOf("Torso tall and upright - no leaning forward","Front knee tracks over toes - never caves inward","Whole front foot planted - push through heel","Back knee points down, hips square forward")
                t.contains("romanian") || t.contains("deadlift") || t.contains("swing") -> listOf("Feet hip-width, soft bend in knees that stays","Flat back the whole time - spine long, chest up","Push hips BACK - weight slides close to legs","Stand by squeezing glutes - no leaning back at top")
                t.contains("glute bridge") || t.contains("hip thrust") || t.contains("kickback") -> listOf("Feet flat hip-width, knees tracking over toes","Ribs down - do not arch lower back at top","Chin slightly tucked, eyes forward (bridge/thrust)","Squeeze glutes hard at top for 1 second")
                t.contains("dead bug") -> listOf("Entire lower back gently pressed into floor - no gap","Only opposite arm+leg move - torso completely still","Move very slowly - 3 seconds out, 3 back","Breathe out slowly as you reach")
                t.contains("plank") -> listOf("Body one straight line - hips not sagging or high","Elbows under shoulders, forearms flat","Squeeze glutes and thighs hard the whole time","Head neutral - look at floor, breathe steadily")
                t.contains("halo") -> listOf("Feet hip-width, glutes squeezed, ribs down","Bell circles close around head - elbows in","Head and torso completely still - only arms move","Move very slowly and controlled")
                t.contains("bike") || t.contains("incline walk") || t.contains("rowing") -> listOf("Sit/stand tall - no slouching or leaning on handles","Easy rhythm - you could hold a conversation","Smooth continuous movement - no coasting to a stop")
                t.contains("arm circle") -> listOf("Stand tall, arms out at shoulder height","Shoulders down away from ears","Start tiny circles, grow them gradually")
                t.contains("leg swing") -> listOf("Hold a support, stand tall on standing leg","Swing from hip - leg relaxed like a pendulum","Torso still - movement only in swinging leg")
                t.contains("cat-cow") || t.contains("cat cow") -> listOf("Hands under shoulders, knees under hips","Move spine slowly one segment at a time","Head follows spine - no forcing neck")
                t.contains("scap") -> listOf("Arms completely straight the whole time","Movement only from shoulder blades","Hang tall - no bending elbows to help")
                else -> if (type.equals("Stretch", true) || t.contains("stretch") || t.contains("pose")) listOf("Move gently until you feel a mild stretch - never force","Breathe slowly and relax into it - no bouncing","Keep posture tall and supported where you can")
                    else listOf("Stand/sit tall with a stable base before you start","Spine long and neutral - no rounding or hard arching","Move slowly and with control - no bouncing or jerking")
            }
            return checks.take(if (type.equals("Main", true)) 4 else 3).joinToString(";")
        }
        fun cuesForName(name: String, equipment: String = "", type: String = "Main"): String {
            val t = name.lowercase(Locale.US)
            val base: List<String> = when {
                t.contains("goblet squat") || (t.contains("squat") && !t.contains("wall")) -> listOf("Feet shoulder-width apart","Chest up, sit back and down","Push through your heels to stand")
                t.contains("wall sit") -> listOf("Back flat against the wall","Knees over ankles, thighs level if you can","Breathe steadily and hold")
                t.contains("leg press") -> listOf("Feet flat, shoulder-width on the plate","Knees track over your toes","Push through heels, don't lock knees")
                t.contains("leg extension") -> listOf("Knees lined up with the machine pivot","Lift with control, pause at top","Lower slowly, no swinging")
                t.contains("leg curl") -> listOf("Hips stay down on the pad","Curl heels toward you with control","Lower slowly all the way")
                t.contains("lunge") -> listOf("Long step, torso tall","Front knee tracks over toes","Push through front heel to stand")
                t.contains("step-up") || t.contains("step up") -> listOf("Whole foot on the step","Push through that heel to stand tall","Step down slowly and controlled")
                t.contains("romanian") || t.contains("deadlift") -> listOf("Soft knees, push hips back","Flat back, weight close to legs","Stand tall by squeezing glutes")
                t.contains("swing") -> listOf("Hinge at hips, not a squat","Snap hips, let the bell float","Keep back flat and core tight")
                t.contains("glute bridge") || t.contains("hip thrust") -> listOf("Feet flat, knees bent","Push through heels, ribs down","Squeeze glutes hard at the top")
                t.contains("kickback") -> listOf("Hips still, core tight","Kick heel back, not up","Squeeze glute, lower slowly")
                t.contains("shoulder press") || t.contains("kb press") -> listOf("Ribs down, glutes tight","Press straight overhead","Lower slowly to shoulder height")
                t.contains("lateral raise") -> listOf("Slight bend in elbows","Lift to shoulder height only","Lower slowly, no swinging")
                t.contains("reverse pec") || t.contains("pec deck") -> listOf("Chest tall, soft elbows","Open arms wide with control","Squeeze shoulder blades together")
                t.contains("shrug") -> listOf("Arms straight, stand tall","Lift shoulders to ears","Pause, then lower slowly")
                t.contains("pike") -> listOf("Hips high, head between arms","Bend elbows, head toward floor","Press floor away to straighten")
                t.contains("chest press") -> listOf("Feet flat, back on pad","Push handles forward smoothly","Lower slowly to chest stretch")
                t.contains("floor press") -> listOf("Upper arms rest lightly on floor","Press up until arms straight","Lower slowly and controlled")
                t.contains("push-up") || t.contains("push up") || t.contains("pushup") -> listOf("Body in one straight line","Hands under shoulders","Lower with control, press up")
                t.contains("pushdown") -> listOf("Elbows tucked at your sides","Push down until arms straight","Control the way back up")
                t.contains("triceps extension") -> listOf("Elbows pointing forward, still","Lower weight behind head slowly","Straighten arms without flaring elbows")
                t.contains("diamond") -> listOf("Hands close under chest","Elbows track back, body straight","Press up with control")
                t.contains("lat pulldown") -> listOf("Chest tall, slight lean back","Pull bar to upper chest","Control the bar all the way up")
                t.contains("row") -> listOf("Flat back, chest tall","Pull to belly, squeeze shoulder blades","Lower slowly, arms straight")
                t.contains("curl") -> listOf("Elbows fixed at your sides","Curl up without swinging","Lower slowly all the way")
                t.contains("dead bug") -> listOf("Low back gently pressed to floor","Move opposite arm and leg slowly","Breathe out as you reach")
                t.contains("plank") -> listOf("Body in one straight line","Squeeze glutes and core","Breathe steadily, hips level")
                t.contains("halo") -> listOf("Ribs down, glutes tight","Circle bell slowly around head","Keep elbows close")
                t.contains("cat-cow") || t.contains("cat cow") -> listOf("Hands under shoulders, knees under hips","Round back up slowly","Then gently arch and look up")
                t.contains("scap") -> listOf("Arms straight the whole time","Pull shoulder blades down and together","Small, slow movement")
                t.contains("bike") || t.contains("incline walk") || t.contains("rowing machine") -> listOf("Easy pace - you can still talk","Move smoothly for the full time")
                t.contains("arm circle") -> listOf("Arms out, small circles","Slowly make circles bigger")
                t.contains("leg swing") -> listOf("Hold support, stand tall","Swing leg front-to-back smoothly")
                t.contains("squat") -> listOf("Feet shoulder-width apart","Chest up, sit back and down","Push through your heels")
                t.contains("stretch") || t.contains("pose") || type.equals("Stretch", true) -> listOf("Move gently to a mild stretch","Breathe slowly, never force it")
                else -> listOf("Set up tall with good posture","Move slowly and with control","Breathe out on the hard part")
            }
            val list = base.toMutableList()
            if (type.equals("Main", true) && (equipment.equals("Machine", true) || equipment.equals("Dumbbell", true) || equipment.equals("Kettlebell", true))) {
                if (list.size < 3) list.add("Start with the lightest weight that feels easy")
                else list[2] = list[2] // keep 3, starter hint shown separately in UI for weighted moves
            }
            return list.take(3).joinToString(";")
        }
    }
}
