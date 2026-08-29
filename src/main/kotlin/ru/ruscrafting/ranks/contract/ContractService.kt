package ru.ruscrafting.ranks.contract

import ru.ruscrafting.ranks.analytics.PlayerSignal
import ru.ruscrafting.ranks.analytics.ProductDimension
import ru.ruscrafting.ranks.analytics.ProductEvent
import ru.ruscrafting.ranks.analytics.ProductTelemetry
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture

sealed interface ContractAcceptResult {
    data class Accepted(val contract: ActiveContract) : ContractAcceptResult
    data class AlreadyActive(val contract: ActiveContract) : ContractAcceptResult
    data object OfferUnavailable : ContractAcceptResult
    data object CycleComplete : ContractAcceptResult
    data object StorageUnavailable : ContractAcceptResult
}

sealed interface ContractClaimResult {
    data class Claimed(val contract: ActiveContract) : ContractClaimResult
    data class NotReady(val contract: ActiveContract) : ContractClaimResult
    data class AlreadyClaimed(val contract: ActiveContract) : ContractClaimResult
    data object NoActive : ContractClaimResult
    data object StorageUnavailable : ContractClaimResult
}

sealed interface ContractRerollResult {
    data class Rerolled(val board: ContractBoard) : ContractRerollResult
    data class Active(val contract: ActiveContract) : ContractRerollResult
    data object AlreadyUsed : ContractRerollResult
    data object CycleComplete : ContractRerollResult
    data object StorageUnavailable : ContractRerollResult
}

class ContractService(
    private val repository: ContractRepository,
    private val generator: ContractOfferGenerator,
    private val clock: Clock,
    private val telemetry: ProductTelemetry? = null,
    private val flushProgress: (UUID) -> CompletableFuture<Unit> = { CompletableFuture.completedFuture(Unit) },
) {
    fun board(playerId: UUID, context: ContractPlayerContext): CompletableFuture<ContractBoard> {
        return loadBoard(playerId, context, recordOfferImpressions = true)
    }

    private fun loadBoard(
        playerId: UUID,
        context: ContractPlayerContext,
        recordOfferImpressions: Boolean,
    ): CompletableFuture<ContractBoard> {
        val cycle = ContractCycle.at(clock.instant())
        return repository.state(playerId, cycle).thenApply { stored ->
            buildBoard(playerId, context, stored, recordOfferImpressions)
        }
    }

    fun accept(
        playerId: UUID,
        offerId: ContractId,
        context: ContractPlayerContext,
    ): CompletableFuture<ContractAcceptResult> = loadBoard(playerId, context, recordOfferImpressions = false).thenCompose { board ->
        val offer = board.offers.firstOrNull { it.id == offerId }
            ?: return@thenCompose CompletableFuture.completedFuture(
                ContractAcceptResult.OfferUnavailable.also { rejected("accept:offer_unavailable") },
            )
        repository.accept(playerId, offer).thenApply { result ->
            when (result) {
                is ContractAcceptStorageResult.Accepted -> {
                    telemetry?.record(ProductEvent.CONTRACT_ACCEPTED, ProductDimension("path:${result.contract.path.name.lowercase()}"))
                    telemetry?.recordPlayer(playerId, PlayerSignal.CONTRACT_ACCEPTED)
                    ContractAcceptResult.Accepted(result.contract)
                }
                is ContractAcceptStorageResult.AlreadyActive -> ContractAcceptResult.AlreadyActive(result.contract).also { rejected("accept:active") }
                ContractAcceptStorageResult.OfferStale -> ContractAcceptResult.OfferUnavailable.also { rejected("accept:stale") }
                ContractAcceptStorageResult.CycleComplete -> ContractAcceptResult.CycleComplete.also { rejected("accept:complete") }
            }
        }
    }.exceptionally { ContractAcceptResult.StorageUnavailable.also { rejected("accept:storage") } }

    fun claim(playerId: UUID): CompletableFuture<ContractClaimResult> {
        val cycle = ContractCycle.at(clock.instant())
        return flushProgress(playerId).thenCompose { repository.claim(playerId, cycle) }.thenApply { result ->
            when (result) {
                is ContractClaimStorageResult.Claimed -> {
                    telemetry?.record(ProductEvent.CONTRACT_CLAIMED, ProductDimension("path:${result.contract.path.name.lowercase()}"))
                    telemetry?.recordPlayer(playerId, PlayerSignal.CONTRACT_COMPLETED)
                    ContractClaimResult.Claimed(result.contract)
                }
                is ContractClaimStorageResult.NotReady -> ContractClaimResult.NotReady(result.contract).also { rejected("claim:not_ready") }
                is ContractClaimStorageResult.AlreadyClaimed -> ContractClaimResult.AlreadyClaimed(result.contract).also { rejected("claim:already_claimed") }
                ContractClaimStorageResult.NoActive -> ContractClaimResult.NoActive.also { rejected("claim:no_active") }
            }
        }.exceptionally { ContractClaimResult.StorageUnavailable.also { rejected("claim:storage") } }
    }

    fun reroll(playerId: UUID, context: ContractPlayerContext): CompletableFuture<ContractRerollResult> {
        val cycle = ContractCycle.at(clock.instant())
        return repository.reroll(playerId, cycle).thenCompose { result ->
            when (result) {
                is ContractRerollStorageResult.Rerolled -> repository.state(playerId, cycle).thenApply { stored ->
                    telemetry?.record(ProductEvent.CONTRACT_REROLLED, ProductDimension.NONE)
                    ContractRerollResult.Rerolled(buildBoard(playerId, context, stored, recordOfferImpressions = false))
                }
                is ContractRerollStorageResult.Active -> CompletableFuture.completedFuture(ContractRerollResult.Active(result.contract).also { rejected("reroll:active") })
                ContractRerollStorageResult.AlreadyUsed -> CompletableFuture.completedFuture(ContractRerollResult.AlreadyUsed.also { rejected("reroll:used") })
                ContractRerollStorageResult.CycleComplete -> CompletableFuture.completedFuture(ContractRerollResult.CycleComplete.also { rejected("reroll:complete") })
            }
        }.exceptionally { ContractRerollResult.StorageUnavailable.also { rejected("reroll:storage") } }
    }

    private fun buildBoard(
        playerId: UUID,
        context: ContractPlayerContext,
        stored: ContractStoredBoard,
        recordOfferImpressions: Boolean,
    ): ContractBoard {
        if (stored.expiredOnLoad) telemetry?.record(ProductEvent.CONTRACT_EXPIRED, ProductDimension.NONE)
        val offers = if (stored.active == null) {
            generator.offers(playerId, stored.cycle, stored.generation, stored.rerollNonce, context)
        } else {
            emptyList()
        }
        if (recordOfferImpressions) {
            offers.forEach { telemetry?.record(ProductEvent.CONTRACT_OFFERED, ProductDimension("path:${it.path.name.lowercase()}")) }
        }
        return ContractBoard(
            stored.cycle,
            stored.generation,
            stored.claimedStamps,
            stored.active,
            offers,
            stored.active == null && stored.generation < ContractOffer.MAX_CONTRACTS_PER_CYCLE && stored.rerollNonce == 0,
        )
    }

    private fun rejected(reason: String) {
        telemetry?.record(ProductEvent.CONTRACT_REJECTED, ProductDimension(reason))
    }
}
