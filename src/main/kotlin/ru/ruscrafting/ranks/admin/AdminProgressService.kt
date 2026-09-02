package ru.ruscrafting.ranks.admin

import ru.ruscrafting.ranks.api.RankProgressApi
import ru.ruscrafting.ranks.domain.NextStep
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.domain.RankEligibility
import ru.ruscrafting.ranks.domain.RankEvaluation
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.storage.ExternalProgressResult
import java.util.UUID
import java.util.concurrent.CompletableFuture

sealed interface AdminProgressAdvanceResult {
    data class Applied(val metric: ProgressMetric, val amount: Long) : AdminProgressAdvanceResult
    data object Duplicate : AdminProgressAdvanceResult
    data object Ready : AdminProgressAdvanceResult
    data object TopRank : AdminProgressAdvanceResult
}

class AdminProgressService(
    private val progress: RankProgressApi,
    private val eventId: () -> String = { "admin-menu:${UUID.randomUUID()}" },
) {
    fun advance(playerId: UUID, evaluation: RankEvaluation): CompletableFuture<AdminProgressAdvanceResult> {
        val step = evaluation.recommendation ?: return CompletableFuture.completedFuture(
            if (evaluation.eligibility == RankEligibility.TOP_RANK) {
                AdminProgressAdvanceResult.TopRank
            } else {
                AdminProgressAdvanceResult.Ready
            },
        )
        val (metric, amount) = when (step) {
            is NextStep.ActiveMinutes -> ProgressMetric.ACTIVE_MINUTES to step.remaining
            is NextStep.PathGoal -> step.path.additiveMetric() to step.remaining
        }
        return progress.record("admin-gui", eventId(), playerId, metric, amount).thenApply { result ->
            if (result == ExternalProgressResult.APPLIED) {
                AdminProgressAdvanceResult.Applied(metric, amount)
            } else {
                AdminProgressAdvanceResult.Duplicate
            }
        }
    }
}

private fun SpecializationPath.additiveMetric(): ProgressMetric =
    if (metric == ProgressMetric.WEALTH_PEAK) {
        checkNotNull(alternativeMetrics.firstOrNull()) { "Path $name has no additive admin progress route" }
    } else {
        metric
    }
