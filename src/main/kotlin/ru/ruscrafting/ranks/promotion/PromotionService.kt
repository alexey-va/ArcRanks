package ru.ruscrafting.ranks.promotion

import ru.ruscrafting.ranks.analytics.ProductDimension
import ru.ruscrafting.ranks.analytics.ProductEvent
import ru.ruscrafting.ranks.analytics.ProductTelemetry
import ru.ruscrafting.ranks.domain.PathAvailability
import ru.ruscrafting.ranks.domain.RankCatalog
import ru.ruscrafting.ranks.domain.RankEligibility
import ru.ruscrafting.ranks.domain.RankEvaluation
import ru.ruscrafting.ranks.domain.RankEvaluator
import ru.ruscrafting.ranks.domain.RankId
import ru.ruscrafting.ranks.progress.ProgressBuffer
import ru.ruscrafting.ranks.rankstate.RankReplaceResult
import ru.ruscrafting.ranks.rankstate.RankState
import ru.ruscrafting.ranks.rankstate.RankStateGateway
import ru.ruscrafting.ranks.storage.ProgressRepository
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

sealed interface PromotionResult {
    data class Promoted(val rankId: RankId) : PromotionResult

    data class Recovered(val rankId: RankId) : PromotionResult

    data class NotEligible(val evaluation: RankEvaluation) : PromotionResult

    data class RankStateProblem(val state: RankState) : PromotionResult

    data object TopRank : PromotionResult

    data object Busy : PromotionResult

    data object Retryable : PromotionResult
}

data class PromotionConfiguration(
    val catalog: RankCatalog,
    val evaluator: RankEvaluator,
)

class PromotionService(
    private val configuration: () -> PromotionConfiguration,
    private val progress: ProgressRepository,
    private val buffer: ProgressBuffer,
    private val rankState: RankStateGateway,
    private val promotions: PromotionRepository,
    private val availability: () -> PathAvailability,
    private val celebrate: (UUID, RankId) -> Unit,
    private val telemetry: ProductTelemetry? = null,
) {
    constructor(
        catalog: RankCatalog,
        evaluator: RankEvaluator,
        progress: ProgressRepository,
        buffer: ProgressBuffer,
        rankState: RankStateGateway,
        promotions: PromotionRepository,
        availability: () -> PathAvailability,
        celebrate: (UUID, RankId) -> Unit,
        telemetry: ProductTelemetry? = null,
    ) : this(
        configuration = { PromotionConfiguration(catalog, evaluator) },
        progress = progress,
        buffer = buffer,
        rankState = rankState,
        promotions = promotions,
        availability = availability,
        celebrate = celebrate,
        telemetry = telemetry,
    )

    private val inFlight = ConcurrentHashMap.newKeySet<UUID>()

    fun promote(playerId: UUID): CompletableFuture<PromotionResult> {
        telemetry?.record(ProductEvent.PROMOTION_ATTEMPT, ProductDimension.NONE)
        if (!inFlight.add(playerId)) {
            telemetry?.record(ProductEvent.PROMOTION_BLOCKED, ProductDimension("result:busy"))
            return CompletableFuture.completedFuture(PromotionResult.Busy)
        }
        val currentConfiguration = try {
            configuration()
        } catch (_: Exception) {
            inFlight.remove(playerId)
            telemetry?.record(ProductEvent.PROMOTION_BLOCKED, ProductDimension("result:retryable"))
            return CompletableFuture.completedFuture(PromotionResult.Retryable)
        }
        val operation = buffer.flush(playerId)
            .thenCompose { promotions.active(playerId) }
            .thenCompose { active -> if (active == null) begin(playerId, currentConfiguration) else recover(active) }
            .exceptionally { PromotionResult.Retryable }
        return operation.whenComplete { result, _ ->
            inFlight.remove(playerId)
            when (result) {
                is PromotionResult.Promoted, is PromotionResult.Recovered ->
                    telemetry?.record(ProductEvent.PROMOTION_SUCCESS, ProductDimension("result:success"))
                is PromotionResult.NotEligible ->
                    telemetry?.record(ProductEvent.PROMOTION_BLOCKED, ProductDimension("result:not_eligible"))
                is PromotionResult.RankStateProblem ->
                    telemetry?.record(ProductEvent.PROMOTION_BLOCKED, ProductDimension("result:rank_state"))
                PromotionResult.TopRank ->
                    telemetry?.record(ProductEvent.PROMOTION_BLOCKED, ProductDimension("result:top"))
                PromotionResult.Busy ->
                    telemetry?.record(ProductEvent.PROMOTION_BLOCKED, ProductDimension("result:busy"))
                PromotionResult.Retryable, null ->
                    telemetry?.record(ProductEvent.PROMOTION_BLOCKED, ProductDimension("result:retryable"))
            }
        }
    }

    private fun begin(
        playerId: UUID,
        configuration: PromotionConfiguration,
    ): CompletableFuture<PromotionResult> = rankState.load(playerId).thenCompose { state ->
        val exact = state as? RankState.Exact
            ?: return@thenCompose CompletableFuture.completedFuture(PromotionResult.RankStateProblem(state))
        val next = configuration.catalog.next(exact.rankId)
            ?: return@thenCompose CompletableFuture.completedFuture(PromotionResult.TopRank)
        progress.load(playerId).thenCompose { profile ->
            val evaluation = configuration.evaluator.evaluate(exact.rankId, profile.progress, availability())
            if (evaluation.eligibility != RankEligibility.READY) {
                return@thenCompose CompletableFuture.completedFuture(PromotionResult.NotEligible(evaluation))
            }
            promotions.prepare(playerId, exact.rankId, next.id).thenCompose { prepared ->
                when (prepared) {
                    is PromotionPrepareResult.Conflict -> CompletableFuture.completedFuture(PromotionResult.Busy)
                    is PromotionPrepareResult.Ready -> apply(prepared.saga, celebrateOnSuccess = true)
                }
            }
        }
    }

    private fun recover(saga: PromotionSaga): CompletableFuture<PromotionResult> = rankState.load(saga.playerId).thenCompose { state ->
        when (state) {
            RankState.Exact(saga.target) -> complete(saga, celebrateOnSuccess = false, recovered = true)
            RankState.Exact(saga.from) -> apply(saga, celebrateOnSuccess = true)
            else -> CompletableFuture.completedFuture(PromotionResult.RankStateProblem(state))
        }
    }

    private fun apply(
        saga: PromotionSaga,
        celebrateOnSuccess: Boolean,
    ): CompletableFuture<PromotionResult> = rankState.replaceExact(saga.playerId, saga.from, saga.target).thenCompose { result ->
        when (result) {
            RankReplaceResult.APPLIED -> promotions.transition(saga, saga.state, PromotionState.APPLIED).thenCompose { transitioned ->
                if (!transitioned) CompletableFuture.completedFuture(PromotionResult.Retryable)
                else complete(saga.copy(state = PromotionState.APPLIED), celebrateOnSuccess, recovered = false)
            }
            else -> rankState.load(saga.playerId).thenCompose { observed ->
                if (observed == RankState.Exact(saga.target)) {
                    complete(saga, celebrateOnSuccess = false, recovered = true)
                } else {
                    markRetryable(saga).thenApply { PromotionResult.Retryable }
                }
            }
        }
    }

    private fun complete(
        saga: PromotionSaga,
        celebrateOnSuccess: Boolean,
        recovered: Boolean,
    ): CompletableFuture<PromotionResult> {
        val expected = saga.state
        return promotions.transition(saga, expected, PromotionState.COMPLETED).thenApply { transitioned ->
            if (!transitioned) PromotionResult.Retryable
            else if (recovered) PromotionResult.Recovered(saga.target)
            else {
                if (celebrateOnSuccess) celebrate(saga.playerId, saga.target)
                PromotionResult.Promoted(saga.target)
            }
        }
    }

    private fun markRetryable(saga: PromotionSaga): CompletableFuture<Boolean> =
        if (saga.state == PromotionState.RETRYABLE) CompletableFuture.completedFuture(true)
        else promotions.transition(saga, saga.state, PromotionState.RETRYABLE)
}
