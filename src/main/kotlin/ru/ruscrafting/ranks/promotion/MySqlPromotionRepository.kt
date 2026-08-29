package ru.ruscrafting.ranks.promotion

import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlRuntime
import ru.ruscrafting.ranks.domain.RankId
import ru.ruscrafting.ranks.storage.RankMigrations
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MySqlPromotionRepository(private val runtime: SqlRuntime) : PromotionRepository {
    fun initialize(): CompletableFuture<Unit> = runtime.executor
        .submit { MySqlMigrator(runtime.dataSource, MIGRATION_NAMESPACE).migrate(RankMigrations.ALL) }
        .thenApply { Unit }

    override fun active(playerId: UUID): CompletableFuture<PromotionSaga?> = runtime.executor.read { connection ->
        connection.prepareStatement(
            """
            SELECT `generation`, `from_rank`, `target_rank`, `state`
            FROM `arc_ranks_promotion_state`
            WHERE `player_uuid` = ? AND `state` <> ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.setString(2, PromotionState.COMPLETED.name)
            statement.executeQuery().use { result -> if (result.next()) result.saga(playerId) else null }
        }
    }

    override fun prepare(
        playerId: UUID,
        from: RankId,
        target: RankId,
    ): CompletableFuture<PromotionPrepareResult> = runtime.executor.transaction { connection ->
        val initial = PromotionSaga(
            playerId = playerId,
            generation = 1L,
            from = from,
            target = target,
            state = PromotionState.PREPARED,
        )
        val inserted = insertIfAbsent(connection, initial)
        val existing = checkNotNull(lock(connection, playerId)) { "Promotion row disappeared after insert-or-lock" }
        if (inserted) {
            appendHistory(connection, existing)
            return@transaction PromotionPrepareResult.Ready(existing, resumed = false)
        }
        if (existing.state != PromotionState.COMPLETED) {
            if (existing.from == from && existing.target == target) {
                return@transaction PromotionPrepareResult.Ready(existing, resumed = true)
            }
            return@transaction PromotionPrepareResult.Conflict(existing)
        }
        val saga = PromotionSaga(
            playerId = playerId,
            generation = existing.generation + 1L,
            from = from,
            target = target,
            state = PromotionState.PREPARED,
        )
        connection.prepareStatement(
            """
            UPDATE `arc_ranks_promotion_state`
            SET `generation` = ?, `from_rank` = ?, `target_rank` = ?, `state` = ?
            WHERE `player_uuid` = ? AND `generation` = ? AND `state` = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, saga.generation)
            statement.setString(2, from.value)
            statement.setString(3, target.value)
            statement.setString(4, saga.state.name)
            statement.setString(5, playerId.toString())
            statement.setLong(6, existing.generation)
            statement.setString(7, PromotionState.COMPLETED.name)
            check(statement.executeUpdate() == 1) { "Completed promotion generation changed while locked" }
        }
        appendHistory(connection, saga)
        PromotionPrepareResult.Ready(saga, resumed = false)
    }

    override fun transition(
        saga: PromotionSaga,
        expected: PromotionState,
        target: PromotionState,
    ): CompletableFuture<Boolean> {
        require(legalTransition(expected, target)) { "Illegal promotion transition $expected -> $target" }
        return runtime.executor.transaction { connection ->
            val updated = connection.prepareStatement(
                """
                UPDATE `arc_ranks_promotion_state`
                SET `state` = ?
                WHERE `player_uuid` = ? AND `generation` = ? AND `state` = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, target.name)
                statement.setString(2, saga.playerId.toString())
                statement.setLong(3, saga.generation)
                statement.setString(4, expected.name)
                statement.executeUpdate() == 1
            }
            if (updated) appendHistory(connection, saga.copy(state = target))
            updated
        }
    }

    private fun lock(connection: Connection, playerId: UUID): PromotionSaga? =
        connection.prepareStatement(
            """
            SELECT `generation`, `from_rank`, `target_rank`, `state`
            FROM `arc_ranks_promotion_state`
            WHERE `player_uuid` = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.executeQuery().use { result -> if (result.next()) result.saga(playerId) else null }
        }

    private fun insertIfAbsent(connection: Connection, saga: PromotionSaga): Boolean =
        connection.prepareStatement(
            """
            INSERT IGNORE INTO `arc_ranks_promotion_state`
                (`player_uuid`, `generation`, `from_rank`, `target_rank`, `state`)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, saga.playerId.toString())
            statement.setLong(2, saga.generation)
            statement.setString(3, saga.from.value)
            statement.setString(4, saga.target.value)
            statement.setString(5, saga.state.name)
            statement.executeUpdate() == 1
        }

    private fun appendHistory(connection: Connection, saga: PromotionSaga) {
        connection.prepareStatement(
            """
            INSERT INTO `arc_ranks_promotion_history`
                (`player_uuid`, `generation`, `from_rank`, `target_rank`, `state`)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, saga.playerId.toString())
            statement.setLong(2, saga.generation)
            statement.setString(3, saga.from.value)
            statement.setString(4, saga.target.value)
            statement.setString(5, saga.state.name)
            statement.executeUpdate()
        }
    }

    private fun ResultSet.saga(playerId: UUID): PromotionSaga = PromotionSaga(
        playerId = playerId,
        generation = getLong("generation"),
        from = RankId(getString("from_rank")),
        target = RankId(getString("target_rank")),
        state = PromotionState.valueOf(getString("state")),
    )

    private fun legalTransition(from: PromotionState, to: PromotionState): Boolean = when (from) {
        PromotionState.PREPARED -> to == PromotionState.APPLIED || to == PromotionState.RETRYABLE || to == PromotionState.COMPLETED
        PromotionState.APPLIED -> to == PromotionState.COMPLETED || to == PromotionState.RETRYABLE
        PromotionState.RETRYABLE -> to == PromotionState.APPLIED || to == PromotionState.COMPLETED
        PromotionState.COMPLETED -> false
    }

    private companion object {
        const val MIGRATION_NAMESPACE = "arc_ranks"
    }
}
