package ru.ruscrafting.ranks.analytics

import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlRuntime
import ru.ruscrafting.ranks.storage.RankMigrations
import java.sql.Connection
import java.sql.Date
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture

class MySqlAnalyticsRepository(private val runtime: SqlRuntime) : AnalyticsRepository {
    fun initialize(): CompletableFuture<Unit> = runtime.executor
        .submit { MySqlMigrator(runtime.dataSource, MIGRATION_NAMESPACE).migrate(RankMigrations.ALL) }
        .thenApply { Unit }

    override fun write(batch: TelemetryBatch): CompletableFuture<Unit> = runtime.executor.transaction { connection ->
        if (!claimBatch(connection, batch)) return@transaction Unit
        writeCounters(connection, batch)
        writePlayers(connection, batch)
    }

    override fun summary(days: Int, now: Instant): CompletableFuture<AnalyticsRawSummary> {
        require(days > 0) { "Analytics window must be positive" }
        return runtime.executor.read { connection ->
            val sinceDay = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(days.toLong() - 1)
            val playerCounts = readPlayerCounts(connection, sinceDay)
            val (eventTotals, recommendationTotals) = readEventTotals(
                connection,
                now.minus(Duration.ofDays(days.toLong())),
            )
            AnalyticsRawSummary(
                uniqueSeenPlayers = playerCounts.seen,
                passportPlayers = playerCounts.passport,
                contractAcceptedPlayers = playerCounts.contractAccepted,
                contractCompletedPlayers = playerCounts.contractCompleted,
                perkSelectedPlayers = playerCounts.perkSelected,
                eventTotals = eventTotals,
                recommendationTotals = recommendationTotals,
            )
        }
    }

    private fun claimBatch(connection: Connection, batch: TelemetryBatch): Boolean =
        connection.prepareStatement(
            """
            INSERT IGNORE INTO `arc_ranks_analytics_batch`
                (`batch_id`, `server_id`, `bucket_start`)
            VALUES (?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, batch.batchId.toString())
            statement.setString(2, batch.serverId)
            statement.setTimestamp(3, Timestamp.from(batch.bucketStart))
            statement.executeUpdate() == 1
        }

    private fun writeCounters(connection: Connection, batch: TelemetryBatch) {
        if (batch.counters.isEmpty()) return
        connection.prepareStatement(
            """
            INSERT INTO `arc_ranks_product_metric_hour`
                (`bucket_start`, `server_id`, `event`, `dimension`, `value`)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                `value` = LEAST(9223372036854775807, `value` + VALUES(`value`))
            """.trimIndent(),
        ).use { statement ->
            batch.counters.forEach { (key, value) ->
                statement.setTimestamp(1, Timestamp.from(batch.bucketStart))
                statement.setString(2, batch.serverId)
                statement.setString(3, key.event.name)
                statement.setString(4, key.dimension.value)
                statement.setLong(5, value)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun writePlayers(connection: Connection, batch: TelemetryBatch) {
        if (batch.players.isEmpty()) return
        connection.prepareStatement(
            """
            INSERT INTO `arc_ranks_product_player_day`
                (`day`, `player_uuid`, `latest_rank`, `seen`, `passport_opened`,
                 `contract_accepted`, `contract_completed`, `perk_selected`)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                `latest_rank` = COALESCE(VALUES(`latest_rank`), `latest_rank`),
                `seen` = GREATEST(`seen`, VALUES(`seen`)),
                `passport_opened` = GREATEST(`passport_opened`, VALUES(`passport_opened`)),
                `contract_accepted` = GREATEST(`contract_accepted`, VALUES(`contract_accepted`)),
                `contract_completed` = GREATEST(`contract_completed`, VALUES(`contract_completed`)),
                `perk_selected` = GREATEST(`perk_selected`, VALUES(`perk_selected`))
            """.trimIndent(),
        ).use { statement ->
            batch.players.forEach { player ->
                statement.setDate(1, Date.valueOf(player.day))
                statement.setString(2, player.playerId.toString())
                statement.setString(3, player.rankId?.value)
                statement.setInt(4, player.signals.flag(PlayerSignal.SEEN))
                statement.setInt(5, player.signals.flag(PlayerSignal.PASSPORT_OPENED))
                statement.setInt(6, player.signals.flag(PlayerSignal.CONTRACT_ACCEPTED))
                statement.setInt(7, player.signals.flag(PlayerSignal.CONTRACT_COMPLETED))
                statement.setInt(8, player.signals.flag(PlayerSignal.PERK_SELECTED))
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun readPlayerCounts(connection: Connection, sinceDay: LocalDate): PlayerCounts =
        connection.prepareStatement(
            """
            SELECT
                COUNT(DISTINCT CASE WHEN `seen` = 1 THEN `player_uuid` END) AS `seen_players`,
                COUNT(DISTINCT CASE WHEN `passport_opened` = 1 THEN `player_uuid` END) AS `passport_players`,
                COUNT(DISTINCT CASE WHEN `contract_accepted` = 1 THEN `player_uuid` END) AS `accepted_players`,
                COUNT(DISTINCT CASE WHEN `contract_completed` = 1 THEN `player_uuid` END) AS `completed_players`,
                COUNT(DISTINCT CASE WHEN `perk_selected` = 1 THEN `player_uuid` END) AS `perk_players`
            FROM `arc_ranks_product_player_day`
            WHERE `day` >= ?
            """.trimIndent(),
        ).use { statement ->
            statement.setDate(1, Date.valueOf(sinceDay))
            statement.executeQuery().use { result ->
                result.next()
                PlayerCounts(
                    seen = result.getLong("seen_players"),
                    passport = result.getLong("passport_players"),
                    contractAccepted = result.getLong("accepted_players"),
                    contractCompleted = result.getLong("completed_players"),
                    perkSelected = result.getLong("perk_players"),
                )
            }
        }

    private fun readEventTotals(
        connection: Connection,
        since: Instant,
    ): Pair<Map<ProductEvent, Long>, Map<String, Long>> {
        val events = mutableMapOf<ProductEvent, Long>()
        val recommendations = mutableMapOf<String, Long>()
        connection.prepareStatement(
            """
            SELECT `event`, `dimension`, SUM(`value`) AS `total`
            FROM `arc_ranks_product_metric_hour`
            WHERE `bucket_start` >= ?
            GROUP BY `event`, `dimension`
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(since))
            statement.executeQuery().use { result ->
                while (result.next()) {
                    val event = runCatching { ProductEvent.valueOf(result.getString("event")) }.getOrNull() ?: continue
                    val total = result.getLong("total").coerceAtLeast(0)
                    events[event] = saturatedAdd(events[event] ?: 0, total)
                    if (event == ProductEvent.RECOMMENDATION_SHOWN) {
                        val dimension = result.getString("dimension")
                        recommendations[dimension] = saturatedAdd(recommendations[dimension] ?: 0, total)
                    }
                }
            }
        }
        return events to recommendations
    }

    private fun Set<PlayerSignal>.flag(signal: PlayerSignal): Int = if (signal in this) 1 else 0

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private data class PlayerCounts(
        val seen: Long,
        val passport: Long,
        val contractAccepted: Long,
        val contractCompleted: Long,
        val perkSelected: Long,
    )

    private companion object {
        const val MIGRATION_NAMESPACE = "arc_ranks"
    }
}
