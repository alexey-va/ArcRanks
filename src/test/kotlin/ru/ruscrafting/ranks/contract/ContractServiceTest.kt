package ru.ruscrafting.ranks.contract

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.arc.config.Config
import ru.ruscrafting.ranks.analytics.ProductDimension
import ru.ruscrafting.ranks.analytics.ProductEvent
import ru.ruscrafting.ranks.analytics.ProductMetricKey
import ru.ruscrafting.ranks.analytics.ProductTelemetry
import ru.ruscrafting.ranks.analytics.TelemetryBatch
import ru.ruscrafting.ranks.domain.PathAvailability
import ru.ruscrafting.ranks.domain.ProgressSnapshot
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.perk.PerkCatalogLoader
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ContractServiceTest : StringSpec({
    val contractCatalog = ContractCatalogLoader(Config(Files.createTempDirectory("arcranks-service-contract"), "contracts.yml")).load()
    val perkCatalog = PerkCatalogLoader(Config(Files.createTempDirectory("arcranks-service-perk"), "perks.yml")).load()
    val generator = ContractOfferGenerator(contractCatalog, perkCatalog)
    val clock = Clock.fixed(Instant.parse("2026-08-29T18:42:00Z"), ZoneOffset.UTC)
    val player = UUID.fromString("00000000-0000-0000-0000-000000000001")

    "board exposes three deterministic offers and rejects an unknown identity" {
        val repository = FakeContractRepository()
        val service = ContractService(repository, generator, clock)

        val board = service.board(player, serviceContext()).join()
        board.offers.size shouldBe 3
        board.rerollAvailable shouldBe true
        service.accept(player, ContractId("aaaaaaaaaaaaaaaaaaaaaaaa"), serviceContext()).join()
            .shouldBeInstanceOf<ContractAcceptResult.OfferUnavailable>()
    }

    "accept, not-ready claim, completed claim and reroll stay typed" {
        val repository = FakeContractRepository()
        var flushes = 0
        val service = ContractService(repository, generator, clock) {
            flushes++
            CompletableFuture.completedFuture(Unit)
        }
        val offer = service.board(player, serviceContext()).join().offers.first()
        val active = ActiveContract(
            offer.id, offer.cycle, offer.generation, offer.path, 10, offer.targetDelta, offer.rewardDelta, 10,
        )
        repository.acceptResult = ContractAcceptStorageResult.Accepted(active)
        service.accept(player, offer.id, serviceContext()).join().shouldBeInstanceOf<ContractAcceptResult.Accepted>()

        repository.claimResult = ContractClaimStorageResult.NotReady(active.copy(currentValue = 11))
        service.claim(player).join().shouldBeInstanceOf<ContractClaimResult.NotReady>()
        repository.claimResult = ContractClaimStorageResult.Claimed(active.copy(currentValue = 10 + offer.targetDelta))
        service.claim(player).join().shouldBeInstanceOf<ContractClaimResult.Claimed>()
        flushes shouldBe 2

        repository.rerollResult = ContractRerollStorageResult.Rerolled(1)
        service.reroll(player, serviceContext()).join().shouldBeInstanceOf<ContractRerollResult.Rerolled>()
    }

    "expired weekly state and rejected claim are recorded" {
        val repository = FakeContractRepository()
        repository.stored = repository.stored.copy(expiredOnLoad = true)
        val batches = mutableListOf<TelemetryBatch>()
        val telemetry = ProductTelemetry("survival", clock, 16, 16) { batch ->
            batches += batch
            CompletableFuture.completedFuture(Unit)
        }
        val service = ContractService(repository, generator, clock, telemetry)

        service.board(player, serviceContext()).join()
        service.claim(player).join().shouldBeInstanceOf<ContractClaimResult.NoActive>()
        telemetry.flush().join()

        batches.single().counters[ProductMetricKey(ProductEvent.CONTRACT_EXPIRED, ProductDimension.NONE)] shouldBe 1
        batches.single().counters[
            ProductMetricKey(ProductEvent.CONTRACT_REJECTED, ProductDimension("claim:no_active")),
        ] shouldBe 1
    }

    "accept validation does not count hidden offer impressions" {
        val repository = FakeContractRepository()
        val batches = mutableListOf<TelemetryBatch>()
        val telemetry = ProductTelemetry("survival", clock, 16, 16) { batch ->
            batches += batch
            CompletableFuture.completedFuture(Unit)
        }
        val service = ContractService(repository, generator, clock, telemetry)
        val offer = service.board(player, serviceContext()).join().offers.first()
        repository.acceptResult = ContractAcceptStorageResult.Accepted(
            ActiveContract(
                offer.id, offer.cycle, offer.generation, offer.path, 0,
                offer.targetDelta, offer.rewardDelta, 0,
            ),
        )

        service.accept(player, offer.id, serviceContext()).join()
        telemetry.flush().join()

        batches.single().counters
            .filterKeys { it.event == ProductEvent.CONTRACT_OFFERED }
            .values.sum() shouldBe 3
    }
})

private fun serviceContext() = ContractPlayerContext(
    rankOrder = 1,
    selectedFocus = SpecializationPath.FARMING,
    nearestIncompletePath = SpecializationPath.INDUSTRY,
    progress = ProgressSnapshot.EMPTY,
    availability = PathAvailability.allAvailable(),
    activePerks = emptySet(),
)

private class FakeContractRepository : ContractRepository {
    private val cycle = ContractCycle.at(Instant.parse("2026-08-29T18:42:00Z"))
    var stored = ContractStoredBoard(cycle, 0, 0, 0, null)
    var acceptResult: ContractAcceptStorageResult = ContractAcceptStorageResult.CycleComplete
    var claimResult: ContractClaimStorageResult = ContractClaimStorageResult.NoActive
    var rerollResult: ContractRerollStorageResult = ContractRerollStorageResult.Rerolled(1)

    override fun state(playerId: UUID, cycle: ContractCycle) = CompletableFuture.completedFuture(stored)
    override fun accept(playerId: UUID, offer: ContractOffer) = CompletableFuture.completedFuture(acceptResult)
    override fun claim(playerId: UUID, cycle: ContractCycle) = CompletableFuture.completedFuture(claimResult)
    override fun reroll(playerId: UUID, cycle: ContractCycle) = CompletableFuture.completedFuture(rerollResult)
}
