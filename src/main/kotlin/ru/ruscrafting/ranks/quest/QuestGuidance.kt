package ru.ruscrafting.ranks.quest

/** Suggestions compare completion fractions, never raw quantities from different activities. */
object QuestGuidance {
    fun progressPermille(state: DailyQuestProgress): Long {
        val plan = state.quest.plan ?: return state.value * 1000 / state.quest.target
        val fractions = plan.steps.mapIndexed { index, step -> state.stepValues[index] * 1000 / step.target }
        return when (plan.mode) {
            QuestMode.ANY -> fractions.max()
            QuestMode.DISTINCT -> fractions.sortedDescending().take(plan.requiredDistinct).sum() / plan.requiredDistinct
            else -> fractions.sum() / fractions.size
        }
    }

    fun suggestion(board: DailyQuestBoard): String? = board.quests.asSequence()
        .filter { !it.completed && it.quest.id !in board.unavailableQuestIds && progressPermille(it) >= 800 }
        .maxByOrNull(::progressPermille)?.quest?.id
}
