package ru.ruscrafting.ranks.perk

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import ru.ruscrafting.ranks.domain.MasteryLevel
import ru.ruscrafting.ranks.domain.SpecializationPath
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture

class PerkSelectionServiceTest : StringSpec({
    val catalog = PerkCatalogLoader(Config(Files.createTempDirectory("arcranks-perk-service"), "perks.yml")).load()
    val playerId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    "locked perk is rejected without repository mutation" {
        val repository = MemoryPerkRepository()
        val service = PerkSelectionService(catalog, repository)

        service.select(playerId, PerkId("farming_momentum"), mastery(MasteryLevel.NONE)).join()
            .shouldBeInstanceOf<PerkSelectionResult.Locked>()
        repository.selection.active shouldBe emptyList()
        repository.equipCalls shouldBe 0
    }

    "two slots fill deterministically and a third selection preserves them" {
        val repository = MemoryPerkRepository()
        val service = PerkSelectionService(catalog, repository)
        val unlocked = mastery(MasteryLevel.III)

        service.select(playerId, PerkId("farming_momentum"), unlocked).join()
            .shouldBeInstanceOf<PerkSelectionResult.Selected>()
        service.select(playerId, PerkId("industry_momentum"), unlocked).join()
            .shouldBeInstanceOf<PerkSelectionResult.Selected>()
        service.select(playerId, PerkId("building_momentum"), unlocked).join()
            .shouldBeInstanceOf<PerkSelectionResult.Full>()

        repository.selection.active shouldBe listOf(PerkId("farming_momentum"), PerkId("industry_momentum"))
        service.select(playerId, PerkId("farming_momentum"), unlocked).join()
            .shouldBeInstanceOf<PerkSelectionResult.AlreadySelected>()
    }

    "removing one perk preserves the other slot" {
        val repository = MemoryPerkRepository(
            PerkSelection(listOf(PerkId("farming_momentum"), PerkId("industry_momentum"))),
        )
        val service = PerkSelectionService(catalog, repository)

        val result = service.remove(playerId, PerkId("farming_momentum")).join()
            .shouldBeInstanceOf<PerkSelectionResult.Removed>()

        result.selection.active shouldBe listOf(PerkId("industry_momentum"))
    }

    "loading removes perk ids that no longer exist in the catalog" {
        val repository = MemoryPerkRepository(
            PerkSelection(listOf(PerkId("removed_from_config"), PerkId("farming_momentum"))),
        )
        val service = PerkSelectionService(catalog, repository)

        service.load(playerId).join().active shouldBe listOf(PerkId("farming_momentum"))
        repository.selection.active shouldBe listOf(PerkId("farming_momentum"))
    }
})

private class MemoryPerkRepository(initial: PerkSelection = PerkSelection.EMPTY) : PerkSelectionRepository {
    var selection = initial
    var equipCalls = 0

    override fun load(playerId: UUID) = CompletableFuture.completedFuture(selection)

    override fun equip(playerId: UUID, perkId: PerkId): CompletableFuture<PerkEquipResult> {
        equipCalls++
        val existing = selection.active.indexOf(perkId)
        if (existing >= 0) return CompletableFuture.completedFuture(PerkEquipResult.AlreadySelected(selection))
        if (selection.active.size >= PerkSelection.MAX_SLOTS) {
            return CompletableFuture.completedFuture(PerkEquipResult.Full(selection))
        }
        selection = PerkSelection(selection.active + perkId)
        return CompletableFuture.completedFuture(PerkEquipResult.Selected(selection.active.size, selection))
    }

    override fun remove(playerId: UUID, perkId: PerkId): CompletableFuture<PerkRemoveResult> {
        val removed = perkId in selection.active
        selection = PerkSelection(selection.active - perkId)
        return CompletableFuture.completedFuture(PerkRemoveResult(selection, removed))
    }
}

private fun mastery(level: MasteryLevel): Map<SpecializationPath, MasteryLevel> =
    SpecializationPath.entries.associateWith { level }
