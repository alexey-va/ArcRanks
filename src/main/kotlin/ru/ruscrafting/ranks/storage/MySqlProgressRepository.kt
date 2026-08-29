package ru.ruscrafting.ranks.storage

import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlRuntime
import ru.ruscrafting.ranks.domain.PlayerProgressProfile
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.domain.ProgressSnapshot
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.progress.ProgressMutation
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MySqlProgressRepository(private val runtime: SqlRuntime) : ProgressRepository {
    override fun initialize(): CompletableFuture<Unit> = runtime.executor
        .submit { MySqlMigrator(runtime.dataSource, MIGRATION_NAMESPACE).migrate(RankMigrations.ALL) }
        .thenApply { Unit }

    override fun load(playerId: UUID): CompletableFuture<PlayerProgressProfile> = runtime.executor.read { connection ->
        val values = mutableMapOf<ProgressMetric, Long>()
        connection.prepareStatement(
            "SELECT `metric`, `value` FROM `arc_ranks_progress` WHERE `player_uuid` = ?",
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.executeQuery().use { result ->
                while (result.next()) {
                    val metric = runCatching { ProgressMetric.valueOf(result.getString("metric")) }.getOrNull() ?: continue
                    values[metric] = result.getLong("value").coerceAtLeast(0)
                }
            }
        }
        val focus = connection.prepareStatement(
            "SELECT `selected_focus` FROM `arc_ranks_profile` WHERE `player_uuid` = ?",
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.executeQuery().use { result ->
                if (!result.next()) SpecializationPath.FARMING
                else runCatching { SpecializationPath.valueOf(result.getString("selected_focus")) }
                    .getOrDefault(SpecializationPath.FARMING)
            }
        }
        PlayerProgressProfile(ProgressSnapshot(values), focus)
    }

    override fun applyMutations(
        playerId: UUID,
        mutations: List<ProgressMutation>,
    ): CompletableFuture<Unit> {
        require(mutations.isNotEmpty()) { "Progress mutation batch must not be empty" }
        require(mutations.map(ProgressMutation::metric).distinct().size == mutations.size) {
            "Progress mutation batch must contain each metric once"
        }
        return runtime.executor.transaction { connection ->
            mutations.forEach { applyMutation(connection, playerId, it) }
        }
    }

    override fun selectFocus(playerId: UUID, path: SpecializationPath): CompletableFuture<Unit> =
        runtime.executor.write { connection ->
            connection.prepareStatement(
                """
                INSERT INTO `arc_ranks_profile` (`player_uuid`, `selected_focus`)
                VALUES (?, ?)
                ON DUPLICATE KEY UPDATE `selected_focus` = VALUES(`selected_focus`)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, playerId.toString())
                statement.setString(2, path.name)
                statement.executeUpdate()
            }
            Unit
        }

    override fun recordExternalEvent(event: ExternalProgressEvent): CompletableFuture<ExternalProgressResult> =
        runtime.executor.transaction { connection ->
            val inserted = connection.prepareStatement(
                """
                INSERT IGNORE INTO `arc_ranks_progress_events`
                    (`source`, `event_id`, `player_uuid`, `metric`, `delta`)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, event.source)
                statement.setString(2, event.eventId)
                statement.setString(3, event.playerId.toString())
                statement.setString(4, event.metric.name)
                statement.setLong(5, event.delta)
                statement.executeUpdate() == 1
            }
            if (!inserted) ExternalProgressResult.DUPLICATE
            else {
                applyMutation(connection, event.playerId, ProgressMutation.Add(event.metric, event.delta))
                ExternalProgressResult.APPLIED
            }
        }

    private fun applyMutation(connection: Connection, playerId: UUID, mutation: ProgressMutation) {
        val update = when (mutation) {
            is ProgressMutation.Add -> "LEAST(9223372036854775807, `value` + VALUES(`value`))"
            is ProgressMutation.Maximum -> "GREATEST(`value`, VALUES(`value`))"
        }
        val amount = when (mutation) {
            is ProgressMutation.Add -> mutation.delta
            is ProgressMutation.Maximum -> mutation.value
        }
        connection.prepareStatement(
            """
            INSERT INTO `arc_ranks_progress` (`player_uuid`, `metric`, `value`)
            VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE `value` = $update
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.setString(2, mutation.metric.name)
            statement.setLong(3, amount)
            statement.executeUpdate()
        }
    }

    private companion object {
        const val MIGRATION_NAMESPACE = "arc_ranks"
    }
}
