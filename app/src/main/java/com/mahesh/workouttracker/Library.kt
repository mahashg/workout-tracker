package com.mahesh.workouttracker

/**
 * Exercise Library (v2.3): a pool of extra moves per muscle group to pick/swap from.
 * The daily routines stay unchanged (5-8/day cap); the library only APPENDS on demand.
 * Every youtubeUrl here is an honest YouTube SEARCH link ("name form") - no invented video IDs.
 */
object Library {
    data class Entry(
        val group: String,          // display group, e.g. "Shoulders"
        val muscles: String,        // targetMuscles, e.g. "shoulders,traps"
        val name: String,
        val equipment: String,      // "Dumbbell" | "Machine" | "Kettlebell" | "Bodyweight"
        val defaultSets: Int = 3,
        val targetReps: String,
        val postureCheck: String,   // ";" separated self-checks (shown in Beginner Mode)
        val cues: String            // ";" separated "Do it" cues
    )

    private fun url(name: String): String =
        "https://www.youtube.com/results?search_query=" + name.replace(" ", "+") + "+form"

    private fun e(
        group: String, muscles: String, name: String, equip: String, reps: String,
        posture: String, cues: String, sets: Int = 3
    ) = Entry(group, muscles, name, equip, sets, reps, posture, cues)

    val entries: List<Entry> = listOf(
        // Shoulders
        e("Shoulders", "shoulders", "DB Shoulder Press", "Dumbbell", "10",
            "Feet hip-width, glutes squeezed, ribs down;Wrists straight over elbows as you press;Elbows slightly in front of body - not flared wide;Head neutral - no shrugging shoulders to ears",
            "Press dumbbells straight up;Lower slowly to shoulder height;Keep ribs down the whole time"),
        e("Shoulders", "shoulders", "Cable Lateral Raise", "Machine", "12",
            "Stand tall beside the cable, shoulders down away from ears;Slight bend in elbow that stays fixed;Arm stays in line with body - slightly in front;No swinging from hips - torso completely still",
            "Pulleys at hand height to start;Lift to shoulder height only;Lower slowly - feel the side shoulder"),
        e("Shoulders", "shoulders,traps", "Face Pull", "Machine", "12",
            "Stand tall facing cable, chest up;Elbows high - point them back and out, not down;Rope ends near ears - thumbs toward you at the end;Back stays flat, no leaning/swinging from body",
            "Set pulley at face height;Pull rope to forehead;Squeeze shoulder blades at the end"),
        // Traps
        e("Traps", "traps", "Cable Shrug", "Machine", "12",
            "Stand tall, arms straight and still in front;Head neutral, chin level - no jutting forward;Shoulders lift straight UP - never roll them;Lift through upper traps only",
            "Grip bar low in front;Shrug shoulders to ears;Pause, then lower slowly"),
        e("Traps", "traps", "Farmer's Carry", "Dumbbell", "40 sec",
            "Stand tall - shoulders down and back, chest up;Arms straight at sides, weights not touching thighs;Walk heel-to-toe, steps short and even;Head level - look straight ahead, never down",
            "Pick up heavy dumbbells;Walk tall and slow;Set down with a flat back"),
        e("Traps", "traps,shoulders", "KB High Pull (Light)", "Kettlebell", "10",
            "Feet hip-width, hinge at hips, flat back;Bell starts between feet - arms straight, lats tight;Stand tall - no leaning back at the top;Elbows end high, pointing back - wrists below elbows",
            "Hinge and grip the bell;Snap hips to stand;Pull elbows high, lower with control"),
        // Chest
        e("Chest", "chest", "Cable Fly", "Machine", "12",
            "Back tall, chest up - shoulders down and back;Soft bend in elbows that stays fixed the whole set;Stagger stance for balance, torso still;Hands meet in front of chest, not below it",
            "Handles at shoulder height;Sweep hands together wide;Slowly open back to a stretch"),
        e("Chest", "chest", "DB Chest Fly", "Dumbbell", "10",
            "Entire back flat on bench, feet planted;Soft bend in elbows that stays fixed;Lower only until elbows level with bench - no deeper;Wrists straight over elbows, palms facing each other",
            "Start dumbbells over chest;Open wide with control;Squeeze chest to bring them back"),
        e("Chest", "chest,triceps", "Assisted Dip (Chest)", "Machine", "10",
            "Torso leans slightly forward (chest dip angle);Elbows point back-out at ~45 degrees;Shoulders down away from ears the whole time;Lower only to a comfortable stretch - never force depth",
            "Set assist, grip bars firmly;Lower with control;Press up without locking elbows"),
        // Triceps
        e("Triceps", "triceps", "Bench Dip", "Bodyweight", "10",
            "Hands on bench edge behind you, fingers forward;Back close to the bench - hips slide straight down;Elbows point straight back, not out to sides;Lower only until upper arms feel a stretch - stop if shoulders pinch",
            "Sit on bench, hands at hips;Walk feet out a little;Dip down and press up slowly"),
        e("Triceps", "triceps", "DB Triceps Kickback", "Dumbbell", "12",
            "Hinge forward with a flat back, free hand supported;Upper arm horizontal, tucked tight to your side - it never moves;Elbow locked in place - only forearm swings back;Wrist straight, no twisting at the end",
            "Upper arm parallel to floor;Kick dumbbell straight back;Squeeze triceps, lower slowly"),
        e("Triceps", "triceps", "DB Skull Crusher", "Dumbbell", "10",
            "Lying on bench, arms straight up over shoulders;Upper arms completely still and vertical - only forearms bend;Elbows point at ceiling, close together - no flaring;Lower dumbbells beside ears (not to forehead)",
            "Press dumbbells up over chest;Bend elbows to lower by ears;Straighten arms without moving elbows"),
        // Back
        e("Back", "back", "Chest-Supported DB Row", "Dumbbell", "10",
            "Chest fully on the incline bench pad - stay planted;Neck neutral - look down, don't crane up;Elbows track close - squeeze shoulder blades at top;No kicking with legs - only arms move",
            "Lie chest-down on incline bench;Row dumbbells to hips;Lower slowly to straight arms"),
        e("Back", "back", "Straight-Arm Pulldown", "Machine", "12",
            "Stand tall facing cable, slight hinge, flat back;Arms straight with a soft elbow bend that never changes;Pull bar to thighs in an arc - ribs down;Shoulders stay down - lats do the work, not arms",
            "Grip bar high, arms straight;Pull down to thighs;Slowly return to stretch overhead"),
        e("Back", "back,glutes", "Back Extension", "Bodyweight", "12",
            "Hips on the pad edge, spine long and neutral;Cross arms on chest - no hands behind head pulling neck;Hinge from hips only - back never rounds or over-arches;Stop when body is one straight line - don't go higher",
            "Set pad at hip crease;Hinge down slowly;Rise to straight line, squeeze glutes"),
        // Biceps
        e("Biceps", "biceps", "Cable Bicep Curl", "Machine", "12",
            "Elbows fixed at your sides - they never drift forward;Upper arms completely still - only forearms move;Stand tall, chest up - no swinging from hips;Lower all the way to straight arms slowly",
            "Bar at thigh height to start;Curl without swinging;Lower slowly all the way"),
        e("Biceps", "biceps", "Incline DB Curl", "Dumbbell", "10",
            "Back and head rest on the incline bench the whole set;Upper arms hang vertical, slightly behind body - still;Elbows never come forward during the curl;Lower all the way until arms straighten fully",
            "Sit back on 45-degree bench;Curl both dumbbells up;Lower slowly into full stretch"),
        e("Biceps", "biceps", "Machine Preacher Curl", "Machine", "12",
            "Upper arms flat on the pad - armpits over the pad edge;Elbows in line with the machine pivot;Sit tall - no leaning or bouncing to cheat;Lower to near-straight arms under control",
            "Adjust seat so arms rest flat;Curl handle up smoothly;Lower slowly, feel the stretch"),
        // Quads
        e("Quads", "quads", "Hack Squat Machine", "Machine", "10",
            "Shoulders and back flat against pads the whole time;Feet shoulder-width, whole foot on plate - heels down;Knees track exactly over toes - never cave in;Lower until thighs about parallel - stop if lower back lifts",
            "Feet shoulder-width on plate;Squat down with control;Push through heels to stand"),
        e("Quads", "quads,glutes", "Bulgarian Split Squat", "Bodyweight", "10 ea",
            "Torso tall and upright - no leaning forward;Front foot flat - knee tracks over toes, never caves in;Back foot top rests on bench behind you;Hips square forward, both legs share the work",
            "One foot up on bench behind;Drop straight down;Push through front heel to rise"),
        e("Quads", "quads,glutes", "Stationary DB Lunge", "Dumbbell", "10 ea",
            "Torso tall and upright - no leaning forward;Front knee tracks over front toes - never caves in;Whole front foot planted - push through heel;Step feet together between reps to reset",
            "Dumbbells at sides, feet together;Step forward and lower;Push back to standing"),
        // Hamstrings
        e("Hamstrings", "hamstrings", "Seated Leg Curl", "Machine", "12",
            "Back flat on seat pad, grip handles to stay still;Knees lined up with machine pivot point;Pad rests on back of ankles, not calves;No lifting hips off seat during the curl",
            "Sit tall, pad behind ankles;Curl heels down and back;Return slowly to the stretch"),
        e("Hamstrings", "hamstrings", "Lying Leg Curl", "Machine", "12",
            "Hips pressed flat into the pad the whole time;Knees lined up with machine pivot point;Curl smoothly - no jerking from lower back;Lower slowly until legs nearly straight",
            "Lie face-down, pad at ankles;Curl heels toward glutes;Lower slowly with control"),
        e("Hamstrings", "hamstrings,glutes", "Cable Pull-Through", "Machine", "12",
            "Face away from cable, feet hip-width, soft knees;Flat back the whole time - spine long, chest up;Push hips BACK toward the machine to lower;Stand by squeezing glutes - no leaning back",
            "Rope between legs, hinge back;Snap hips forward tall;Keep arms straight and relaxed"),
        // Glutes
        e("Glutes", "glutes", "Hip Abduction Machine", "Machine", "12",
            "Sit tall, back on pad - no leaning forward hard;Knees lined up with pads, feet flat on rests;Push from outer thighs/glutes - not by rocking torso;Move slowly - no slamming the weight stack",
            "Sit tall, pads outside knees;Press knees apart wide;Return slowly to stretch"),
        e("Glutes", "glutes,quads", "KB Sumo Squat", "Kettlebell", "12",
            "Feet wider than shoulders, toes turned out ~45 degrees;Chest up, flat back - look straight ahead;Knees track out over toes - never cave in;Weight in heels and mid-foot - heels stay down",
            "Bell hangs between legs;Squat down wide and tall;Push through heels, squeeze glutes"),
        e("Glutes", "glutes", "Fire Hydrant", "Bodyweight", "12 ea",
            "On all fours - hands under shoulders, knees under hips;Back flat like a table - no sagging or twisting;Knee stays bent at 90 degrees the whole move;Hips stay square to floor - don't roll open",
            "Lift bent knee out to side;Keep hips level;Lower slowly, repeat, switch sides"),
        // Calves
        e("Calves", "calves", "Standing Calf Raise Machine", "Machine", "12",
            "Balls of feet on step edge, heels lower than toes;Legs straight but knees never locked hard;Rise straight up - no bouncing at the bottom;Body tall - shoulders under pads, no leaning",
            "Shoulders under pads, feet at edge;Rise onto toes high;Lower heels below step, slowly"),
        e("Calves", "calves", "Seated Calf Raise", "Machine", "12",
            "Balls of feet on platform, pad rests on lower thighs;Knees bent ~90 degrees and still throughout;Rise straight up on toes - no rocking;Full stretch at bottom - pause before rising",
            "Sit tall, pad on thighs;Raise heels as high as you can;Lower slowly to full stretch"),
        e("Calves", "calves", "Single-Leg Calf Raise", "Bodyweight", "12 ea",
            "Ball of one foot on step edge, hold rail for balance;Standing leg straight, knee soft - not locked;Rise straight up - hips level, no leaning away;Lower heel below step slowly, no bounce",
            "One foot on step, hold support;Rise onto toes tall;Lower slowly, repeat, switch"),
        // Core
        e("Core", "core", "Cable Crunch", "Machine", "12",
            "Kneel tall facing cable, hips stay back and still;Rope by your ears - arms locked, they never pull;Curl ribs toward pelvis - spine rounds like a C;Lower back stays put - only your torso curls",
            "Kneel, rope behind head;Curl down ribs-to-pelvis;Rise slowly to tall kneeling"),
        e("Core", "core", "Russian Twist", "Bodyweight", "20 (10 ea)",
            "Sit tall, knees bent, heels lightly on floor;Lean back slightly with a LONG straight back - never rounded;Rotate from ribs - shoulders turn, hips stay still;Move slowly - momentum does no work here",
            "Sit tall, lean back a little;Turn shoulders side to side;Tap floor lightly each side"),
        e("Core", "core", "Mountain Climbers", "Bodyweight", "30 sec",
            "Plank position - body one straight line head-to-heels;Hands directly under shoulders, arms straight;Hips stay level - no bouncing up and down;Knees drive toward chest, feet land softly",
            "Start in strong plank;Drive knees in alternately;Keep hips low and steady")
    )

    fun groups(): List<String> =
        listOf("Shoulders", "Traps", "Chest", "Triceps", "Back", "Biceps", "Quads", "Hamstrings", "Glutes", "Calves", "Core")

    fun byGroup(group: String): List<Entry> = entries.filter { it.group.equals(group, true) }

    fun urlFor(entry: Entry): String = url(entry.name)
}
