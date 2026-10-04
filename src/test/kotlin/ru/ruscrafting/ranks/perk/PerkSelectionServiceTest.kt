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

    "assigning a perk to a chosen slot replaces only that slot atomically" {
        val repository = MemoryPerkRepository(
            PerkSelection(mapOf(1 to PerkId("farming_momentum"), 2 to PerkId("industry_momentum"))),
        )
        val service = PerkSelectionService(catalog, repository)

        val result = service.selectIntoSlot(
            playerId,
            slot = 1,
            perkId = PerkId("building_momentum"),
            mastery = mastery(MasteryLevel.III),
        ).join().shouldBeInstanceOf<PerkSelectionResult.Selected>()

        result.slot shouldBe 1
        result.selection.slots shouldBe mapOf(
            1 to PerkId("building_momentum"),
            2 to PerkId("industry_momentum"),
        )
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

    "load captures one catalog revision before asynchronous storage completes" {
        val selected = PerkSelection(listOf(PerkId("farming_momentum")))
        val pendingLoad = CompletableFuture<PerkSelection>()
        val repository = MemoryPerkRepository(selected, pendingLoad)
        val reloadedCatalog = PerkCatalog(
            catalog.perks.map { perk ->
                if (perk.id == PerkId("farming_momentum")) {
                    perk.copy(id = PerkId("farming_reloaded"))
                } else {
                    perk
                }
            },
        )
        var currentCatalog = catalog
        var catalogReads = 0
        val service = PerkSelectionService(
            catalogProvider = {
                catalogReads++
                currentCatalog
            },
            repository = repository,
        )

        val load = service.load(playerId)
        currentCatalog = reloadedCatalog
        pendingLoad.complete(selected)

        load.join() shouldBe selected
        repository.selection shouldBe selected
        catalogReads shouldBe 1
    }

    "saving a preset preserves its slot map and an empty save clears it" {
        val current = PerkSelection(mapOf(2 to PerkId("industry_momentum")))
        val repository = MemoryPerkRepository(current)
        val service = PerkSelectionService(catalog, repository)

        service.savePreset(playerId, PerkPreset.MINE).join() shouldBe current
        service.loadPresets(playerId).join().getValue(PerkPreset.MINE) shouldBe current

        repository.selection = PerkSelection.EMPTY
        service.savePreset(playerId, PerkPreset.MINE).join() shouldBe PerkSelection.EMPTY
        service.loadPresets(playerId).join().getValue(PerkPreset.MINE) shouldBe PerkSelection.EMPTY
    }

    "applying an unlocked preset replaces both slots and notifies the gameplay cache" {
        val original = PerkSelection(mapOf(
            1 to PerkId("building_momentum"),
            2 to PerkId("industry_momentum"),
        ))
        val saved = PerkSelection(mapOf(2 to PerkId("farming_momentum")))
        val repository = MemoryPerkRepository(original).apply { presets[PerkPreset.TRAIL] = saved }
        val changes = mutableListOf<PerkSelection>()
        val service = PerkSelectionService(catalog, repository, onChanged = { _, selection -> changes += selection })

        val result = service.applyPreset(playerId, PerkPreset.TRAIL, mastery(MasteryLevel.III)).join()

        result shouldBe PerkPresetApplyResult.Applied(saved)
        repository.selection shouldBe saved
        changes shouldBe listOf(saved)
    }

    "empty and partly locked or unknown presets leave the active pair untouched" {
        val original = PerkSelection(mapOf(2 to PerkId("industry_momentum")))
        val repository = MemoryPerkRepository(original).apply {
            presets[PerkPreset.MINE] = PerkSelection.EMPTY
            presets[PerkPreset.BUILD] = PerkSelection(mapOf(
                1 to PerkId("farming_momentum"),
                2 to PerkId("removed_from_catalog"),
            ))
        }
        val changes = mutableListOf<PerkSelection>()
        val service = PerkSelectionService(catalog, repository, onChanged = { _, selection -> changes += selection })

        service.applyPreset(playerId, PerkPreset.MINE, mastery(MasteryLevel.III)).join() shouldBe
            PerkPresetApplyResult.Empty
        val rejected = service.applyPreset(playerId, PerkPreset.BUILD, mastery(MasteryLevel.NONE)).join()
            .shouldBeInstanceOf<PerkPresetApplyResult.Unavailable>()

        rejected.perkIds shouldBe setOf(PerkId("farming_momentum"), PerkId("removed_from_catalog"))
        repository.selection shouldBe original
        changes shouldBe emptyList()
    }
})

private class MemoryPerkRepository(
    initial: PerkSelection = PerkSelection.EMPTY,
    private val pendingLoad: CompletableFuture<PerkSelection>? = null,
) : PerkSelectionRepository {
    var selection = initial
    var equipCalls = 0
    val presets = PerkPreset.entries.associateWith { PerkSelection.EMPTY }.toMutableMap()

    override fun load(playerId: UUID) = pendingLoad ?: CompletableFuture.completedFuture(selection)

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
        selection = PerkSelection(selection.slots.filterValues { it != perkId })
        return CompletableFuture.completedFuture(PerkRemoveResult(selection, removed))
    }

    override fun assign(playerId: UUID, slot: Int, perkId: PerkId): CompletableFuture<PerkAssignResult> {
        val existingSlot = selection.slots.entries.firstOrNull { it.value == perkId }?.key
        if (existingSlot != null) {
            return CompletableFuture.completedFuture(PerkAssignResult.AlreadySelected(existingSlot, selection))
        }
        selection = PerkSelection(selection.slots + (slot to perkId))
        return CompletableFuture.completedFuture(PerkAssignResult.Assigned(slot, selection))
    }

    override fun loadPresets(playerId: UUID) = CompletableFuture.completedFuture(presets.toMap())

    override fun savePreset(playerId: UUID, preset: PerkPreset): CompletableFuture<PerkSelection> =
        CompletableFuture.completedFuture(selection.also { presets[preset] = it })

    override fun applyPreset(
        playerId: UUID,
        preset: PerkPreset,
        allowedPerkIds: Set<PerkId>,
    ): CompletableFuture<PerkPresetApplyResult> {
        val saved = presets[preset] ?: PerkSelection.EMPTY
        val result = when {
            saved.slots.isEmpty() -> PerkPresetApplyResult.Empty
            saved.active.any { it !in allowedPerkIds } ->
                PerkPresetApplyResult.Unavailable(saved.active.filterNot(allowedPerkIds::contains).toSet())
            else -> PerkPresetApplyResult.Applied(saved.also { selection = it })
        }
        return CompletableFuture.completedFuture(result)
    }
}

private fun mastery(level: MasteryLevel): Map<SpecializationPath, MasteryLevel> =
    SpecializationPath.entries.associateWith { level }
