package ru.ruscrafting.ranks.domain

enum class GoalState {
    COMPLETE,
    INCOMPLETE,
    UNAVAILABLE,
}

data class GoalProgress(
    val path: SpecializationPath,
    val current: Long,
    val required: Long,
    val state: GoalState,
)

enum class RankEligibility {
    READY,
    CORE_INCOMPLETE,
    CHOICES_INCOMPLETE,
    INSUFFICIENT_AVAILABLE_PATHS,
    TOP_RANK,
}

sealed interface NextStep {
    data class ActiveMinutes(val remaining: Long) : NextStep

    data class PathGoal(val path: SpecializationPath, val remaining: Long) : NextStep
}

data class RankEvaluation(
    val currentRank: RankDefinition,
    val nextRank: RankDefinition?,
    val eligibility: RankEligibility,
    val activeMinutesCurrent: Long,
    val activeMinutesRequired: Long,
    val completedChoices: Int,
    val availableChoices: Int,
    val requiredChoices: Int,
    val goals: List<GoalProgress>,
    val recommendation: NextStep?,
)

class RankEvaluator(private val catalog: RankCatalog) {
    fun evaluate(
        currentRankId: RankId,
        progress: ProgressSnapshot,
        availability: PathAvailability,
    ): RankEvaluation {
        val current = catalog.require(currentRankId)
        val next = catalog.next(currentRankId)
            ?: return RankEvaluation(
                currentRank = current,
                nextRank = null,
                eligibility = RankEligibility.TOP_RANK,
                activeMinutesCurrent = progress.value(ProgressMetric.ACTIVE_MINUTES),
                activeMinutesRequired = current.activeMinutesRequired,
                completedChoices = 0,
                availableChoices = 0,
                requiredChoices = 0,
                goals = emptyList(),
                recommendation = null,
            )

        val goals = SpecializationPath.entries.map { path ->
            val currentValue = progress.value(path.metric)
            val required = checkNotNull(next.pathGoals[path])
            GoalProgress(
                path = path,
                current = currentValue,
                required = required,
                state = when {
                    !availability.isAvailable(path) -> GoalState.UNAVAILABLE
                    currentValue >= required -> GoalState.COMPLETE
                    else -> GoalState.INCOMPLETE
                },
            )
        }
        val availableChoices = goals.count { it.state != GoalState.UNAVAILABLE }
        val completedChoices = goals.count { it.state == GoalState.COMPLETE }
        val activeCurrent = progress.value(ProgressMetric.ACTIVE_MINUTES)
        val eligibility = when {
            availableChoices < next.requiredChoices -> RankEligibility.INSUFFICIENT_AVAILABLE_PATHS
            activeCurrent < next.activeMinutesRequired -> RankEligibility.CORE_INCOMPLETE
            completedChoices < next.requiredChoices -> RankEligibility.CHOICES_INCOMPLETE
            else -> RankEligibility.READY
        }
        val recommendation = when (eligibility) {
            RankEligibility.CORE_INCOMPLETE -> NextStep.ActiveMinutes(next.activeMinutesRequired - activeCurrent)
            RankEligibility.CHOICES_INCOMPLETE -> goals
                .asSequence()
                .filter { it.state == GoalState.INCOMPLETE }
                .minWithOrNull(
                    compareBy<GoalProgress> { normalizedRemaining(it) }
                        .thenBy { it.path.ordinal },
                )
                ?.let { NextStep.PathGoal(it.path, it.required - it.current) }
            else -> null
        }
        return RankEvaluation(
            currentRank = current,
            nextRank = next,
            eligibility = eligibility,
            activeMinutesCurrent = activeCurrent,
            activeMinutesRequired = next.activeMinutesRequired,
            completedChoices = completedChoices,
            availableChoices = availableChoices,
            requiredChoices = next.requiredChoices,
            goals = goals,
            recommendation = recommendation,
        )
    }

    private fun normalizedRemaining(goal: GoalProgress): Double =
        if (goal.required == 0L) 0.0 else (goal.required - goal.current).toDouble() / goal.required.toDouble()
}
