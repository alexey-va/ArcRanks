package ru.ruscrafting.ranks.perk

import ru.ruscrafting.ranks.analytics.PlayerSignal
import ru.ruscrafting.ranks.analytics.ProductDimension
import ru.ruscrafting.ranks.analytics.ProductEvent
import ru.ruscrafting.ranks.analytics.ProductTelemetry
import ru.ruscrafting.ranks.domain.MasteryLevel
import ru.ruscrafting.ranks.domain.SpecializationPath
import java.util.UUID
import java.util.concurrent.CompletableFuture

sealed interface PerkSelectionResult {
    data class Selected(val perk: PerkDefinition, val slot: Int, val selection: PerkSelection) : PerkSelectionResult
    data class AlreadySelected(val perk: PerkDefinition, val selection: PerkSelection) : PerkSelectionResult
    data class Removed(val perk: PerkDefinition, val selection: PerkSelection) : PerkSelectionResult
    data class NotSelected(val perk: PerkDefinition, val selection: PerkSelection) : PerkSelectionResult
    data class Locked(val perk: PerkDefinition, val currentMastery: MasteryLevel) : PerkSelectionResult
    data class Full(val perk: PerkDefinition, val selection: PerkSelection) : PerkSelectionResult
}

class PerkSelectionService(
    private val catalog: PerkCatalog,
    private val repository: PerkSelectionRepository,
    private val telemetry: ProductTelemetry? = null,
    private val onChanged: (UUID, PerkSelection) -> Unit = { _, _ -> },
) {
    fun load(playerId: UUID): CompletableFuture<PerkSelection> = repository.load(playerId).thenCompose { selection ->
        val stale = selection.active.filterNot(catalog::contains)
        stale.fold(CompletableFuture.completedFuture(selection)) { current, perkId ->
            current.thenCompose { repository.remove(playerId, perkId).thenApply(PerkRemoveResult::selection) }
        }.thenApply { cleaned ->
            if (stale.isNotEmpty()) onChanged(playerId, cleaned)
            cleaned
        }
    }

    fun select(
        playerId: UUID,
        perkId: PerkId,
        mastery: Map<SpecializationPath, MasteryLevel>,
    ): CompletableFuture<PerkSelectionResult> {
        val perk = catalog.require(perkId)
        val current = mastery[perk.path] ?: MasteryLevel.NONE
        if (current.ordinal < perk.requiredMastery.ordinal) {
            telemetry?.record(ProductEvent.PERK_REJECTED, ProductDimension("locked:${perk.id.value}"))
            return CompletableFuture.completedFuture(PerkSelectionResult.Locked(perk, current))
        }
        return repository.equip(playerId, perkId).thenApply { result ->
            when (result) {
                is PerkEquipResult.Selected -> {
                    onChanged(playerId, result.selection)
                    telemetry?.record(ProductEvent.PERK_SELECTED, ProductDimension("perk:${perk.id.value}"))
                    telemetry?.recordPlayer(playerId, PlayerSignal.PERK_SELECTED)
                    PerkSelectionResult.Selected(perk, result.slot, result.selection)
                }
                is PerkEquipResult.AlreadySelected -> PerkSelectionResult.AlreadySelected(perk, result.selection)
                is PerkEquipResult.Full -> {
                    telemetry?.record(ProductEvent.PERK_REJECTED, ProductDimension("full:${perk.path.name.lowercase()}"))
                    PerkSelectionResult.Full(perk, result.selection)
                }
            }
        }
    }

    fun remove(playerId: UUID, perkId: PerkId): CompletableFuture<PerkSelectionResult> {
        val perk = catalog.require(perkId)
        return repository.remove(playerId, perkId).thenApply { result ->
            if (result.removed) {
                onChanged(playerId, result.selection)
                telemetry?.record(ProductEvent.PERK_REMOVED, ProductDimension("perk:${perk.id.value}"))
                PerkSelectionResult.Removed(perk, result.selection)
            } else {
                PerkSelectionResult.NotSelected(perk, result.selection)
            }
        }
    }
}
