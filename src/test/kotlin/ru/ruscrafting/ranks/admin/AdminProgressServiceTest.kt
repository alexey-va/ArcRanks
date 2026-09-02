package ru.ruscrafting.ranks.admin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.ranks.api.RankProgressApi
import ru.ruscrafting.ranks.domain.GoalProgress
import ru.ruscrafting.ranks.domain.GoalState
import ru.ruscrafting.ranks.domain.NextStep
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.domain.RankDefinition
import ru.ruscrafting.ranks.domain.RankEligibility
import ru.ruscrafting.ranks.domain.RankEvaluation
import ru.ruscrafting.ranks.domain.RankId
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.storage.ExternalProgressResult
import java.util.UUID
import java.util.concurrent.CompletableFuture

class AdminProgressServiceTest : StringSpec({
    val playerId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    "the admin step grants exactly the missing active minutes" {
        val api = RecordingProgressApi()
        val service = AdminProgressService(api) { "admin-menu:event-1" }

        service.advance(playerId, evaluation(NextStep.ActiveMinutes(37))).join() shouldBe
            AdminProgressAdvanceResult.Applied(ProgressMetric.ACTIVE_MINUTES, 37)
        api.calls shouldBe listOf(ProgressCall("admin-gui", "admin-menu:event-1", playerId, ProgressMetric.ACTIVE_MINUTES, 37))
    }

    "the trade step uses its additive route instead of the high-water balance metric" {
        val api = RecordingProgressApi()
        val service = AdminProgressService(api) { "admin-menu:event-2" }

        service.advance(playerId, evaluation(NextStep.PathGoal(SpecializationPath.TRADE, 19))).join() shouldBe
            AdminProgressAdvanceResult.Applied(ProgressMetric.TRADE_ACTIONS, 19)
        api.calls.single().metric shouldBe ProgressMetric.TRADE_ACTIONS
    }

    "a ready or top rank is reported without writing progress" {
        val api = RecordingProgressApi()
        val service = AdminProgressService(api) { "unused" }

        service.advance(playerId, evaluation(null, RankEligibility.READY)).join() shouldBe AdminProgressAdvanceResult.Ready
        service.advance(playerId, evaluation(null, RankEligibility.TOP_RANK)).join() shouldBe AdminProgressAdvanceResult.TopRank
        api.calls shouldBe emptyList()
    }
})

private data class ProgressCall(
    val source: String,
    val eventId: String,
    val playerId: UUID,
    val metric: ProgressMetric,
    val delta: Long,
)

private class RecordingProgressApi : RankProgressApi {
    val calls = mutableListOf<ProgressCall>()

    override fun record(
        source: String,
        eventId: String,
        playerId: UUID,
        metric: ProgressMetric,
        delta: Long,
    ): CompletableFuture<ExternalProgressResult> {
        calls += ProgressCall(source, eventId, playerId, metric, delta)
        return CompletableFuture.completedFuture(ExternalProgressResult.APPLIED)
    }
}

private fun evaluation(
    nextStep: NextStep?,
    eligibility: RankEligibility = RankEligibility.CORE_INCOMPLETE,
): RankEvaluation {
    val current = rank("settler", 1)
    val next = if (eligibility == RankEligibility.TOP_RANK) null else rank("peasant", 2)
    return RankEvaluation(
        currentRank = current,
        nextRank = next,
        eligibility = eligibility,
        activeMinutesCurrent = 0,
        activeMinutesRequired = 100,
        completedChoices = 0,
        availableChoices = 6,
        requiredChoices = 1,
        goals = SpecializationPath.entries.map { GoalProgress(it, 0, 100, GoalState.INCOMPLETE) },
        recommendation = nextStep,
    )
}

private fun rank(id: String, order: Int) = RankDefinition(
    id = RankId(id),
    luckPermsGroup = id,
    order = order,
    displayNameKey = "ranks.$id.name",
    activeMinutesRequired = order * 100L,
    requiredChoices = 1,
    pathGoals = SpecializationPath.entries.associateWith { order * 100L },
    benefitKeys = listOf("ranks.$id.benefits.1"),
)
