package ru.ruscrafting.ranks.perk

import java.util.UUID
import java.util.concurrent.CompletableFuture

data class PerkSelection(val slots: Map<Int, PerkId>) {
    constructor(active: List<PerkId>) : this(active.mapIndexed { index, perkId -> index + 1 to perkId }.toMap())

    val active: List<PerkId> = slots.toSortedMap().values.toList()

    init {
        require(slots.keys.all { it in 1..MAX_SLOTS }) { "Perk slot must be between one and two" }
        require(active.size <= MAX_SLOTS) { "A player may equip at most two perks" }
        require(active.distinct().size == active.size) { "Equipped perks must be unique" }
    }

    companion object {
        const val MAX_SLOTS = 2
        val EMPTY = PerkSelection(emptyMap())
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

sealed interface PerkAssignResult {
    val selection: PerkSelection

    data class Assigned(val slot: Int, override val selection: PerkSelection) : PerkAssignResult
    data class AlreadySelected(val slot: Int, override val selection: PerkSelection) : PerkAssignResult
}

interface PerkSelectionRepository {
    fun load(playerId: UUID): CompletableFuture<PerkSelection>
    fun equip(playerId: UUID, perkId: PerkId): CompletableFuture<PerkEquipResult>
    fun assign(playerId: UUID, slot: Int, perkId: PerkId): CompletableFuture<PerkAssignResult>
    fun remove(playerId: UUID, perkId: PerkId): CompletableFuture<PerkRemoveResult>
}
