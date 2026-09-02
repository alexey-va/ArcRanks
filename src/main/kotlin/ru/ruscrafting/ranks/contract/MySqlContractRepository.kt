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
                     `baseline`, `target_delta`, `reward_delta`, `money_reward`, `token_reward`,
                     `token_currency`, `item_preset`, `item_amount`, `state`, `reward_delivery_state`, `accepted_at`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 'PENDING', CURRENT_TIMESTAMP(3))
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
                statement.setLong(10, offer.bonusReward.money)
                statement.setLong(11, offer.bonusReward.tokens)
                statement.setString(12, offer.bonusReward.tokenCurrency)
                statement.setString(13, offer.bonusReward.itemPreset)
                statement.setInt(14, offer.bonusReward.itemAmount)
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
                    bonusReward = offer.bonusReward,
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

    override fun adminComplete(
        playerId: UUID,
        cycle: ContractCycle,
        actor: String,
    ): CompletableFuture<ContractAdminCompleteStorageResult> =
        runtime.executor.retryingTransaction { connection ->
            lockCycle(connection, playerId, cycle)
            val active = loadActive(connection, playerId, cycle, lock = true)
                ?: return@retryingTransaction ContractAdminCompleteStorageResult.NoActive
            if (active.completed) {
                return@retryingTransaction ContractAdminCompleteStorageResult.AlreadyReady(active)
            }
            connection.prepareStatement(
                """
                UPDATE `arc_ranks_contract`
                SET `admin_completed_at` = CURRENT_TIMESTAMP(3), `admin_completed_by` = ?
                WHERE `contract_id` = ? AND `state` = 'ACTIVE' AND `admin_completed_at` IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, actor)
                statement.setString(2, active.id.value)
                check(statement.executeUpdate() == 1) { "Active contract changed during admin completion" }
            }
            ContractAdminCompleteStorageResult.Completed(active.copy(adminCompleted = true))
        }

    override fun pendingRewards(playerId: UUID): CompletableFuture<List<ActiveContract>> =
        runtime.executor.read { connection ->
            connection.prepareStatement(
                """
                SELECT `contract_id`, `cycle_start`, `generation`, `path`, `metric`, `baseline`,
                       `target_delta`, `reward_delta`, `money_reward`, `token_reward`, `token_currency`,
                       `item_preset`, `item_amount`, `admin_completed_at`
                FROM `arc_ranks_contract`
                WHERE `player_uuid` = ? AND `state` = 'CLAIMED' AND `reward_delivery_state` = 'PENDING'
                ORDER BY `claimed_at`, `generation`
                LIMIT 3
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, playerId.toString())
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            val baseline = result.getLong("baseline")
                            val target = result.getLong("target_delta")
                            val completedValue = if (Long.MAX_VALUE - baseline < target) Long.MAX_VALUE else baseline + target
                            add(readContract(connection, playerId, result, currentValue = completedValue))
                        }
                    }
                }
            }
        }

    override fun markRewardGranted(playerId: UUID, contractId: ContractId): CompletableFuture<Boolean> =
        updateRewardState(playerId, contractId, "GRANTED", null)

    override fun markRewardRecovery(
        playerId: UUID,
        contractId: ContractId,
        failureCode: String,
    ): CompletableFuture<Boolean> {
        require(failureCode.matches(Regex("[a-z0-9_]{1,64}"))) { "Unsafe contract reward failure code" }
        return updateRewardState(playerId, contractId, "RECOVERY", failureCode)
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
            SELECT `contract_id`, `cycle_start`, `generation`, `path`, `metric`, `baseline`, `target_delta`,
                   `reward_delta`, `money_reward`, `token_reward`, `token_currency`, `item_preset`, `item_amount`,
                   `admin_completed_at`
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
                readContract(connection, playerId, result, cycle)
            }
        }
    }

    private fun readContract(
        connection: Connection,
        playerId: UUID,
        result: java.sql.ResultSet,
        knownCycle: ContractCycle? = null,
        currentValue: Long? = null,
    ): ActiveContract {
        val metric = ProgressMetric.valueOf(result.getString("metric"))
        return ActiveContract(
            ContractId(result.getString("contract_id")),
            knownCycle ?: ContractCycle(result.getDate("cycle_start").toLocalDate()),
            result.getInt("generation"),
            SpecializationPath.valueOf(result.getString("path")),
            result.getLong("baseline"),
            result.getLong("target_delta"),
            result.getLong("reward_delta"),
            currentValue ?: progressValue(connection, playerId, metric),
            result.getTimestamp("admin_completed_at") != null,
            ContractBonusReward(
                result.getLong("money_reward"),
                result.getLong("token_reward"),
                result.getString("token_currency"),
                result.getString("item_preset"),
                result.getInt("item_amount"),
            ),
        )
    }

    private fun updateRewardState(
        playerId: UUID,
        contractId: ContractId,
        state: String,
        failureCode: String?,
    ): CompletableFuture<Boolean> = runtime.executor.write { connection ->
        connection.prepareStatement(
            """
            UPDATE `arc_ranks_contract`
            SET `reward_delivery_state` = ?,
                `reward_delivered_at` = CASE WHEN ? = 'GRANTED' THEN CURRENT_TIMESTAMP(3) ELSE NULL END,
                `reward_failure_code` = ?
            WHERE `contract_id` = ? AND `player_uuid` = ? AND `state` = 'CLAIMED'
              AND `reward_delivery_state` = 'PENDING'
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, state)
            statement.setString(2, state)
            statement.setString(3, failureCode)
            statement.setString(4, contractId.value)
            statement.setString(5, playerId.toString())
            statement.executeUpdate() == 1
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
