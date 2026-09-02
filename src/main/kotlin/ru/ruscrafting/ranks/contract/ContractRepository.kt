package ru.ruscrafting.ranks.contract

import java.util.UUID
import java.util.concurrent.CompletableFuture

sealed interface ContractAcceptStorageResult {
    data class Accepted(val contract: ActiveContract) : ContractAcceptStorageResult
    data class AlreadyActive(val contract: ActiveContract) : ContractAcceptStorageResult
    data object OfferStale : ContractAcceptStorageResult
    data object CycleComplete : ContractAcceptStorageResult
}

sealed interface ContractClaimStorageResult {
    data class Claimed(val contract: ActiveContract) : ContractClaimStorageResult
    data class NotReady(val contract: ActiveContract) : ContractClaimStorageResult
    data class AlreadyClaimed(val contract: ActiveContract) : ContractClaimStorageResult
    data object NoActive : ContractClaimStorageResult
}

sealed interface ContractRerollStorageResult {
    data class Rerolled(val nonce: Int) : ContractRerollStorageResult
    data class Active(val contract: ActiveContract) : ContractRerollStorageResult
    data object AlreadyUsed : ContractRerollStorageResult
    data object CycleComplete : ContractRerollStorageResult
}

sealed interface ContractAdminCompleteStorageResult {
    data class Completed(val contract: ActiveContract) : ContractAdminCompleteStorageResult
    data class AlreadyReady(val contract: ActiveContract) : ContractAdminCompleteStorageResult
    data object NoActive : ContractAdminCompleteStorageResult
}

interface ContractRepository {
    fun state(playerId: UUID, cycle: ContractCycle): CompletableFuture<ContractStoredBoard>
    fun accept(playerId: UUID, offer: ContractOffer): CompletableFuture<ContractAcceptStorageResult>
    fun claim(playerId: UUID, cycle: ContractCycle): CompletableFuture<ContractClaimStorageResult>
    fun reroll(playerId: UUID, cycle: ContractCycle): CompletableFuture<ContractRerollStorageResult>
    fun adminComplete(
        playerId: UUID,
        cycle: ContractCycle,
        actor: String,
    ): CompletableFuture<ContractAdminCompleteStorageResult>
    fun pendingRewards(playerId: UUID): CompletableFuture<List<ActiveContract>>
    fun markRewardGranted(playerId: UUID, contractId: ContractId): CompletableFuture<Boolean>
    fun markRewardRecovery(playerId: UUID, contractId: ContractId, failureCode: String): CompletableFuture<Boolean>
}
