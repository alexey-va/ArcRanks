package ru.ruscrafting.ranks.kit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.ranks.domain.RankId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture

class WeeklyKitServiceTest : StringSpec({
    val playerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val definition = WeeklyKitDefinition(RankId("citizen"), "arcranks_weekly_citizen", 4, 3)
    val clock = Clock.fixed(Instant.parse("2026-08-30T18:00:00Z"), ZoneOffset.UTC)

    "Moscow week begins on Monday across the UTC boundary" {
        WeeklyKitCycle.at(Instant.parse("2026-08-30T20:59:59Z")).start.toString() shouldBe "2026-08-24"
        WeeklyKitCycle.at(Instant.parse("2026-08-30T21:00:00Z")).start.toString() shouldBe "2026-08-31"
    }

    "inventory capacity is rejected before a durable reservation" {
        val repository = FakeWeeklyKitRepository()
        val service = WeeklyKitService(repository, { _, _ -> CompletableFuture.completedFuture(true) }, clock)

        service.claim(request(playerId, definition, freeSlots = 3)).join() shouldBe WeeklyKitClaimResult.InventoryFull(4)
        repository.beginCalls shouldBe 0
    }

    "duplicate and in-flight claims never dispatch CMI again" {
        val repository = FakeWeeklyKitRepository()
        var deliveries = 0
        val service = WeeklyKitService(repository, { _, _ ->
            deliveries++
            CompletableFuture.completedFuture(true)
        }, clock)

        repository.nextBegin = WeeklyKitBeginResult.AlreadyClaimed
        service.claim(request(playerId, definition)).join() shouldBe WeeklyKitClaimResult.AlreadyClaimed
        repository.nextBegin = WeeklyKitBeginResult.DeliveryPending
        service.claim(request(playerId, definition)).join() shouldBe WeeklyKitClaimResult.DeliveryPending
        deliveries shouldBe 0
    }

    "explicit provider refusal releases only its reservation" {
        val repository = FakeWeeklyKitRepository()
        val service = WeeklyKitService(repository, { _, _ -> CompletableFuture.completedFuture(false) }, clock)

        service.claim(request(playerId, definition)).join() shouldBe WeeklyKitClaimResult.ProviderRejected
        repository.released shouldBe listOf(repository.reservation.claimId)
        repository.confirmed shouldBe emptyList()
    }

    "successful delivery confirms the weekly claim" {
        val repository = FakeWeeklyKitRepository()
        val service = WeeklyKitService(repository, { delivered, name ->
            delivered.kitId shouldBe "arcranks_weekly_citizen"
            name shouldBe "Alexey23"
            CompletableFuture.completedFuture(true)
        }, clock)

        service.claim(request(playerId, definition)).join() shouldBe WeeklyKitClaimResult.Claimed
        repository.confirmed shouldBe listOf(repository.reservation.claimId)
        repository.released shouldBe emptyList()
    }

    "ambiguous confirmation failure stays locked instead of duplicating rewards" {
        val repository = FakeWeeklyKitRepository().apply { failConfirm = true }
        val service = WeeklyKitService(repository, { _, _ -> CompletableFuture.completedFuture(true) }, clock)

        service.claim(request(playerId, definition)).join() shouldBe WeeklyKitClaimResult.DeliveryPending
        repository.released shouldBe emptyList()
    }

    "ambiguous provider exception stays locked instead of releasing" {
        val repository = FakeWeeklyKitRepository()
        val service = WeeklyKitService(repository, { _, _ ->
            CompletableFuture.failedFuture(IllegalStateException("command outcome unknown"))
        }, clock)

        service.claim(request(playerId, definition)).join() shouldBe WeeklyKitClaimResult.DeliveryPending
        repository.released shouldBe emptyList()
    }

    "an administrator resets only a confirmed claim for the current Moscow week" {
        val repository = FakeWeeklyKitRepository().apply {
            nextAdminReset = WeeklyKitAdminResetStorageResult.RESET
        }
        val service = WeeklyKitService(repository, { _, _ -> CompletableFuture.completedFuture(true) }, clock)

        service.adminReset(playerId, "00000000-0000-0000-0000-000000000042").join() shouldBe
            WeeklyKitAdminResetResult.Reset
        repository.adminResetCalls shouldBe listOf(
            WeeklyKitAdminResetCall(
                playerId,
                WeeklyKitCycle.at(clock.instant()),
                "00000000-0000-0000-0000-000000000042",
            ),
        )
    }

    "an administrator cannot reset an in-flight delivery" {
        val repository = FakeWeeklyKitRepository().apply {
            nextAdminReset = WeeklyKitAdminResetStorageResult.DELIVERY_PENDING
        }
        val service = WeeklyKitService(repository, { _, _ -> CompletableFuture.completedFuture(true) }, clock)

        service.adminReset(playerId, "CONSOLE").join() shouldBe WeeklyKitAdminResetResult.DeliveryPending
    }
})

private fun request(
    playerId: UUID,
    definition: WeeklyKitDefinition,
    freeSlots: Int = 9,
) = WeeklyKitClaimRequest(playerId, "Alexey23", definition, "classic_survival", freeSlots)

private class FakeWeeklyKitRepository : WeeklyKitRepository {
    val reservation = WeeklyKitReservation(
        UUID.fromString("00000000-0000-0000-0000-000000000099"),
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        WeeklyKitCycle.at(Instant.parse("2026-08-30T18:00:00Z")),
        RankId("citizen"),
        "arcranks_weekly_citizen",
        "classic_survival",
    )
    var nextBegin: WeeklyKitBeginResult = WeeklyKitBeginResult.Ready(reservation)
    var beginCalls = 0
    var failConfirm = false
    val confirmed = mutableListOf<UUID>()
    val released = mutableListOf<UUID>()
    var nextAdminReset: WeeklyKitAdminResetStorageResult = WeeklyKitAdminResetStorageResult.NOT_CLAIMED
    val adminResetCalls = mutableListOf<WeeklyKitAdminResetCall>()

    override fun state(playerId: UUID, cycle: WeeklyKitCycle) =
        CompletableFuture.completedFuture(WeeklyKitClaimState.AVAILABLE)

    override fun begin(
        playerId: UUID,
        cycle: WeeklyKitCycle,
        rankId: RankId,
        kitId: String,
        serverId: String,
    ): CompletableFuture<WeeklyKitBeginResult> {
        beginCalls++
        return CompletableFuture.completedFuture(nextBegin)
    }

    override fun confirm(reservation: WeeklyKitReservation): CompletableFuture<Boolean> {
        confirmed += reservation.claimId
        return if (failConfirm) CompletableFuture.failedFuture(IllegalStateException("mysql unavailable"))
        else CompletableFuture.completedFuture(true)
    }

    override fun release(reservation: WeeklyKitReservation): CompletableFuture<Boolean> {
        released += reservation.claimId
        return CompletableFuture.completedFuture(true)
    }

    override fun adminReset(playerId: UUID, cycle: WeeklyKitCycle, actor: String): CompletableFuture<WeeklyKitAdminResetStorageResult> {
        adminResetCalls += WeeklyKitAdminResetCall(playerId, cycle, actor)
        return CompletableFuture.completedFuture(nextAdminReset)
    }
}

private data class WeeklyKitAdminResetCall(val playerId: UUID, val cycle: WeeklyKitCycle, val actor: String)
