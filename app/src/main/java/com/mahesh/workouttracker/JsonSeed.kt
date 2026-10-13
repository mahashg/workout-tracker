package com.mahesh.workouttracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONObject

/**
 * v3 data layer. Model lives in code ([BodyParts]); the workout DATA lives in
 * assets/data/<part-id>.json — one JSON object per body part with warmups,
 * exercises and stretches. On first v3 launch the JSONs seed the DB
 * (exercises.bodyPart / exercises.sourceId); afterwards the DB is the working
 * copy, so in-app add/edit (ExerciseEditActivity) keeps working per part.
 */
object JsonSeed {
    data class Entry(
        val id: String, val name: String, val equipment: String, val sets: Int,
        val targetReps: String, val targetMuscles: String, val precautions: String,
        val cues: String, val image: String, val url: String, val primary: Boolean,
        val defaultWeight: String, val variation: String, val easier: String,
        val photo: String, val type: String // "Warmup" | "Main" | "Stretch"
    )

    data class PartData(val partId: String, val warmups: List<Entry>, val exercises: List<Entry>, val stretches: List<Entry>) {
        val all: List<Entry> get() = warmups + exercises + stretches
    }

    @Volatile private var cache: List<PartData>? = null

    fun loadAll(context: Context): List<PartData> {
        cache?.let { return it }
        val out = mutableListOf<PartData>()
        for (partId in BodyParts.ids) {
            try {
                val text = context.assets.open("data/$partId.json").bufferedReader().use { it.readText() }
                val root = JSONObject(text)
                fun entries(key: String, type: String): List<Entry> {
                    val arr = root.optJSONArray(key) ?: return emptyList()
                    val list = mutableListOf<Entry>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(Entry(
                            id = o.optString("id"), name = o.optString("name"),
                            equipment = o.optString("equipment"), sets = o.optInt("sets", 3),
                            targetReps = o.optString("targetReps"), targetMuscles = o.optString("targetMuscles"),
                            precautions = o.optString("precautions"), cues = o.optString("cues"),
                            image = o.optString("image"), url = o.optString("url"),
                            primary = o.optBoolean("primary", false),
                            defaultWeight = o.optString("defaultWeight"), variation = o.optString("variation"),
                            easier = o.optString("easier"), photo = o.optString("photo"), type = type
                        ))
                    }
                    return list
                }
                out.add(PartData(partId, entries("warmups", "Warmup"), entries("exercises", "Main"), entries("stretches", "Stretch")))
            } catch (e: Exception) { /* a missing/corrupt part file must never crash the app */ }
        }
        cache = out
        return out
    }

    fun entryById(context: Context, id: String): Entry? =
        if (id.isBlank()) null else loadAll(context).firstNotNullOfOrNull { pd -> pd.all.firstOrNull { it.id == id } }

    fun entryByName(context: Context, name: String): Entry? {
        val key = name.trim().lowercase()
        return loadAll(context).firstNotNullOfOrNull { pd -> pd.all.firstOrNull { it.name.trim().lowercase() == key } }
    }

    fun applyV3IfNeeded(context: Context, db: DbHelper) {
        val prefs = context.getSharedPreferences("workout_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("v3SeedApplied", false)) return
        applyV3(context, db)
        prefs.edit().putBoolean("v3SeedApplied", true).apply()
    }

    /** Settings "Restore Preloaded": drop the canonical JSON-seeded rows and
     *  re-seed them from the JSON data. User-added rows (sourceId="") and all
     *  session history are untouched. */
    fun restoreDefaults(context: Context, db: DbHelper) {
        db.writableDatabase.delete("exercises", "sourceId IS NOT NULL AND sourceId != ''", null)
        applyV3(context, db)
    }

    private fun applyV3(context: Context, db: DbHelper) {
        val parts = loadAll(context)
        val entryPart = mutableMapOf<String, String>()
        val byNameType = mutableMapOf<Pair<String, String>, Entry>()
        val byName = mutableMapOf<String, Entry>()
        for (pd in parts) for (e in pd.all) {
            entryPart[e.id] = pd.partId
            byNameType.putIfAbsent(e.name.trim().lowercase() to e.type, e)
            byName.putIfAbsent(e.name.trim().lowercase(), e)
        }
        // 1) Backfill/adopt existing rows: match a JSON entry by name (+type),
        //    else derive the part from target muscles. Nothing is renamed or
        //    re-texted — only bodyPart/sourceId are filled in.
        for (e in db.getAllExercises()) {
            if (e.sourceId.isNotBlank() && e.bodyPart.isNotBlank()) continue
            val match = byNameType[e.name.trim().lowercase() to e.type] ?: byName[e.name.trim().lowercase()]
            var bp = e.bodyPart
            var sid = e.sourceId
            if (match != null) {
                sid = match.id
                bp = entryPart[match.id] ?: bp
            }
            if (bp.isBlank()) {
                bp = DbHelper.parseMuscles(e.targetMuscles, e.name).firstOrNull { BodyParts.byId(it) != null } ?: ""
            }
            if (bp != e.bodyPart || sid != e.sourceId) db.updateExercise(e.copy(bodyPart = bp, sourceId = sid))
        }
        // 2) Dedupe canonical rows that share a sourceId (the legacy 5-routine
        //    seed repeated warm-ups/stretches across days, e.g. Arm Circles x3).
        //    Session history stores its own copies, so this loses nothing.
        val seen = mutableSetOf<String>()
        for (e in db.getAllExercises()) {
            if (e.sourceId.isBlank()) continue
            if (!seen.add(e.sourceId)) db.deleteExercise(e.id)
        }
        // 3) Insert the JSON entries that are not in the DB yet.
        val present = db.getAllExercises().map { it.sourceId }.toSet()
        for (pd in parts) {
            var order = 0
            for (entry in pd.all) {
                val myOrder = order++
                if (entry.id in present) continue
                db.insertExercise(Exercise(
                    0, -1, entry.name, entry.type, entry.equipment, entry.sets,
                    entry.targetReps, entry.defaultWeight, entry.variation, entry.url,
                    myOrder, entry.targetMuscles, entry.cues, entry.precautions, pd.partId, entry.id
                ))
            }
        }
        // 4) Backfill session_parts for pre-v3 sessions (their parts are
        //    derived from their exercises) so Home's last-trained/done state
        //    reaches back into existing history.
        val withParts = db.sessionsWithParts()
        for (s in db.getSessions()) {
            if (s.id in withParts) continue
            val ps = db.getSessionExercises(s.id).map { db.sessionExercisePart(it) }.filter { it.isNotBlank() }.distinct()
            for (p in ps) db.insertSessionPart(s.id, p)
        }
    }
}

/** Resolves exercise photos (assets/exercise-images/<id>.jpg, from the
 *  free-exercise-db set — see research-exercise-images.md) and decodes them
 *  sampled + cached. No photo -> null -> callers fall back to BodyMap. */
object PhotoLib {
    private val bitmapCache = LruCache<String, Bitmap>(32)

    fun assetForExercise(context: Context, e: Exercise): String? {
        val entry = JsonSeed.entryById(context, e.sourceId) ?: JsonSeed.entryByName(context, e.name)
        return entry?.photo?.takeIf { it.isNotBlank() }
    }

    fun assetForSessionExercise(context: Context, db: DbHelper, se: SessionExercise): String? {
        if (se.originExerciseId > 0) {
            db.getExercise(se.originExerciseId)?.let { return assetForExercise(context, it) }
        }
        return JsonSeed.entryByName(context, se.name)?.photo?.takeIf { it.isNotBlank() }
    }

    /** v3.1 animation frame 2: "<id>-2.jpg" next to the bundled photo (the
     *  dataset entry's second movement position). Null when the entry has
     *  no second frame — callers then show the static photo. */
    fun secondBitmap(context: Context, firstAssetPath: String): Bitmap? =
        bitmap(context, firstAssetPath.removeSuffix(".jpg") + "-2.jpg")

    fun bitmap(context: Context, assetPath: String): Bitmap? {
        bitmapCache.get(assetPath)?.let { return it }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 720) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, opts) }
            if (bmp != null) bitmapCache.put(assetPath, bmp)
            bmp
        } catch (e: Exception) { null }
    }
}
