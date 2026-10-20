package ru.ruscrafting.ranks.config

/** A visit to a public mechanic, never an automatic purchase, job start or dungeon admission. */
data class QuestTravelLocation(val objectives: Set<String>, val destination: DungeonQuestDestination) {
    init {
        require(objectives.isNotEmpty() && objectives.all { it.matches(Regex("[a-z0-9_.:-]{1,96}")) }) {
            "Quest travel locations need safe objective selectors"
        }
    }
}

/** Specific contract/job selectors override broad choices; equally specific locations remain choices. */
fun Map<String, QuestTravelLocation>.locationsFor(objective: String): List<String> {
    val matches = mapNotNull { (id, location) ->
        location.objectives.filter { objective == it || objective.startsWith("$it:") }
            .maxOfOrNull(String::length)?.let { id to it }
    }
    val specificity = matches.maxOfOrNull { it.second } ?: return emptyList()
    return matches.filter { it.second == specificity }.map { it.first }
}
