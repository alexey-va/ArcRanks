package ru.ruscrafting.ranks.kit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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

    "retired claims never reserve confirm release or inspect a historical grant" {
        val repository = mockk<WeeklyKitRepository>()
        val service = WeeklyKitService(repository, clock)

        for (freeSlots in listOf(0, 9, 36)) {
            val request = WeeklyKitClaimRequest(playerId, "Alexey23", definition, "classic_survival", freeSlots)
            service.claim(request).join() shouldBe WeeklyKitClaimResult.Retired
        }

        verify { repository wasNot Called }
    }

    "retired administrative resets cannot delete claimed or uncertain grant records" {
        val repository = mockk<WeeklyKitRepository>()
        val service = WeeklyKitService(repository, clock)

        service.adminReset(playerId, "CONSOLE").join() shouldBe WeeklyKitAdminResetResult.Retired
        service.adminReset(playerId, UUID.randomUUID().toString()).join() shouldBe WeeklyKitAdminResetResult.Retired

        verify { repository wasNot Called }
    }

    "available claimed and uncertain historical states remain readable without writes" {
        val repository = mockk<WeeklyKitRepository>()
        val cycle = WeeklyKitCycle.at(clock.instant())
        val service = WeeklyKitService(repository, clock)

        for (state in WeeklyKitClaimState.entries) {
            every { repository.state(playerId, cycle) } returns CompletableFuture.completedFuture(state)
            service.state(playerId).join() shouldBe state
        }

        verify(exactly = 3) { repository.state(playerId, cycle) }
        verify(exactly = 0) {
            repository.begin(any(), any(), any(), any(), any())
            repository.confirm(any())
            repository.release(any())
            repository.adminReset(any(), any(), any())
        }
    }
})
