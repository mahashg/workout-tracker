package com.mahesh.workouttracker

/** v3 model: the canonical body parts. Data (the workouts per part) lives in
 *  assets/data/<id>.json — model in code, data in JSON.
 *
 *  v3.1: [Part.compatibleWith] is the symmetric pairing matrix used by the
 *  Start Workout picker to highlight parts that pair well with the ones
 *  already picked (synergist / same-session-friendly pairings). Core pairs
 *  with everything, so every part also lists "core". */
object BodyParts {
    data class Part(val id: String, val name: String, val repeatable: Boolean, val compatibleWith: Set<String>)
    val all: List<Part> = listOf(
        Part("shoulders", "Shoulders", false, setOf("chest", "triceps", "traps", "core")),
        Part("traps", "Traps", false, setOf("shoulders", "back", "core")),
        Part("chest", "Chest", false, setOf("triceps", "shoulders", "core")),
        Part("triceps", "Triceps", false, setOf("chest", "shoulders", "core")),
        Part("back", "Back", false, setOf("biceps", "traps", "core")),
        Part("biceps", "Biceps", false, setOf("back", "core")),
        Part("quads", "Quads", false, setOf("hamstrings", "glutes", "calves", "core")),
        Part("hamstrings", "Hamstrings", false, setOf("quads", "glutes", "calves", "core")),
        Part("glutes", "Glutes", false, setOf("quads", "hamstrings", "core")),
        Part("calves", "Calves", false, setOf("quads", "hamstrings", "core")),
        // repeatable: never locked by the week view; pairs with all parts
        Part("core", "Core", true, setOf("shoulders", "traps", "chest", "triceps", "back", "biceps", "quads", "hamstrings", "glutes", "calves"))
    )
    val ids: List<String> get() = all.map { it.id }
    fun byId(id: String): Part? = all.firstOrNull { it.id.equals(id, ignoreCase = true) }
    fun name(id: String): String = byId(id)?.name ?: id
    /** True when [a] pairs well with [b] per the matrix (symmetric). */
    fun compatible(a: String, b: String): Boolean = byId(a)?.compatibleWith?.contains(b.lowercase()) == true
}
