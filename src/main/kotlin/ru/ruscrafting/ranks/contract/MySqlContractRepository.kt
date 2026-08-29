package ru.ruscrafting.ranks.contract

import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlRuntime
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.storage.RankMigrations
import ru.ruscrafting.ranks.storage.retryingTransaction
import java.sql.Connection
import java.sql.Date
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MySqlContractRepository(private val runtime: SqlRuntime) : ContractRepository {
    fun initialize(): CompletableFuture<Unit> = runtime.executor
        .submit { MySqlMigrator(runtime.dataSource, MIGRATION_NAMESPACE).migrate(RankMigrations.ALL) }
        .thenApply { Unit }

    override fun state(playerId: UUID, cycle: ContractCycle): CompletableFuture<ContractStoredBoard> =
        runtime.executor.retryingTransaction { connection ->
            val owner = lockCycle(connection, playerId, cycle)
            ContractStoredBoard(
                cycle,
                owner.generation,
                owner.rerollNonce,
                claimedCount(connection, playerId, cycle),
                loadActive(connection, playerId, cycle, lock = false),
                owner.expiredActive,
            )
        }

    override fun accept(playerId: UUID, offer: ContractOffer): CompletableFuture<ContractAcceptStorageResult> =
        runtime.executor.retryingTransaction { connection ->
            val owner = lockCycle(connection, playerId, offer.cycle)
            loadActive(connection, playerId, offer.cycle, lock = true)?.let {
                return@retryingTransaction ContractAcceptStorageResult.AlreadyActive(it)
            }
            if (owner.generation >= ContractOffer.MAX_CONTRACTS_PER_CYCLE) {
                return@retryingTransaction ContractAcceptStorageResult.CycleComplete
            }
            if (owner.generation != offer.generation || owner.rerollNonce != offer.rerollNonce) {
                return@retryingTransaction ContractAcceptStorageResult.OfferStale
            }
            val baseline = progressValue(connection, playerId, offer.path.metric)
            connection.prepareStatement(
                """
                INSERT INTO `arc_ranks_contract`
                    (`contract_id`, `player_uuid`, `cycle_start`, `generation`, `path`, `metric`,
                     `baseline`, `target_delta`, `reward_delta`, `state`, `accepted_at`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP(3))
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, offer.id.value)
                statement.setString(2, playerId.toString())
                statement.setDate(3, Date.valueOf(offer.cycle.start))
                statement.setInt(4, offer.generation)
                statement.setString(5, offer.path.name)
                statement.setString(6, offer.path.metric.name)
                statement.setLong(7, baseline)
                statement.setLong(8, offer.targetDelta)
                statement.setLong(9, offer.rewardDelta)
                statement.executeUpdate()
            }
            ContractAcceptStorageResult.Accepted(
                ActiveContract(
                    offer.id,
                    offer.cycle,
                    offer.generation,
                    offer.path,
                    baseline,
                    offer.targetDelta,
                    offer.rewardDelta,
                    baseline,
                ),
            )
        }

    override fun claim(playerId: UUID, cycle: ContractCycle): CompletableFuture<ContractClaimStorageResult> =
        runtime.executor.retryingTransaction { connection ->
            lockCycle(connection, playerId, cycle)
            val active = loadActive(connection, playerId, cycle, lock = true)
            if (active == null) {
                val claimed = loadLatestClaimed(connection, playerId, cycle)
                return@retryingTransaction claimed?.let(ContractClaimStorageResult::AlreadyClaimed)
                    ?: ContractClaimStorageResult.NoActive
            }
            if (!active.completed) return@retryingTransaction ContractClaimStorageResult.NotReady(active)

            val rewardInserted = connection.prepareStatement(
                """
                INSERT IGNORE INTO `arc_ranks_progress_events`
                    (`source`, `event_id`, `player_uuid`, `metric`, `delta`)
                VALUES ('arcranks_contract', ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, active.id.value)
                statement.setString(2, playerId.toString())
                statement.setString(3, active.path.metric.name)
                statement.setLong(4, active.rewardDelta)
                statement.executeUpdate() == 1
            }
            if (rewardInserted) addProgress(connection, playerId, active.path.metric, active.rewardDelta)
            connection.prepareStatement(
                """
                UPDATE `arc_ranks_contract`
                SET `state` = 'CLAIMED', `claimed_at` = CURRENT_TIMESTAMP(3)
                WHERE `contract_id` = ? AND `state` = 'ACTIVE'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, active.id.value)
                check(statement.executeUpdate() == 1) { "Active contract changed during locked claim" }
            }
            connection.prepareStatement(
                """
                UPDATE `arc_ranks_contract_cycle`
                SET `generation` = LEAST(?, `generation` + 1), `reroll_nonce` = 0
                WHERE `player_uuid` = ? AND `cycle_start` = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, ContractOffer.MAX_CONTRACTS_PER_CYCLE)
                statement.setString(2, playerId.toString())
                statement.setDate(3, Date.valueOf(cycle.start))
                check(statement.executeUpdate() == 1) { "Contract cycle disappeared during claim" }
            }
            ContractClaimStorageResult.Claimed(active)
        }

    override fun reroll(playerId: UUID, cycle: ContractCycle): CompletableFuture<ContractRerollStorageResult> =
        runtime.executor.retryingTransaction { connection ->
            val owner = lockCycle(connection, playerId, cycle)
            loadActive(connection, playerId, cycle, lock = true)?.let {
                return@retryingTransaction ContractRerollStorageResult.Active(it)
            }
            if (owner.generation >= ContractOffer.MAX_CONTRACTS_PER_CYCLE) {
                return@retryingTransaction ContractRerollStorageResult.CycleComplete
            }
            if (owner.rerollNonce > 0) return@retryingTransaction ContractRerollStorageResult.AlreadyUsed
            connection.prepareStatement(
                "UPDATE `arc_ranks_contract_cycle` SET `reroll_nonce` = 1 WHERE `player_uuid` = ? AND `reroll_nonce` = 0",
            ).use { statement ->
                statement.setString(1, playerId.toString())
                if (statement.executeUpdate() != 1) return@retryingTransaction ContractRerollStorageResult.AlreadyUsed
            }
            ContractRerollStorageResult.Rerolled(1)
        }

    private fun lockCycle(connection: Connection, playerId: UUID, cycle: ContractCycle): CycleOwner {
        connection.prepareStatement(
            """
            INSERT IGNORE INTO `arc_ranks_contract_cycle`
                (`player_uuid`, `cycle_start`, `generation`, `reroll_nonce`)
            VALUES (?, ?, 0, 0)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.setDate(2, Date.valueOf(cycle.start))
            statement.executeUpdate()
        }
        val stored = connection.prepareStatement(
            """
            SELECT `cycle_start`, `generation`, `reroll_nonce`
            FROM `arc_ranks_contract_cycle`
            WHERE `player_uuid` = ? FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.executeQuery().use { result ->
                check(result.next()) { "Could not lock contract cycle" }
                CycleOwner(result.getDate("cycle_start").toLocalDate(), result.getInt("generation"), result.getInt("reroll_nonce"))
            }
        }
        if (stored.cycleStart == cycle.start) return stored

        val expiredActive = connection.prepareStatement(
            """
            UPDATE `arc_ranks_contract`
            SET `state` = 'EXPIRED', `expired_at` = CURRENT_TIMESTAMP(3)
            WHERE `player_uuid` = ? AND `state` = 'ACTIVE'
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.executeUpdate() > 0
        }
        connection.prepareStatement(
            """
            UPDATE `arc_ranks_contract_cycle`
            SET `cycle_start` = ?, `generation` = 0, `reroll_nonce` = 0
            WHERE `player_uuid` = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setDate(1, Date.valueOf(cycle.start))
            statement.setString(2, playerId.toString())
            check(statement.executeUpdate() == 1) { "Could not advance contract cycle" }
        }
        return CycleOwner(cycle.start, 0, 0, expiredActive)
    }

    private fun loadActive(
        connection: Connection,
        playerId: UUID,
        cycle: ContractCycle,
        lock: Boolean,
    ): ActiveContract? = loadContract(connection, playerId, cycle, "ACTIVE", lock)

    private fun loadLatestClaimed(connection: Connection, playerId: UUID, cycle: ContractCycle): ActiveContract? =
        loadContract(connection, playerId, cycle, "CLAIMED", lock = false)

    private fun loadContract(
        connection: Connection,
        playerId: UUID,
        cycle: ContractCycle,
        state: String,
        lock: Boolean,
    ): ActiveContract? {
        val suffix = if (lock) "FOR UPDATE" else ""
        return connection.prepareStatement(
            """
            SELECT `contract_id`, `generation`, `path`, `metric`, `baseline`, `target_delta`, `reward_delta`
            FROM `arc_ranks_contract`
            WHERE `player_uuid` = ? AND `cycle_start` = ? AND `state` = ?
            ORDER BY `generation` DESC
            LIMIT 1
            $suffix
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.setDate(2, Date.valueOf(cycle.start))
            statement.setString(3, state)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                val metric = ProgressMetric.valueOf(result.getString("metric"))
                ActiveContract(
                    ContractId(result.getString("contract_id")),
                    cycle,
                    result.getInt("generation"),
                    SpecializationPath.valueOf(result.getString("path")),
                    result.getLong("baseline"),
                    result.getLong("target_delta"),
                    result.getLong("reward_delta"),
                    progressValue(connection, playerId, metric),
                )
            }
        }
    }

    private fun claimedCount(connection: Connection, playerId: UUID, cycle: ContractCycle): Int =
        connection.prepareStatement(
            "SELECT COUNT(*) AS `total` FROM `arc_ranks_contract` WHERE `player_uuid` = ? AND `cycle_start` = ? AND `state` = 'CLAIMED'",
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.setDate(2, Date.valueOf(cycle.start))
            statement.executeQuery().use { result -> result.next(); result.getInt("total") }
        }

    private fun progressValue(connection: Connection, playerId: UUID, metric: ProgressMetric): Long =
        connection.prepareStatement(
            "SELECT `value` FROM `arc_ranks_progress` WHERE `player_uuid` = ? AND `metric` = ?",
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.setString(2, metric.name)
            statement.executeQuery().use { result -> if (result.next()) result.getLong("value").coerceAtLeast(0) else 0 }
        }

    private fun addProgress(connection: Connection, playerId: UUID, metric: ProgressMetric, delta: Long) {
        connection.prepareStatement(
            """
            INSERT INTO `arc_ranks_progress` (`player_uuid`, `metric`, `value`)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE `value` = LEAST(9223372036854775807, `value` + VALUES(`value`))
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.setString(2, metric.name)
            statement.setLong(3, delta)
            statement.executeUpdate()
        }
    }

    private data class CycleOwner(
        val cycleStart: java.time.LocalDate,
        val generation: Int,
        val rerollNonce: Int,
        val expiredActive: Boolean = false,
    )

    private companion object {
        const val MIGRATION_NAMESPACE = "arc_ranks"
    }
}
