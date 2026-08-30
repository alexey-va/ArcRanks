package ru.ruscrafting.ranks.perk

import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlRuntime
import ru.ruscrafting.ranks.storage.RankMigrations
import ru.ruscrafting.ranks.storage.retryingTransaction
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MySqlPerkSelectionRepository(private val runtime: SqlRuntime) : PerkSelectionRepository {
    fun initialize(): CompletableFuture<Unit> = runtime.executor
        .submit { MySqlMigrator(runtime.dataSource, MIGRATION_NAMESPACE).migrate(RankMigrations.ALL) }
        .thenApply { Unit }

    override fun load(playerId: UUID): CompletableFuture<PerkSelection> = runtime.executor.read { connection ->
        load(connection, playerId)
    }

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
