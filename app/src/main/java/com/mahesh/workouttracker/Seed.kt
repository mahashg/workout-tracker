package com.mahesh.workouttracker

import android.content.Context

object Seed {
    private val preloadedNames = listOf("Shoulders & Traps","Chest & Triceps","Back & Biceps","Legs (Quad focus)","Legs (Hamstring/Glute) + Core")

    private data class SE(val name:String,val type:String,val equip:String,val sets:Int,val reps:String,val weight:String="",val variation:String="",val url:String="",val muscles:String="")
    private fun se(name:String,type:String,equip:String,sets:Int,reps:String,weight:String="",variation:String="",url:String="",muscles:String="") = SE(name,type,equip,sets,reps,weight,variation,url,muscles)

    // URLs copied verbatim from research tables; FALLBACK entries are youtube search URLs exactly as given.
    private const val U_BIKE="https://www.youtube.com/watch?v=EzEaTen6CMU"
    private const val U_ARM_CIRCLES="https://www.youtube.com/shorts/HMxqtrsNz60"
    private const val U_HALO="https://www.youtube.com/watch?v=BtNHBe5VXxY"
    private const val U_PUSHUP_PLUS="https://www.youtube.com/watch?v=iQP1BBmwAgg"
    private const val U_SHOULDER_PRESS="https://www.youtube.com/shorts/Gkk-6q7Rq-s"
    private const val U_KB_PRESS="https://www.youtube.com/watch?v=gjr-QAdsq4o"
    private const val U_LAT_RAISE="https://www.youtube.com/shorts/n1aCZuft1xM"
    private const val U_REV_PEC="https://www.youtube.com/watch?v=P6-N-VGCVxk"
    private const val U_SHRUG="https://www.youtube.com/watch?v=vMr-kzeElVo"
    private const val U_PIKE="https://www.youtube.com/shorts/Jod-jg8s0mk"
    private const val U_CROSS_BODY="https://www.youtube.com/shorts/fmvywFQkKAw"
    private const val U_NECK_TRAP="https://www.youtube.com/watch?v=3hw6r9zrraU"
    private const val U_DOORWAY_CHEST="https://www.youtube.com/shorts/ArixC7ODJiI"
    private const val U_TRI_STRETCH="https://www.youtube.com/watch?v=Uvk1Y8O1_yM"
    private const val U_INCLINE_WALK="https://www.youtube.com/shorts/yN8bNaNIucw"
    private const val U_CHEST_PRESS="https://www.youtube.com/shorts/fQI6Fy6uqtE"
    private const val U_INCLINE_DB="https://www.youtube.com/shorts/f-q2SJAuVDc"
    private const val U_KB_FLOOR_FALLBACK="https://www.youtube.com/results?search_query=kettlebell%20floor%20press%20hindi"
    private const val U_PUSHUPS="https://www.youtube.com/watch?v=ZY7sCgqsXYs"
    private const val U_TRI_PUSHDOWN="https://www.youtube.com/shorts/DxPIwrcRcKA"
    private const val U_DB_TRI_EXT_FALLBACK="https://www.youtube.com/results?search_query=dumbbell%20overhead%20triceps%20extension%20hindi"
    private const val U_DIAMOND="https://www.youtube.com/watch?v=QCUUyCsTMxQ"
    private const val U_LAT_PULLDOWN="https://www.youtube.com/shorts/na6vMsxorI0"
    private const val U_SEATED_ROW="https://www.youtube.com/shorts/TaVfW4LVmrw"
    private const val U_KB_ROW_FALLBACK="https://www.youtube.com/results?search_query=single+arm+kettlebell+row+hindi"
    private const val U_DB_CURL="https://www.youtube.com/watch?v=5EQ1Nu1ODgE"
    private const val U_HAMMER="https://www.youtube.com/shorts/I6saX6C_G4E"
    private const val U_INV_ROW_FALLBACK="https://www.youtube.com/results?search_query=inverted+row+hindi"
    private const val U_CATCOW="https://www.youtube.com/watch?v=VPa8-aMy7lA"
    private const val U_SCAP_PULL="https://www.youtube.com/shorts/9M8ylnbriB0"
    private const val U_ROW_WARM_FALLBACK="https://www.youtube.com/results?search_query=rowing+machine+warm+up+hindi"
    private const val U_LAT_STRETCH_FALLBACK="https://www.youtube.com/results?search_query=lat+stretch+hindi"
    private const val U_BICEP_STRETCH_FALLBACK="https://www.youtube.com/results?search_query=doorway+bicep+stretch+hindi"
    private const val U_CHILD="https://www.youtube.com/shorts/5lQz1Npqdwg"
    private const val U_LEG_PRESS="https://www.youtube.com/watch?v=7P4Jt_2yRAA"
    private const val U_GOBLET="https://www.youtube.com/watch?v=FPYzK3LAKu8"
    private const val U_LUNGE="https://www.youtube.com/watch?v=Pbmj6xPo-Hw"
    private const val U_LEG_EXT="https://www.youtube.com/shorts/awskmnDDtAs"
    private const val U_KB_STEP_FALLBACK="https://www.youtube.com/results?search_query=kettlebell+step+up+hindi"
    private const val U_WALL_SIT="https://www.youtube.com/shorts/dUrX61oJdIU"
    private const val U_BW_SQUAT="https://www.youtube.com/watch?v=Ul2idJKmpAc"
    private const val U_LEG_SWINGS="https://www.youtube.com/shorts/iN2XZrZVozg"
    private const val U_QUAD_STRETCH="https://www.youtube.com/shorts/l0CGOOd_7rA"
    private const val U_CALF_STRETCH="https://www.youtube.com/watch?v=zeTV7lvAWyg"
    private const val U_COUCH_FALLBACK="https://www.youtube.com/results?search_query=couch+stretch+hip+flexor+hindi"
    private const val U_LEG_CURL="https://www.youtube.com/watch?v=QolPPQFk8SE"
    private const val U_KB_RDL_FALLBACK="https://www.youtube.com/results?search_query=kettlebell%20romanian%20deadlift%20hindi"
    private const val U_KB_SWING="https://www.youtube.com/watch?v=O3tXmXn2sog"
    private const val U_DB_STAG_RDL="https://www.youtube.com/watch?v=zSKeYOuv_60"
    private const val U_GLUTE_KICKBACK="https://www.youtube.com/shorts/jKvvKrs2u_g"
    private const val U_GLUTE_BRIDGE="https://www.youtube.com/shorts/STYnAiPREnI"
    private const val U_DEADBUG="https://www.youtube.com/watch?v=v1mKeDKbyQI"
    private const val U_PLANK="https://www.youtube.com/watch?v=xKQwZCjbp-g"
    private const val U_HIP_THRUST="https://www.youtube.com/shorts/fjHMwYiMgno"
    private const val U_KB_DL_FALLBACK="https://www.youtube.com/results?search_query=kettlebell%20deadlift%20hindi"
    private const val U_HAM_STRETCH="https://www.youtube.com/watch?v=ZeSAX3QlZxQ"
    private const val U_PIGEON="https://www.youtube.com/shorts/fbMLr8XtVPE"
    private const val U_COBRA="https://www.youtube.com/watch?v=yjfcGr_kvAQ"

    private data class RDef(val name:String,val focus:String,val weekday:Int,val exs:List<SE>)
    private fun defs(): List<RDef> = listOf(
        RDef("Shoulders & Traps","Shoulders, Traps",1, listOf(
            se("Stationary Bike","Warmup","Machine",1,"5 min","","Easy pace",U_BIKE,""),
            se("Arm Circles","Warmup","Bodyweight",1,"30 sec","BW","",U_ARM_CIRCLES,"shoulders"),
            se("Kettlebell Halo","Warmup","Kettlebell",1,"8 each","","Light halo",U_HALO,"shoulders"),
            se("Scap Push-up","Warmup","Bodyweight",1,"10","BW","Push-up plus / scap push",U_PUSHUP_PLUS,"shoulders,back"),
            se("Shoulder Press Machine","Main","Machine",3,"10","","",U_SHOULDER_PRESS,"shoulders"),
            se("Single-Arm KB Press","Main","Kettlebell",3,"8","","Half-kneeling variation",U_KB_PRESS,"shoulders"),
            se("DB Lateral Raise","Main","Dumbbell",3,"12","","",U_LAT_RAISE,"shoulders"),
            se("Reverse Pec Deck","Main","Machine",3,"12","","Rear delts",U_REV_PEC,"shoulders,back"),
            se("DB Shrug","Main","Dumbbell",3,"15","","",U_SHRUG,"traps"),
            se("Pike Push-up","Main","Bodyweight",2,"10","BW","Wall pike easier",U_PIKE,"shoulders"),
            se("Cross-Body Shoulder Stretch","Stretch","Bodyweight",1,"30 sec","BW","",U_CROSS_BODY,"shoulders"),
            se("Neck Trapezius Stretch","Stretch","Bodyweight",1,"30 sec","BW","",U_NECK_TRAP,"traps"),
            se("Doorway Chest Stretch","Stretch","Bodyweight",1,"30 sec","BW","",U_DOORWAY_CHEST,"chest"),
            se("Overhead Triceps Stretch","Stretch","Bodyweight",1,"30 sec","BW","",U_TRI_STRETCH,"triceps")
        )),
        RDef("Chest & Triceps","Chest, Triceps",2, listOf(
            se("Incline Walk","Warmup","Machine",1,"5 min","","Warm-up walk",U_INCLINE_WALK,"quads,calves"),
            se("Push-Up Plus","Warmup","Bodyweight",1,"10","BW","Scapula push-up",U_PUSHUP_PLUS,"chest,shoulders"),
            se("Arm Circles","Warmup","Bodyweight",1,"30 sec","BW","",U_ARM_CIRCLES,"shoulders"),
            se("Light Push-ups","Warmup","Bodyweight",1,"10","BW","Easy warm-up set",U_PUSHUPS,"chest"),
            se("Chest Press Machine","Main","Machine",3,"10","","",U_CHEST_PRESS,"chest"),
            se("Incline DB Press","Main","Dumbbell",3,"10","","",U_INCLINE_DB,"chest"),
            se("KB Floor Press","Main","Kettlebell",3,"10","","",U_KB_FLOOR_FALLBACK,"chest,triceps"),
            se("Push-ups","Main","Bodyweight",3,"max-2","BW","Knee/incline/decline",U_PUSHUPS,"chest"),
            se("Cable Triceps Pushdown","Main","Machine",3,"12","","",U_TRI_PUSHDOWN,"triceps"),
            se("DB Overhead Triceps Extension","Main","Dumbbell",2,"10","","",U_DB_TRI_EXT_FALLBACK,"triceps"),
            se("Diamond Push-up","Main","Bodyweight",2,"10","BW","",U_DIAMOND,"triceps,chest"),
            se("Doorway Chest Stretch","Stretch","Bodyweight",1,"30 sec","BW","",U_DOORWAY_CHEST,"chest"),
            se("Overhead Triceps Stretch","Stretch","Bodyweight",1,"30 sec","BW","",U_TRI_STRETCH,"triceps"),
            se("Cross-Body Shoulder Stretch","Stretch","Bodyweight",1,"30 sec","BW","",U_CROSS_BODY,"shoulders")
        )),
        RDef("Back & Biceps","Back, Biceps",3, listOf(
            se("Rowing Machine Warm-up","Warmup","Machine",1,"3 min","","Easy row",U_ROW_WARM_FALLBACK,"back"),
            se("Cat-Cow","Warmup","Bodyweight",1,"10","BW","",U_CATCOW,"back,core"),
            se("Scapula Pulls","Warmup","Bodyweight",1,"10","BW","Scap pull",U_SCAP_PULL,"back,shoulders"),
            se("Arm Circles","Warmup","Bodyweight",1,"30 sec","BW","",U_ARM_CIRCLES,"shoulders"),
            se("Lat Pulldown","Main","Machine",3,"10","","",U_LAT_PULLDOWN,"back"),
            se("Seated Cable Row","Main","Machine",3,"12","","",U_SEATED_ROW,"back"),
            se("Single-Arm KB Row","Main","Kettlebell",3,"10","","",U_KB_ROW_FALLBACK,"back"),
            se("DB Bicep Curl","Main","Dumbbell",3,"10","","",U_DB_CURL,"biceps"),
            se("DB Hammer Curl","Main","Dumbbell",2,"12","","",U_HAMMER,"biceps"),
            se("Inverted Row","Main","Bodyweight",3,"10","BW","",U_INV_ROW_FALLBACK,"back,biceps"),
            se("Lat Stretch","Stretch","Bodyweight",1,"30 sec","BW","",U_LAT_STRETCH_FALLBACK,"back"),
            se("Doorway Bicep Stretch","Stretch","Bodyweight",1,"30 sec","BW","",U_BICEP_STRETCH_FALLBACK,"biceps"),
            se("Child's Pose","Stretch","Bodyweight",1,"60 sec","BW","",U_CHILD,"back")
        )),
        RDef("Legs (Quad focus)","Quads, Calves",4, listOf(
            se("Stationary Bike","Warmup","Machine",1,"5 min","","Easy pace",U_BIKE,"quads,calves"),
            se("Bodyweight Squat","Warmup","Bodyweight",1,"15","BW","",U_BW_SQUAT,"quads"),
            se("Leg Swings","Warmup","Bodyweight",1,"15 each","BW","",U_LEG_SWINGS,"quads,hamstrings"),
            se("Glute Bridge Activation","Warmup","Bodyweight",1,"12","BW","Warm-up activation",U_GLUTE_BRIDGE,"glutes"),
            se("Leg Press","Main","Machine",3,"10","","",U_LEG_PRESS,"quads"),
            se("KB Goblet Squat","Main","Kettlebell",3,"10","","",U_GOBLET,"quads"),
            se("DB Walking Lunge","Main","Dumbbell",2,"10 ea","","",U_LUNGE,"quads,glutes"),
            se("Leg Extension","Main","Machine",3,"12","","",U_LEG_EXT,"quads"),
            se("KB Step-Up","Main","Kettlebell",2,"10 ea","","",U_KB_STEP_FALLBACK,"quads,glutes"),
            se("Wall Sit","Main","Bodyweight",2,"40 sec","BW","",U_WALL_SIT,"quads"),
            se("Standing Quadriceps Stretch","Stretch","Bodyweight",1,"30 sec","BW","",U_QUAD_STRETCH,"quads"),
            se("Standing Calf Stretch","Stretch","Bodyweight",1,"30 sec","BW","",U_CALF_STRETCH,"calves"),
            se("Couch Stretch","Stretch","Bodyweight",1,"30 sec","BW","Hip flexor",U_COUCH_FALLBACK,"quads")
        )),
        RDef("Legs (Hamstring/Glute) + Core","Hamstrings, Glutes, Core",5, listOf(
            se("Stationary Bike","Warmup","Machine",1,"5 min","","Easy pace",U_BIKE,"quads,calves"),
            se("Glute Bridge Activation","Warmup","Bodyweight",1,"12","BW","Warm-up activation",U_GLUTE_BRIDGE,"glutes"),
            se("Kettlebell Deadlift (Light)","Warmup","Kettlebell",1,"10","","Light hinge pattern",U_KB_DL_FALLBACK,"hamstrings,glutes"),
            se("Leg Swings","Warmup","Bodyweight",1,"15 each","BW","",U_LEG_SWINGS,"quads,hamstrings"),
            se("Leg Curl Machine","Main","Machine",3,"12","","Seated/lying leg curl",U_LEG_CURL,"hamstrings"),
            se("KB Romanian Deadlift","Main","Kettlebell",3,"10","","",U_KB_RDL_FALLBACK,"hamstrings,glutes"),
            se("KB Swing (light)","Main","Kettlebell",2,"12","","Hinge only",U_KB_SWING,"glutes,hamstrings"),
            se("DB Staggered Romanian Deadlift","Main","Dumbbell",3,"10","","",U_DB_STAG_RDL,"hamstrings,glutes"),
            se("Cable Glute Kickback","Main","Machine",3,"12","","",U_GLUTE_KICKBACK,"glutes"),
            se("Hip Thrust Machine","Main","Machine",3,"12","","",U_HIP_THRUST,"glutes"),
            se("Glute Bridge","Main","Bodyweight",3,"15","BW","",U_GLUTE_BRIDGE,"glutes"),
            se("Dead Bug","Main","Bodyweight",2,"10","BW","",U_DEADBUG,"core"),
            se("Plank","Main","Bodyweight",2,"40 sec","BW","",U_PLANK,"core"),
            se("Hamstring Stretch","Stretch","Bodyweight",1,"30 sec","BW","",U_HAM_STRETCH,"hamstrings"),
            se("Pigeon Pose","Stretch","Bodyweight",1,"45 sec","BW","Glute stretch",U_PIGEON,"glutes"),
            se("Cobra Pose","Stretch","Bodyweight",1,"30 sec","BW","",U_COBRA,"back,core")
        ))
    )

    fun ensureSeeded(db: DbHelper) {
        if (db.getRoutines().isNotEmpty()) return
        seed(db)
    }
    fun restore(db: DbHelper) {
        val w = db.writableDatabase
        w.delete("exercises", null, null); w.delete("routines", null, null)
        seed(db)
    }
    fun applyV2IfNeeded(context: Context, db: DbHelper) {
        val prefs = context.getSharedPreferences("workout_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("v2SeedApplied", false)) return
        // Replace exercise lists for the 5 preloaded routines; custom routines untouched.
        for (def in defs()) {
            val existing = db.getRoutines().firstOrNull { it.name.equals(def.name, ignoreCase = true) } ?: continue
            db.deleteExercisesForRoutine(existing.id)
            // keep weekday/focus from def (focus may have evolved), preserve notes; weekday preserved from existing
            db.updateRoutine(existing.copy(focus = def.focus))
            for ((i, e) in def.exs.withIndex()) {
                db.insertExercise(Exercise(0, existing.id, e.name, e.type, e.equip, e.sets, e.reps, e.weight, e.variation, e.url, i, e.muscles, DbHelper.cuesForName(e.name, e.equip, e.type), DbHelper.postureForName(e.name, e.type)))
            }
        }
        prefs.edit().putBoolean("v2SeedApplied", true).apply()
    }
    fun markV2Applied(context: Context) {
        context.getSharedPreferences("workout_prefs", Context.MODE_PRIVATE).edit().putBoolean("v2SeedApplied", true).apply()
    }
    private fun seed(db: DbHelper) {
        for (def in defs()) {
            val rid = db.insertRoutine(Routine(0, def.name, def.focus, def.weekday, "", ""))
            for ((i, e) in def.exs.withIndex()) db.insertExercise(Exercise(0, rid, e.name, e.type, e.equip, e.sets, e.reps, e.weight, e.variation, e.url, i, e.muscles, DbHelper.cuesForName(e.name, e.equip, e.type), DbHelper.postureForName(e.name, e.type)))
        }
    }
}
