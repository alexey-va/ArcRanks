package ru.ruscrafting.ranks.domain

@JvmInline
value class RankId(val value: String) {
    init {
        require(value.matches(Regex("[a-z0-9_-]{1,40}"))) { "Unsafe rank id: $value" }
    }

    override fun toString(): String = value
}

data class RankDefinition(
    val id: RankId,
    val luckPermsGroup: String,
    val order: Int,
    val displayNameKey: String,
    val activeMinutesRequired: Long,
    val requiredChoices: Int,
    val pathGoals: Map<SpecializationPath, Long>,
    val benefitKeys: List<String>,
) {
    init {
        require(luckPermsGroup.matches(Regex("[a-z0-9_-]{1,64}"))) { "Unsafe LuckPerms group: $luckPermsGroup" }
        require(order > 0) { "Rank order must be positive" }
        require(displayNameKey.isNotBlank()) { "Rank display name key must not be blank" }
        require(activeMinutesRequired >= 0) { "Active minutes requirement must not be negative" }
        require(pathGoals.keys == SpecializationPath.entries.toSet()) { "Every rank must define all progress paths" }
        require(pathGoals.values.none { it < 0 }) { "Path goals must not be negative" }
        require(requiredChoices in 0..pathGoals.size) { "Required choices must fit the progress path count" }
        require(benefitKeys.isNotEmpty() && benefitKeys.none(String::isBlank)) { "Every rank needs player-facing benefits" }
    }
}

class RankCatalog(definitions: List<RankDefinition>) {
    val ranks: List<RankDefinition> = definitions.sortedBy(RankDefinition::order)
    private val byId = ranks.associateBy(RankDefinition::id)
    private val byGroup = ranks.associateBy(RankDefinition::luckPermsGroup)

    init {
        require(ranks.isNotEmpty()) { "Rank catalog must not be empty" }
        require(byId.size == ranks.size) { "Rank ids must be unique" }
        require(ranks.map(RankDefinition::luckPermsGroup).distinct().size == ranks.size) {
            "LuckPerms progression groups must be unique"
        }
        require(ranks.map(RankDefinition::order) == (1..ranks.size).toList()) { "Rank order must be contiguous from one" }
        ranks.zipWithNext().forEach { (previous, next) ->
            require(next.activeMinutesRequired >= previous.activeMinutesRequired) { "Active time requirements must not decrease" }
            SpecializationPath.entries.forEach { path ->
                require(checkNotNull(next.pathGoals[path]) >= checkNotNull(previous.pathGoals[path])) {
                    "Path requirement ${path.name} must not decrease"
                }
            }
        }
    }

    fun require(id: RankId): RankDefinition = requireNotNull(byId[id]) { "Unknown rank id: $id" }

    fun byGroup(group: String): RankDefinition? = byGroup[group]

    fun next(id: RankId): RankDefinition? = ranks.getOrNull(require(id).order)
}
