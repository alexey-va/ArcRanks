package ru.ruscrafting.ranks.kit

import ru.ruscrafting.ranks.config.GuiItemSpec
import ru.ruscrafting.ranks.domain.RankId
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import java.util.concurrent.CompletableFuture

data class WeeklyKitCycle(val start: LocalDate) {
    companion object {
        private val MOSCOW = ZoneId.of("Europe/Moscow")

        fun at(instant: Instant): WeeklyKitCycle = WeeklyKitCycle(
            instant.atZone(MOSCOW).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
        )
    }
}

data class WeeklyKitDefinition(
    val rankId: RankId,
    val kitId: String,
    val minimumFreeSlots: Int,
    val contentLines: Int,
    val icon: GuiItemSpec = GuiItemSpec("CHEST", 0),
    val summaryKey: String = "weekly-kits.${rankId.value}.summary",
    val contentKeys: List<String> = (1..contentLines).map { "weekly-kits.${rankId.value}.contents.$it" },
) {
    init {
        require(kitId.matches(Regex("[a-z0-9_]{1,64}"))) { "Unsafe CMI kit id: $kitId" }
        require(minimumFreeSlots in 1..9) { "Minimum free slots must be between 1 and 9" }
        require(contentLines in 2..8) { "Weekly kit must describe between 2 and 8 rewards" }
        require(contentKeys.size == contentLines) { "Weekly kit content key count must match content-lines" }
    }
}

data class WeeklyKitClaimRequest(
    val playerId: UUID,
    val playerName: String,
    val definition: WeeklyKitDefinition,
    val serverId: String,
    val freeSlots: Int,
) {
    init {
        require(playerName.matches(Regex("[A-Za-z0-9_]{1,16}"))) { "Unsafe player name" }
        require(serverId.matches(Regex("[a-z0-9_-]{1,40}"))) { "Unsafe server id" }
        require(freeSlots in 0..36) { "Free slot count is outside a player inventory" }
    }
}

enum class WeeklyKitClaimState {
    AVAILABLE,
    DELIVERING,
    CLAIMED,
}

data class WeeklyKitReservation(
    val claimId: UUID,
    val playerId: UUID,
    val cycle: WeeklyKitCycle,
    val rankId: RankId,
    val kitId: String,
    val serverId: String,
)

sealed interface WeeklyKitBeginResult {
    data class Ready(val reservation: WeeklyKitReservation) : WeeklyKitBeginResult
    data object AlreadyClaimed : WeeklyKitBeginResult
    data object DeliveryPending : WeeklyKitBeginResult
}

sealed interface WeeklyKitClaimResult {
    data object Claimed : WeeklyKitClaimResult
    data object AlreadyClaimed : WeeklyKitClaimResult
    data object DeliveryPending : WeeklyKitClaimResult
    data class InventoryFull(val requiredFreeSlots: Int) : WeeklyKitClaimResult
    data object ProviderRejected : WeeklyKitClaimResult
    data object StorageUnavailable : WeeklyKitClaimResult
}

sealed interface WeeklyKitAdminResetResult {
    data object Reset : WeeklyKitAdminResetResult
    data object NotClaimed : WeeklyKitAdminResetResult
    data object DeliveryPending : WeeklyKitAdminResetResult
    data object StorageUnavailable : WeeklyKitAdminResetResult
}

fun interface WeeklyKitProvider {
    fun deliver(definition: WeeklyKitDefinition, playerName: String): CompletableFuture<Boolean>
}

class WeeklyKitService(
    private val repository: WeeklyKitRepository,
    private val provider: WeeklyKitProvider,
    private val clock: Clock,
) {
    fun state(playerId: UUID): CompletableFuture<WeeklyKitClaimState> =
        repository.state(playerId, WeeklyKitCycle.at(clock.instant()))

    fun adminReset(playerId: UUID, actor: String): CompletableFuture<WeeklyKitAdminResetResult> {
        require(actor.matches(Regex("[A-Za-z0-9_.:-]{1,64}"))) { "Unsafe weekly kit admin actor" }
        return repository.adminReset(playerId, WeeklyKitCycle.at(clock.instant()), actor)
            .handle { result, failure ->
                if (failure != null || result == null) {
                    WeeklyKitAdminResetResult.StorageUnavailable
                } else when (result) {
                    WeeklyKitAdminResetStorageResult.RESET -> WeeklyKitAdminResetResult.Reset
                    WeeklyKitAdminResetStorageResult.NOT_CLAIMED -> WeeklyKitAdminResetResult.NotClaimed
                    WeeklyKitAdminResetStorageResult.DELIVERY_PENDING -> WeeklyKitAdminResetResult.DeliveryPending
                }
            }
    }

    fun claim(request: WeeklyKitClaimRequest): CompletableFuture<WeeklyKitClaimResult> {
        if (request.freeSlots < request.definition.minimumFreeSlots) {
            return CompletableFuture.completedFuture(
                WeeklyKitClaimResult.InventoryFull(request.definition.minimumFreeSlots),
            )
        }
        val cycle = WeeklyKitCycle.at(clock.instant())
        return repository.begin(
            request.playerId,
            cycle,
            request.definition.rankId,
            request.definition.kitId,
            request.serverId,
        ).handle { begun, failure ->
            if (failure != null || begun == null) null else begun
        }.thenCompose { begun ->
            when (begun) {
                null -> CompletableFuture.completedFuture(WeeklyKitClaimResult.StorageUnavailable)
                WeeklyKitBeginResult.AlreadyClaimed -> CompletableFuture.completedFuture(WeeklyKitClaimResult.AlreadyClaimed)
                WeeklyKitBeginResult.DeliveryPending -> CompletableFuture.completedFuture(WeeklyKitClaimResult.DeliveryPending)
                is WeeklyKitBeginResult.Ready -> deliver(request, begun.reservation)
            }
        }
    }

    private fun deliver(
        request: WeeklyKitClaimRequest,
        reservation: WeeklyKitReservation,
    ): CompletableFuture<WeeklyKitClaimResult> = runCatching {
        provider.deliver(request.definition, request.playerName)
    }.getOrElse { CompletableFuture.failedFuture(it) }
        .handle { accepted, failure -> if (failure == null) accepted else null }
        .thenCompose { accepted ->
            when (accepted) {
                null -> CompletableFuture.completedFuture(WeeklyKitClaimResult.DeliveryPending)
                true -> repository.confirm(reservation)
                    .handle { confirmed, failure ->
                        if (failure == null && confirmed == true) WeeklyKitClaimResult.Claimed
                        else WeeklyKitClaimResult.DeliveryPending
                    }
                false -> repository.release(reservation)
                    .handle { released, failure ->
                        if (failure == null && released == true) WeeklyKitClaimResult.ProviderRejected
                        else WeeklyKitClaimResult.DeliveryPending
                    }
            }
        }
}
