package com.mahesh.workouttracker

import android.content.Context
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * v3.11 Circuits data layer. Model lives in code (this file); the circuit
 * DATA lives in assets/data/circuits.json — one heterogeneous raw object
 * per preset, written by three different sources (an Instagram saved-reel
 * scan, an Instagram circuit search, and the Nerd Fitness circuit guide),
 * so almost every field beyond id/name/steps is OPTIONAL and shaped a
 * little differently on each circuit:
 *  - source{type,creator,ref} rides on the 49 sourced circuits; the 17
 *    Nerd Fitness ones carry level/audience/equipmentTheme/section/
 *    restGuidance instead, and no source at all.
 *  - rounds is absent on 12 circuits (3 of them are time-boxed AMRAPs),
 *    workSec/restSec/roundRestSec/repsPerMotion each appear on a minority,
 *    and exactly 3 circuits carry a timeBoxMin.
 *  - a step is just {id} on the timed circuits, {id,dose} on the rep
 *    circuits, {id,durationSec} on a handful of holds, and may also carry
 *    a display name, a block label ("Superset 1", "Day 1", …) and a note.
 *
 * [load] normalises all of that ONCE into the flat [Circuit] shape the
 * Circuits screens read, so no screen ever touches raw JSON. Every
 * accessor is defensive (opt*-style, per-entry try/catch): one malformed
 * circuit or step must never crash the app, it is simply skipped or
 * defaulted. Catalog entries (a step's name, its guide reps, its body
 * part) are resolved through [JsonSeed] — the part files stay the single
 * source of truth for movement data, exactly as the Movie deck does.
 */
object CircuitsData {

    /** Session identity of a Circuits workout: sessions.focus is
     *  FOCUS_PREFIX + circuit id (DbHelper.createCircuitSession writes
     *  it, [isCircuit]/[circuitIdOf] read it back). Mirrors how Movie
     *  Movements keys off its routineName, but in the focus column —
     *  a circuit's display name is already the session's routineName,
     *  and History/Summary need to tell two same-named presets apart. */
    const val FOCUS_PREFIX = "circuit:"

    /** True when [s] is a Circuits session (see [FOCUS_PREFIX]). */
    fun isCircuit(s: SessionInfo): Boolean = s.focus.startsWith(FOCUS_PREFIX)

    /** The circuit id a Circuits session was started from, or null when
     *  [s] is not a Circuits session (or the suffix is somehow blank). */
    fun circuitIdOf(s: SessionInfo): String? =
        if (isCircuit(s)) s.focus.removePrefix(FOCUS_PREFIX).takeIf { it.isNotBlank() } else null

    /**
     * One normalised circuit step (station). [entryId] is the catalog id
     * (JsonSeed) of the movement. Exactly one dose shape is live per step:
     *  - timed: [timedSec] > 0 and [doseLabel] is the display dose
     *    ("40s", "2 min", or the step's verbatim dose for compound doses
     *    like "2 x 30 sec"), [repsLabel] is "".
     *  - rep-based: [timedSec] == 0 and [repsLabel] is what the set
     *    logger stores ("10", "10 each side"); [doseLabel] is the same
     *    text for display (the raw dose with the word "reps" stripped).
     *  - rep-free (a "part of ~5 min total" warm-up menu step): [timedSec]
     *    == 0 and both labels are the raw dose — there is no rep target.
     * [block] is the raw grouping label ("Superset 1", "Day 1", …) or
     * null when the circuit does not group its steps.
     */
    data class CStep(val entryId: String, val doseLabel: String, val timedSec: Int, val repsLabel: String, val block: String?)

    /**
     * One normalised circuit preset, ready for display and for starting
     * a session. Invariants the screens rely on:
     *  - [rounds] is -1 for AMRAP (run against [timeBoxMin] instead of a
     *    round count) and >= 1 for everything else.
     *  - [restSec] is 0 (not null) when the source prescribes no rest
     *    between steps; [roundRestSec] defaults to 60 the same way.
     *  - [workSec]/[repsPerMotion]/[timeBoxMin] stay null when absent —
     *    they are the discriminators [dosePattern] is built from.
     *  - [totalMinutes] is an estimate for list display (see load docs),
     *    except on time-boxed circuits where it IS the prescribed box.
     *  - [band] is one of [BAND_ORDER]; [jumping] drives the
     *    "No jumping" chip and the low-impact promise.
     */
    data class Circuit(val id: String, val name: String, val specialty: String?, val sourceType: String?, val creator: String?, val sourceRef: String?, val format: String?, val rounds: Int, val timeBoxMin: Int?, val workSec: Int?, val restSec: Int, val roundRestSec: Int, val repsPerMotion: Int?, val steps: List<CStep>, val notes: String, val jumping: Boolean, val totalMinutes: Int, val dosePattern: String, val band: String)

    /** Display order of the Circuits list's sections. [Circuit.band] is
     *  always one of these (the band rules in [bandOf] end in a
     *  catch-all, never in an off-list value). */
    val BAND_ORDER: List<String> = listOf(
        "Start Here", "From Your Reels", "Quick & Timed", "Warm-Ups", "Mobility",
        "Dumbbell", "Core", "HIIT & Tabata", "Nerd Fitness"
    )

    // Parsed-once in-memory cache (same contract as JsonSeed.loadAll:
    // the bundled JSON cannot change under a running process).
    @Volatile private var cache: List<Circuit>? = null

    /** Circuit ids tagged for the Circuits section in circuits.json's
     *  sectionTags (a LIST of {id, sections[]} alongside the circuits).
     *  Populated by the same parse pass as [cache]; read by [motions]. */
    @Volatile private var sectionCircuitIds: Set<String> = emptySet()

    /** "120s that is really 2 min" formatting for dose labels: whole
     *  minutes from 120s up read "N min", everything else "Ns"
     *  (so 60s stays "60s", the way the sources write EMOM minutes). */
    private fun fmtSec(s: Int): String = if (s >= 120 && s % 60 == 0) "${s / 60} min" else "${s}s"

    // ---- defensive JSON helpers (a malformed entry must never crash) ----

    /** String field or null when absent/blank/not a string. */
    private fun JSONObject.strOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return (opt(key) as? String)?.takeIf { it.isNotBlank() }
    }

    /** Int field or null when absent/not numeric. Accepts JSON numbers
     *  and numeric strings; anything else defaults instead of throwing. */
    private fun JSONObject.intOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return when (val v = opt(key)) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        }
    }

    /** A dose with the word "reps" removed, whitespace tidied:
     *  "10 reps each side" -> "10 each side". Used for rep-step display
     *  labels and for catalog targetReps read as a rep target. */
    private fun stripReps(dose: String): String =
        dose.replace(Regex("""\breps\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .replace(" ,", ",")
            .trim()

    /** "N x M sec|min" at the START of a dose ("2 x 30 sec each side"):
     *  a compound timed dose — N repeats of an M-second hold. */
    private val productDose = Regex("""^(\d+)\s*x\s*(\d+)\s*(sec|min)\b""", RegexOption.IGNORE_CASE)

    /** The first time mention in a dose ("15 sec", "2-3 min", "1 min,
     *  hold the top position"): group 1 is the (first) value, group 3
     *  the unit. A range resolves to its FIRST value by contract. */
    private val timeMention = Regex("""(\d+)(?:\s*-\s*(\d+))?\s*(sec|min)\b""", RegexOption.IGNORE_CASE)

    /** Leading integer of a label ("10 each side" -> 10), null when the
     *  label does not start with a number ("—", "part of ~5 min total"). */
    private val leadingInt = Regex("""^\s*(\d+)""")

    /**
     * Resolve one raw step's dose, in contract order:
     *  (a) an explicit durationSec wins over any dose text;
     *  (b) a dose string: "N x M sec|min" is a compound timed dose; a
     *      "part of …" dose is a rep-free menu step; a dose mentioning a
     *      time and NOT mentioning reps is timed at that (first) time;
     *      anything else is a rep step whose label is the dose minus
     *      the word "reps";
     *  (c) no dose at all: the CIRCUIT's timing prescribes the step —
     *      EMOM minutes, every-2-minutes stations, a flat workSec, a
     *      flat repsPerMotion, or (last resort) the catalog entry's own
     *      targetReps, falling back to "—" for an unknown movement.
     */
    private fun resolveStep(
        entryId: String,
        dose: String?,
        durationSec: Int?,
        block: String?,
        format: String?,
        workSec: Int?,
        repsPerMotion: Int?,
        entry: JsonSeed.Entry?
    ): CStep {
        if (durationSec != null) {
            return CStep(entryId, fmtSec(durationSec), durationSec, "", block)
        }
        if (!dose.isNullOrBlank()) {
            val product = productDose.find(dose)
            if (product != null) {
                val sets = product.groupValues[1].toIntOrNull() ?: 0
                val each = product.groupValues[2].toIntOrNull() ?: 0
                val per = if (product.groupValues[3].equals("min", true)) 60 else 1
                return CStep(entryId, dose, sets * each * per, "", block)
            }
            if (dose.startsWith("part of", ignoreCase = true)) {
                // Rep-free: the step is one slice of a shared time menu
                // (the warm-up circuit's "~5 min total"), so both labels
                // carry the raw dose and no rep target is invented.
                return CStep(entryId, dose, 0, dose, block)
            }
            val mention = timeMention.find(dose)
            if (mention != null && !dose.contains("rep", ignoreCase = true)) {
                val value = mention.groupValues[1].toIntOrNull() ?: 0
                val per = if (mention.groupValues[3].equals("min", true)) 60 else 1
                return CStep(entryId, dose, value * per, "", block)
            }
            val label = stripReps(dose)
            return CStep(entryId, label, 0, label, block)
        }
        return when {
            format == "EMOM" -> CStep(entryId, fmtSec(60), 60, "", block)
            format == "every 2 minutes" -> CStep(entryId, fmtSec(120), 120, "", block)
            workSec != null -> CStep(entryId, fmtSec(workSec), workSec, "", block)
            repsPerMotion != null -> CStep(entryId, "$repsPerMotion", 0, "$repsPerMotion", block)
            else -> {
                val label = entry?.targetReps?.takeIf { it.isNotBlank() }
                    ?.let { stripReps(it) }?.takeIf { it.isNotBlank() } ?: "—"
                CStep(entryId, label, 0, label, block)
            }
        }
    }

    /** First-matching-rule band assignment (see [BAND_ORDER]). The order
     *  is the point: a beginner reel is "Start Here" before it is a reel,
     *  a reel is "From Your Reels" before its length can make it "Quick
     *  & Timed", and anything the first five rules do not claim is a
     *  Nerd Fitness circuit (the only un-sourced, un-specialtied group). */
    private fun bandOf(id: String, specialty: String?, sourceType: String?, timeBoxMin: Int?, totalMinutes: Int, rawSection: String?): String {
        return when {
            specialty == "beginner" || specialty == "low-impact" || id.contains("beginner") -> "Start Here"
            sourceType == "instagram-scan" -> "From Your Reels"
            specialty == "time-boxed" || timeBoxMin != null || totalMinutes <= 10 -> "Quick & Timed"
            specialty == "warm-up" || rawSection == "warm-up" -> "Warm-Ups"
            specialty == "mobility" -> "Mobility"
            specialty == "dumbbell" -> "Dumbbell"
            specialty == "core" -> "Core"
            specialty == "hiit" || specialty == "tabata" || specialty == "bodyweight" -> "HIIT & Tabata"
            else -> "Nerd Fitness"
        }
    }

    /** Short dose summary for list rows, first matching rule wins:
     *  the box for AMRAP, the cadence for EMOM formats, the work/rest
     *  pair for interval circuits, the flat reps for per-motion Rep
     *  circuits, then the step shapes ("timed steps" / "reps-based"). */
    private fun dosePatternOf(timeBoxMin: Int?, format: String?, workSec: Int?, restSec: Int, repsPerMotion: Int?, steps: List<CStep>): String {
        return when {
            timeBoxMin != null -> "AMRAP $timeBoxMin min"
            format == "EMOM" -> "every minute"
            format == "every 2 minutes" -> "every 2 min"
            workSec != null -> "${workSec}s work" + (if (restSec > 0) " / ${restSec}s rest" else "")
            repsPerMotion != null -> "$repsPerMotion reps each"
            steps.isNotEmpty() && steps.all { it.timedSec > 0 } -> "timed steps"
            else -> "reps-based"
        }
    }

    /**
     * Parse + normalise circuits.json, once per process. Per-circuit
     * normalisation (beyond the field defaults on [Circuit]):
     *  - rounds: the raw value; else -1 (AMRAP) when the circuit is
     *    time-boxed or its format is "amrap"; else 1 (single pass —
     *    the chipper and the unstated-rounds Nerd Fitness lists).
     *  - totalMinutes: the time box itself when present; else an
     *    estimate — per round, timed steps cost their seconds and rep
     *    steps cost (leading rep count, else repsPerMotion, else 10)
     *    x 3 seconds, plus restSec between steps; rounds multiply,
     *    with roundRestSec between them; AMRAP's per-round estimate
     *    is discarded in favour of the box. Rounded to whole minutes,
     *    never below 1.
     *  - jumping: false outright for low-impact circuits; else true
     *    when any step's movement name or catalog id mentions a jump /
     *    burpee / hop / skipping, or the circuit notes say "jumping".
     */
    fun load(context: Context): List<Circuit> {
        cache?.let { return it }
        val out = mutableListOf<Circuit>()
        // Catalog lookup for this pass: step doses fall back to an
        // entry's targetReps, and jumping reads entry names.
        val entriesById = mutableMapOf<String, JsonSeed.Entry>()
        for (pd in JsonSeed.loadAll(context)) for (e in pd.all) entriesById.putIfAbsent(e.id, e)
        try {
            val text = context.assets.open("data/circuits.json").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            // sectionTags: the per-movement section membership list that
            // [motions] unions with the circuits' own step ids.
            val tags = root.optJSONArray("sectionTags")
            val circuitTagIds = mutableSetOf<String>()
            if (tags != null) {
                for (i in 0 until tags.length()) {
                    try {
                        val tag = tags.getJSONObject(i)
                        val sections = tag.optJSONArray("sections") ?: continue
                        var inCircuits = false
                        for (j in 0 until sections.length()) {
                            if (sections.optString(j) == "circuits") { inCircuits = true; break }
                        }
                        if (inCircuits) tag.strOrNull("id")?.let { circuitTagIds.add(it) }
                    } catch (e: Exception) { /* one bad tag never sinks the list */ }
                }
            }
            sectionCircuitIds = circuitTagIds
            val arr = root.optJSONArray("circuits") ?: return emptyList<Circuit>().also { cache = it }
            for (i in 0 until arr.length()) {
                try {
                    val c = arr.getJSONObject(i)
                    val id = c.strOrNull("id") ?: continue
                    val specialty = c.strOrNull("specialty")
                    val source = c.optJSONObject("source")
                    val sourceType = source?.strOrNull("type")
                    val format = c.strOrNull("format")
                    val timeBoxMin = c.intOrNull("timeBoxMin")
                    val rounds = c.intOrNull("rounds")
                        ?: if (timeBoxMin != null || format == "amrap") -1 else 1
                    val workSec = c.intOrNull("workSec")
                    val restSec = c.intOrNull("restSec") ?: 0
                    val roundRestSec = c.intOrNull("roundRestSec") ?: 60
                    val repsPerMotion = c.intOrNull("repsPerMotion")
                    val notes = c.optString("notes", "")
                    val steps = mutableListOf<CStep>()
                    val stepArr = c.optJSONArray("steps")
                    if (stepArr != null) {
                        for (j in 0 until stepArr.length()) {
                            try {
                                val s = stepArr.getJSONObject(j)
                                val entryId = s.strOrNull("id") ?: continue
                                steps.add(resolveStep(
                                    entryId = entryId,
                                    dose = s.strOrNull("dose"),
                                    durationSec = s.intOrNull("durationSec"),
                                    block = s.strOrNull("block"),
                                    format = format,
                                    workSec = workSec,
                                    repsPerMotion = repsPerMotion,
                                    entry = entriesById[entryId]
                                ))
                            } catch (e: Exception) { /* skip one malformed step */ }
                        }
                    }
                    val jumping = when {
                        // A circuit that IS low-impact never jumps,
                        // whatever its notes say (one staged circuit's
                        // notes mention "jumping jacks" only to say it
                        // avoids them — specialty or name decides).
                        specialty == "low-impact" || id.contains("low-impact") ||
                            c.optString("name", "").contains("low-impact", ignoreCase = true) -> false
                        else -> steps.any { step ->
                            val hay = ((entriesById[step.entryId]?.name ?: "") + " " + step.entryId).lowercase(Locale.US)
                            listOf("jump", "burpee", "hop", "skipping").any { hay.contains(it) }
                        } || notes.lowercase(Locale.US).contains("jumping")
                    }
                    val totalMinutes = if (timeBoxMin != null) {
                        timeBoxMin
                    } else {
                        var perRound = restSec * (steps.size - 1).coerceAtLeast(0)
                        for (step in steps) {
                            perRound += if (step.timedSec > 0) step.timedSec
                                else (leadingInt.find(step.doseLabel)?.groupValues?.get(1)?.toIntOrNull()
                                    ?: repsPerMotion ?: 10) * 3
                        }
                        val totalSec = if (rounds > 0) perRound * rounds + roundRestSec * (rounds - 1) else perRound
                        (totalSec / 60.0).roundToInt().coerceAtLeast(1)
                    }
                    out.add(Circuit(
                        id = id,
                        name = c.optString("name", ""),
                        specialty = specialty,
                        sourceType = sourceType,
                        creator = source?.strOrNull("creator"),
                        sourceRef = source?.strOrNull("ref"),
                        format = format,
                        rounds = rounds,
                        timeBoxMin = timeBoxMin,
                        workSec = workSec,
                        restSec = restSec,
                        roundRestSec = roundRestSec,
                        repsPerMotion = repsPerMotion,
                        steps = steps,
                        notes = notes,
                        jumping = jumping,
                        totalMinutes = totalMinutes,
                        dosePattern = dosePatternOf(timeBoxMin, format, workSec, restSec, repsPerMotion, steps),
                        band = bandOf(id, specialty, sourceType, timeBoxMin, totalMinutes, c.strOrNull("section"))
                    ))
                } catch (e: Exception) { /* skip one malformed circuit */ }
            }
        } catch (e: Exception) { /* a missing/corrupt circuits.json must never crash the app */ }
        cache = out
        return out
    }

    /** One circuit by id, or null when the id is not in the bundled data. */
    fun circuitById(context: Context, id: String): Circuit? =
        load(context).firstOrNull { it.id == id }

    /**
     * Every movement the Circuits section can show: the union of the
     * sectionTags ids whose sections contain "circuits" and every step
     * id used by any circuit, resolved through [JsonSeed] (unresolvable
     * ids — movements the catalog has no part-file entry for yet — are
     * dropped, never fabricated). Deduped by entry id, A–Z by entry
     * name (case-insensitive). Returns (partId, entry) pairs like
     * JsonSeed.deckEntries, so callers get the body part for free.
     */
    fun motions(context: Context): List<Pair<String, JsonSeed.Entry>> {
        val circuits = load(context) // also populates sectionCircuitIds
        val wanted = linkedSetOf<String>()
        wanted.addAll(sectionCircuitIds)
        for (circuit in circuits) for (step in circuit.steps) wanted.add(step.entryId)
        val byId = mutableMapOf<String, Pair<String, JsonSeed.Entry>>()
        for (pd in JsonSeed.loadAll(context)) for (e in pd.all) byId.putIfAbsent(e.id, pd.partId to e)
        return wanted.mapNotNull { byId[it] }
            .distinctBy { it.second.id }
            .sortedBy { it.second.name.lowercase(Locale.US) }
    }

    /** Display name of the body part a catalog entry belongs to
     *  ("Bodyweight Squat" -> "Quads"), or "" when the entry (or its
     *  part) is unknown — BodyParts.name's id fallback is deliberately
     *  NOT used here, an unknown part must read as no part. */
    fun partNameOf(context: Context, entryId: String): String {
        if (entryId.isBlank()) return ""
        for (pd in JsonSeed.loadAll(context)) {
            if (pd.all.any { it.id == entryId }) return BodyParts.byId(pd.partId)?.name ?: ""
        }
        return ""
    }

    /** The Circuits filter chips, in display order, as (label, test)
     *  pairs — the screen owns no filter logic of its own.
     *  "Rounds" means a counted-rounds circuit: AMRAPs (rounds == -1)
     *  and the minute-cadence formats run against a clock, not rounds,
     *  even when the raw data happens to carry a rounds number (the
     *  EMOM presets do — it counts their intervals). */
    fun chips(): List<Pair<String, (Circuit) -> Boolean>> = listOf(
        "All" to { _: Circuit -> true },
        "Rounds" to { c: Circuit -> c.rounds != -1 && c.format != "EMOM" && c.format != "every 2 minutes" },
        "Tabata" to { c: Circuit -> c.specialty == "tabata" || (c.workSec == 20 && c.restSec == 10) },
        "EMOM" to { c: Circuit -> c.format == "EMOM" || c.format == "every 2 minutes" },
        "AMRAP" to { c: Circuit -> c.rounds == -1 || c.format == "amrap" },
        "No jumping" to { c: Circuit -> !c.jumping }
    )
}
