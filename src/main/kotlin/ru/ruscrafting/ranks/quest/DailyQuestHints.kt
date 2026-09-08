package ru.ruscrafting.ranks.quest

/** Localized explanations follow the counted action, including the current chain stage. */
object DailyQuestHints {
    val CATEGORIES = setOf("harvest", "fish", "breed", "craft", "smelt", "enchant", "smith", "trade", "build",
        "decorate", "travel", "advancement", "active", "community", "farm-job", "lumber-job", "mine-job",
        "builder", "dungeon", "contract", "vote", "discord", "telegram", "chain", "all", "any", "distinct", "team")

    fun categories(state: DailyQuestProgress): List<String> {
        val plan = state.quest.plan ?: return listOfNotNull(category(state.quest.objective))
        val mode = plan.mode.name.lowercase()
        if (plan.mode == QuestMode.CHAIN) {
            val index = plan.steps.indices.firstOrNull { state.stepValues[it] < plan.steps[it].target }
            return listOfNotNull(mode, index?.let { category(plan.steps[it].objective) }).distinct()
        }
        return if (plan.steps.any { it.objective.endsWith(":team") }) listOf(mode, "team") else listOf(mode)
    }

    private fun category(objective: String): String? = when (val base = objective.substringBefore(':')) {
        "farm.job" -> "farm-job"
        "lumber.job" -> "lumber-job"
        "mine.job" -> "mine-job"
        "builder.blocks", "builder.use" -> "builder"
        "dungeon.complete" -> "dungeon"
        "contract.items", "contract.delivery" -> "contract"
        "vote.confirmed" -> "vote"
        "account.discord" -> "discord"
        "account.telegram" -> "telegram"
        else -> base.takeIf { it in CATEGORIES }
    }
}
