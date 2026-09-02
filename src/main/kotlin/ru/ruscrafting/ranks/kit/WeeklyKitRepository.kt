package ru.ruscrafting.ranks.kit

import ru.ruscrafting.ranks.domain.RankId
import java.util.UUID
import java.util.concurrent.CompletableFuture

enum class WeeklyKitAdminResetStorageResult {
    RESET,
    NOT_CLAIMED,
    DELIVERY_PENDING,
}

interface WeeklyKitRepository {
    fun state(playerId: UUID, cycle: WeeklyKitCycle): CompletableFuture<WeeklyKitClaimState>
    fun begin(
        playerId: UUID,
        cycle: WeeklyKitCycle,
        rankId: RankId,
        kitId: String,
        serverId: String,
    ): CompletableFuture<WeeklyKitBeginResult>
    fun confirm(reservation: WeeklyKitReservation): CompletableFuture<Boolean>
    fun release(reservation: WeeklyKitReservation): CompletableFuture<Boolean>
    fun adminReset(
        playerId: UUID,
        cycle: WeeklyKitCycle,
        actor: String,
    ): CompletableFuture<WeeklyKitAdminResetStorageResult>
}
