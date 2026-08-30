package ru.ruscrafting.ranks.kit

import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlRuntime
import ru.ruscrafting.ranks.domain.RankId
import ru.ruscrafting.ranks.storage.RankMigrations
import ru.ruscrafting.ranks.storage.retryingTransaction
import java.sql.Date
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MySqlWeeklyKitRepository(private val runtime: SqlRuntime) : WeeklyKitRepository {
    fun initialize(): CompletableFuture<Unit> = runtime.executor
        .submit { MySqlMigrator(runtime.dataSource, "arc_ranks").migrate(RankMigrations.ALL) }
        .thenApply { Unit }

    override fun state(playerId: UUID, cycle: WeeklyKitCycle): CompletableFuture<WeeklyKitClaimState> =
        runtime.executor.read { connection ->
            connection.prepareStatement(
                "SELECT `state` FROM `arc_ranks_weekly_kit_claim` WHERE `player_uuid` = ? AND `cycle_start` = ?",
            ).use { statement ->
                statement.setString(1, playerId.toString())
                statement.setDate(2, Date.valueOf(cycle.start))
                statement.executeQuery().use { result ->
                    if (!result.next()) WeeklyKitClaimState.AVAILABLE
                    else WeeklyKitClaimState.valueOf(result.getString("state"))
                }
            }
        }

    override fun begin(
        playerId: UUID,
        cycle: WeeklyKitCycle,
        rankId: RankId,
        kitId: String,
        serverId: String,
    ): CompletableFuture<WeeklyKitBeginResult> = runtime.executor.retryingTransaction { connection ->
        val reservation = WeeklyKitReservation(UUID.randomUUID(), playerId, cycle, rankId, kitId, serverId)
        val inserted = connection.prepareStatement(
            """
            INSERT IGNORE INTO `arc_ranks_weekly_kit_claim`
                (`player_uuid`, `cycle_start`, `claim_id`, `rank_id`, `kit_id`, `server_id`, `state`)
            VALUES (?, ?, ?, ?, ?, ?, 'DELIVERING')
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.setDate(2, Date.valueOf(cycle.start))
            statement.setString(3, reservation.claimId.toString())
            statement.setString(4, rankId.value)
            statement.setString(5, kitId)
            statement.setString(6, serverId)
            statement.executeUpdate() == 1
        }
        if (inserted) return@retryingTransaction WeeklyKitBeginResult.Ready(reservation)

        val state = connection.prepareStatement(
            "SELECT `state` FROM `arc_ranks_weekly_kit_claim` WHERE `player_uuid` = ? AND `cycle_start` = ? FOR UPDATE",
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.setDate(2, Date.valueOf(cycle.start))
            statement.executeQuery().use { result ->
                check(result.next()) { "Weekly claim disappeared after duplicate insert" }
                result.getString("state")
            }
        }
        if (state == "CLAIMED") WeeklyKitBeginResult.AlreadyClaimed else WeeklyKitBeginResult.DeliveryPending
    }

    override fun confirm(reservation: WeeklyKitReservation): CompletableFuture<Boolean> = runtime.executor.write { connection ->
        connection.prepareStatement(
            """
            UPDATE `arc_ranks_weekly_kit_claim`
            SET `state` = 'CLAIMED', `claimed_at` = CURRENT_TIMESTAMP(3)
            WHERE `player_uuid` = ? AND `cycle_start` = ? AND `claim_id` = ? AND `state` = 'DELIVERING'
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, reservation.playerId.toString())
            statement.setDate(2, Date.valueOf(reservation.cycle.start))
            statement.setString(3, reservation.claimId.toString())
            statement.executeUpdate() == 1
        }
    }

    override fun release(reservation: WeeklyKitReservation): CompletableFuture<Boolean> = runtime.executor.write { connection ->
        connection.prepareStatement(
            """
            DELETE FROM `arc_ranks_weekly_kit_claim`
            WHERE `player_uuid` = ? AND `cycle_start` = ? AND `claim_id` = ? AND `state` = 'DELIVERING'
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, reservation.playerId.toString())
            statement.setDate(2, Date.valueOf(reservation.cycle.start))
            statement.setString(3, reservation.claimId.toString())
            statement.executeUpdate() == 1
        }
    }
}
