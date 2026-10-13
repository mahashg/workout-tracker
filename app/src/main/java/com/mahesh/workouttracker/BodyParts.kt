package com.mahesh.workouttracker

/** v3 model: the canonical body parts. Data (the workouts per part) lives in
 *  assets/data/<id>.json — model in code, data in JSON. */
object BodyParts {
    data class Part(val id: String, val name: String, val repeatable: Boolean)
    val all: List<Part> = listOf(
        Part("shoulders", "Shoulders", false),
        Part("traps", "Traps", false),
        Part("chest", "Chest", false),
        Part("triceps", "Triceps", false),
        Part("back", "Back", false),
        Part("biceps", "Biceps", false),
        Part("quads", "Quads", false),
        Part("hamstrings", "Hamstrings", false),
        Part("glutes", "Glutes", false),
        Part("calves", "Calves", false),
        Part("core", "Core", true) // repeatable: never locked by the week view
    )
    val ids: List<String> get() = all.map { it.id }
    fun byId(id: String): Part? = all.firstOrNull { it.id.equals(id, ignoreCase = true) }
    fun name(id: String): String = byId(id)?.name ?: id
}
