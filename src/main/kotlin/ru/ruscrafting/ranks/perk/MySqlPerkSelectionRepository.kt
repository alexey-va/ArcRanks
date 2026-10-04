package ru.ruscrafting.ranks.perk

import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlRuntime
import ru.ruscrafting.ranks.storage.RankMigrations
import ru.ruscrafting.ranks.storage.retryingTransaction
import java.sql.Connection
import java.util.EnumMap
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MySqlPerkSelectionRepository(private val runtime: SqlRuntime) : PerkSelectionRepository {
    fun initialize(): CompletableFuture<Unit> = runtime.executor
        .submit { MySqlMigrator(runtime.dataSource, MIGRATION_NAMESPACE).migrate(RankMigrations.ALL) }
        .thenApply { Unit }

    override fun load(playerId: UUID): CompletableFuture<PerkSelection> = runtime.executor.read { connection ->
        load(connection, playerId)
    }

    override fun loadPresets(playerId: UUID): CompletableFuture<Map<PerkPreset, PerkSelection>> =
        runtime.executor.read { connection -> loadPresets(connection, playerId) }

    override fun equip(playerId: UUID, perkId: PerkId): CompletableFuture<PerkEquipResult> =
        runtime.executor.retryingTransaction { connection ->
            lockOwner(connection, playerId)
            val slots = loadSlots(connection, playerId)
            if (perkId in slots.values) return@retryingTransaction PerkEquipResult.AlreadySelected(selection(slots))
            val slot = (1..PerkSelection.MAX_SLOTS).firstOrNull { it !in slots }
                ?: return@retryingTransaction PerkEquipResult.Full(selection(slots))
            connection.prepareStatement(
                "INSERT INTO `arc_ranks_perk_selection` (`player_uuid`, `slot`, `perk_id`) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, playerId.toString())
                statement.setInt(2, slot)
                statement.setString(3, perkId.value)
                statement.executeUpdate()
            }
            slots[slot] = perkId
            PerkEquipResult.Selected(slot, selection(slots))
        }

    override fun assign(playerId: UUID, slot: Int, perkId: PerkId): CompletableFuture<PerkAssignResult> {
        require(slot in 1..PerkSelection.MAX_SLOTS) { "Perk slot must be between one and two" }
        return runtime.executor.retryingTransaction { connection ->
            lockOwner(connection, playerId)
            val slots = loadSlots(connection, playerId)
            val existingSlot = slots.entries.firstOrNull { it.value == perkId }?.key
            if (existingSlot != null) {
                return@retryingTransaction PerkAssignResult.AlreadySelected(existingSlot, selection(slots))
            }
            connection.prepareStatement(
                """
                INSERT INTO `arc_ranks_perk_selection` (`player_uuid`, `slot`, `perk_id`)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE `perk_id` = VALUES(`perk_id`)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, playerId.toString())
                statement.setInt(2, slot)
                statement.setString(3, perkId.value)
                statement.executeUpdate()
            }
            slots[slot] = perkId
            PerkAssignResult.Assigned(slot, selection(slots))
        }
    }

    override fun remove(playerId: UUID, perkId: PerkId): CompletableFuture<PerkRemoveResult> =
        runtime.executor.retryingTransaction { connection ->
            lockOwner(connection, playerId)
            val removed = connection.prepareStatement(
                "DELETE FROM `arc_ranks_perk_selection` WHERE `player_uuid` = ? AND `perk_id` = ?",
            ).use { statement ->
                statement.setString(1, playerId.toString())
                statement.setString(2, perkId.value)
                statement.executeUpdate() > 0
            }
            PerkRemoveResult(load(connection, playerId), removed)
        }

    override fun savePreset(playerId: UUID, preset: PerkPreset): CompletableFuture<PerkSelection> =
        runtime.executor.retryingTransaction { connection ->
            lockOwner(connection, playerId)
            val current = loadSlots(connection, playerId)
            replacePreset(connection, playerId, preset, current)
            selection(current)
        }

    override fun applyPreset(
        playerId: UUID,
        preset: PerkPreset,
        allowedPerkIds: Set<PerkId>,
    ): CompletableFuture<PerkPresetApplyResult> = runtime.executor.retryingTransaction { connection ->
        lockOwner(connection, playerId)
        val targetSlots = loadPresetSlots(connection, playerId, preset)
        if (targetSlots.isEmpty()) return@retryingTransaction PerkPresetApplyResult.Empty
        val unavailable = targetSlots.values.filterNot(allowedPerkIds::contains).toSet()
        if (unavailable.isNotEmpty()) {
            return@retryingTransaction PerkPresetApplyResult.Unavailable(unavailable)
        }
        replaceSelection(connection, playerId, targetSlots)
        PerkPresetApplyResult.Applied(selection(targetSlots))
    }

    private fun lockOwner(connection: Connection, playerId: UUID) {
        connection.prepareStatement(
            "INSERT IGNORE INTO `arc_ranks_perk_owner` (`player_uuid`) VALUES (?)",
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "SELECT `player_uuid` FROM `arc_ranks_perk_owner` WHERE `player_uuid` = ? FOR UPDATE",
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.executeQuery().use { result -> check(result.next()) { "Could not lock perk owner" } }
        }
    }

    private fun load(connection: Connection, playerId: UUID): PerkSelection =
        selection(loadSlots(connection, playerId))

    private fun loadPresets(connection: Connection, playerId: UUID): Map<PerkPreset, PerkSelection> {
        val slotsByPreset = EnumMap<PerkPreset, MutableMap<Int, PerkId>>(PerkPreset::class.java)
        PerkPreset.entries.forEach { slotsByPreset[it] = sortedMapOf() }
        connection.prepareStatement(
            "SELECT `preset_id`, `slot`, `perk_id` FROM `arc_ranks_perk_preset` WHERE `player_uuid` = ? ORDER BY `preset_id`, `slot`",
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.executeQuery().use { result ->
                while (result.next()) {
                    val preset = PerkPreset.entries.firstOrNull { it.storageId == result.getString("preset_id") }
                        ?: continue
                    slotsByPreset.getValue(preset)[result.getInt("slot")] = PerkId(result.getString("perk_id"))
                }
            }
        }
        return PerkPreset.entries.associateWith { preset -> selection(slotsByPreset.getValue(preset)) }
    }

    private fun loadPresetSlots(connection: Connection, playerId: UUID, preset: PerkPreset): MutableMap<Int, PerkId> {
        val slots = sortedMapOf<Int, PerkId>()
        connection.prepareStatement(
            "SELECT `slot`, `perk_id` FROM `arc_ranks_perk_preset` WHERE `player_uuid` = ? AND `preset_id` = ? ORDER BY `slot`",
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.setString(2, preset.storageId)
            statement.executeQuery().use { result ->
                while (result.next()) slots[result.getInt("slot")] = PerkId(result.getString("perk_id"))
            }
        }
        return slots
    }

    private fun replacePreset(
        connection: Connection,
        playerId: UUID,
        preset: PerkPreset,
        slots: Map<Int, PerkId>,
    ) {
        connection.prepareStatement(
            "DELETE FROM `arc_ranks_perk_preset` WHERE `player_uuid` = ? AND `preset_id` = ?",
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.setString(2, preset.storageId)
            statement.executeUpdate()
        }
        slots.forEach { (slot, perkId) ->
            connection.prepareStatement(
                "INSERT INTO `arc_ranks_perk_preset` (`player_uuid`, `preset_id`, `slot`, `perk_id`) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, playerId.toString())
                statement.setString(2, preset.storageId)
                statement.setInt(3, slot)
                statement.setString(4, perkId.value)
                statement.executeUpdate()
            }
        }
    }

    private fun replaceSelection(connection: Connection, playerId: UUID, slots: Map<Int, PerkId>) {
        connection.prepareStatement("DELETE FROM `arc_ranks_perk_selection` WHERE `player_uuid` = ?").use { statement ->
            statement.setString(1, playerId.toString())
            statement.executeUpdate()
        }
        slots.forEach { (slot, perkId) ->
            connection.prepareStatement(
                "INSERT INTO `arc_ranks_perk_selection` (`player_uuid`, `slot`, `perk_id`) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, playerId.toString())
                statement.setInt(2, slot)
                statement.setString(3, perkId.value)
                statement.executeUpdate()
            }
        }
    }

    private fun loadSlots(connection: Connection, playerId: UUID): MutableMap<Int, PerkId> {
        val slots = sortedMapOf<Int, PerkId>()
        connection.prepareStatement(
            "SELECT `slot`, `perk_id` FROM `arc_ranks_perk_selection` WHERE `player_uuid` = ? ORDER BY `slot`",
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.executeQuery().use { result ->
                while (result.next()) slots[result.getInt("slot")] = PerkId(result.getString("perk_id"))
            }
        }
        return slots
    }

    private fun selection(slots: Map<Int, PerkId>): PerkSelection = PerkSelection(slots.toSortedMap())

    private companion object {
        const val MIGRATION_NAMESPACE = "arc_ranks"
    }
}
