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

    override fun adminReset(
        playerId: UUID,
        cycle: WeeklyKitCycle,
        actor: String,
    ): CompletableFuture<WeeklyKitAdminResetStorageResult> = runtime.executor.retryingTransaction { connection ->
        require(actor.matches(Regex("[A-Za-z0-9_.:-]{1,64}"))) { "Unsafe weekly kit admin actor" }
        val claim = connection.prepareStatement(
            """
            SELECT `claim_id`, `rank_id`, `kit_id`, `server_id`, `state`
            FROM `arc_ranks_weekly_kit_claim`
            WHERE `player_uuid` = ? AND `cycle_start` = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.setDate(2, Date.valueOf(cycle.start))
            statement.executeQuery().use { result ->
                if (!result.next()) null else WeeklyKitAdminResetClaim(
                    UUID.fromString(result.getString("claim_id")),
                    result.getString("rank_id"),
                    result.getString("kit_id"),
                    result.getString("server_id"),
                    WeeklyKitClaimState.valueOf(result.getString("state")),
                )
            }
        } ?: return@retryingTransaction WeeklyKitAdminResetStorageResult.NOT_CLAIMED
        if (claim.state == WeeklyKitClaimState.DELIVERING) {
            return@retryingTransaction WeeklyKitAdminResetStorageResult.DELIVERY_PENDING
        }

        connection.prepareStatement(
            """
            INSERT INTO `arc_ranks_weekly_kit_admin_reset`
                (`reset_id`, `player_uuid`, `cycle_start`, `claim_id`, `rank_id`, `kit_id`, `server_id`, `admin_actor`)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, UUID.randomUUID().toString())
            statement.setString(2, playerId.toString())
            statement.setDate(3, Date.valueOf(cycle.start))
            statement.setString(4, claim.claimId.toString())
            statement.setString(5, claim.rankId)
            statement.setString(6, claim.kitId)
            statement.setString(7, claim.serverId)
            statement.setString(8, actor)
            check(statement.executeUpdate() == 1) { "Weekly kit admin reset audit was not written" }
        }
        connection.prepareStatement(
            """
            DELETE FROM `arc_ranks_weekly_kit_claim`
            WHERE `player_uuid` = ? AND `cycle_start` = ? AND `claim_id` = ? AND `state` = 'CLAIMED'
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.setDate(2, Date.valueOf(cycle.start))
            statement.setString(3, claim.claimId.toString())
            check(statement.executeUpdate() == 1) { "Confirmed weekly kit claim changed during admin reset" }
        }
        WeeklyKitAdminResetStorageResult.RESET
    }
}

private data class WeeklyKitAdminResetClaim(
    val claimId: UUID,
    val rankId: String,
    val kitId: String,
    val serverId: String,
    val state: WeeklyKitClaimState,
)
