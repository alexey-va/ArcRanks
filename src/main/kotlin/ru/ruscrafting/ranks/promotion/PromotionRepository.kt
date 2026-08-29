package ru.ruscrafting.ranks.promotion

import ru.ruscrafting.ranks.domain.RankId
import java.util.UUID
import java.util.concurrent.CompletableFuture

enum class PromotionState {
    PREPARED,
    APPLIED,
    RETRYABLE,
    COMPLETED,
}

data class PromotionSaga(
    val playerId: UUID,
    val generation: Long,
    val from: RankId,
    val target: RankId,
    val state: PromotionState,
)

sealed interface PromotionPrepareResult {
    data class Ready(val saga: PromotionSaga, val resumed: Boolean) : PromotionPrepareResult

    data class Conflict(val saga: PromotionSaga) : PromotionPrepareResult
}

interface PromotionRepository {
    fun active(playerId: UUID): CompletableFuture<PromotionSaga?>

    fun prepare(playerId: UUID, from: RankId, target: RankId): CompletableFuture<PromotionPrepareResult>

    fun transition(saga: PromotionSaga, expected: PromotionState, target: PromotionState): CompletableFuture<Boolean>
}
