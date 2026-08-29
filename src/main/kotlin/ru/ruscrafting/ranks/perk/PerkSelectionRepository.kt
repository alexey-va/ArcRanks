package ru.ruscrafting.ranks.perk

import java.util.UUID
import java.util.concurrent.CompletableFuture

data class PerkSelection(val active: List<PerkId>) {
    init {
        require(active.size <= MAX_SLOTS) { "A player may equip at most two perks" }
        require(active.distinct().size == active.size) { "Equipped perks must be unique" }
    }

    companion object {
        const val MAX_SLOTS = 2
        val EMPTY = PerkSelection(emptyList())
    }
}

sealed interface PerkEquipResult {
    val selection: PerkSelection

    data class Selected(val slot: Int, override val selection: PerkSelection) : PerkEquipResult
    data class AlreadySelected(override val selection: PerkSelection) : PerkEquipResult
    data class Full(override val selection: PerkSelection) : PerkEquipResult
}

data class PerkRemoveResult(
    val selection: PerkSelection,
    val removed: Boolean,
)

interface PerkSelectionRepository {
    fun load(playerId: UUID): CompletableFuture<PerkSelection>
    fun equip(playerId: UUID, perkId: PerkId): CompletableFuture<PerkEquipResult>
    fun remove(playerId: UUID, perkId: PerkId): CompletableFuture<PerkRemoveResult>
}
