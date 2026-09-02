package ru.ruscrafting.ranks.kit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlRuntime
import ru.arc.sql.SqlSslMode
import ru.arc.testing.containers.MySqlTestService
import ru.arc.testing.containers.MySqlTestSettings
import ru.ruscrafting.ranks.domain.RankId
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MySqlWeeklyKitRepositoryIntegrationTest : StringSpec({
    "one cross-server weekly reservation is confirmed without duplicate delivery" {
        MySqlTestService.start(MySqlTestSettings(database = "arc_ranks_weekly_kit_test")).use { mysql ->
            val endpoint = mysql.endpoint
            SqlRuntime.create(
                SqlConnectionConfig(
                    endpoint.host, endpoint.port, endpoint.database, endpoint.username, endpoint.password,
                    sslMode = SqlSslMode.DISABLED, minimumIdle = 0, maximumPoolSize = 3,
                ),
                "arc-ranks-weekly-kit-test",
            ).use { runtime ->
                val repository = MySqlWeeklyKitRepository(runtime)
                repository.initialize().join()
                val playerId = UUID.randomUUID()
                val cycle = WeeklyKitCycle.at(Instant.parse("2026-08-30T18:00:00Z"))

                val attempts = listOf(
                    repository.begin(playerId, cycle, RankId("citizen"), "arcranks_weekly_citizen", "classic"),
                    repository.begin(playerId, cycle, RankId("citizen"), "arcranks_weekly_citizen", "classic_survival"),
                )
                CompletableFuture.allOf(*attempts.toTypedArray()).join()
                val results = attempts.map { it.join() }
                results.count { it is WeeklyKitBeginResult.Ready } shouldBe 1
                results.count { it is WeeklyKitBeginResult.DeliveryPending } shouldBe 1

                val reservation = (results.single { it is WeeklyKitBeginResult.Ready } as WeeklyKitBeginResult.Ready).reservation
                repository.confirm(reservation).join() shouldBe true
                repository.state(playerId, cycle).join() shouldBe WeeklyKitClaimState.CLAIMED
                repository.begin(
                    playerId, cycle, RankId("citizen"), "arcranks_weekly_citizen", "classic_survival",
                ).join() shouldBe WeeklyKitBeginResult.AlreadyClaimed
                repository.release(reservation).join() shouldBe false
            }
        }
    }


    "an admin reset archives a confirmed claim but refuses an in-flight delivery" {
        MySqlTestService.start(MySqlTestSettings(database = "arc_ranks_weekly_kit_admin_test")).use { mysql ->
            val endpoint = mysql.endpoint
            SqlRuntime.create(
                SqlConnectionConfig(
                    endpoint.host, endpoint.port, endpoint.database, endpoint.username, endpoint.password,
                    sslMode = SqlSslMode.DISABLED, minimumIdle = 0, maximumPoolSize = 3,
                ),
                "arc-ranks-weekly-kit-admin-test",
            ).use { runtime ->
                val repository = MySqlWeeklyKitRepository(runtime)
                repository.initialize().join()
                val cycle = WeeklyKitCycle.at(Instant.parse("2026-08-30T18:00:00Z"))
                val claimedPlayer = UUID.randomUUID()
                val pendingPlayer = UUID.randomUUID()

                val claimed = repository.begin(
                    claimedPlayer, cycle, RankId("citizen"), "arcranks_weekly_citizen", "classic",
                ).join() as WeeklyKitBeginResult.Ready
                repository.confirm(claimed.reservation).join() shouldBe true
                repository.adminReset(claimedPlayer, cycle, "CONSOLE").join() shouldBe WeeklyKitAdminResetStorageResult.RESET
                repository.state(claimedPlayer, cycle).join() shouldBe WeeklyKitClaimState.AVAILABLE

                val audit = runtime.executor.read { connection ->
                    connection.prepareStatement(
                        "SELECT `claim_id`, `admin_actor` FROM `arc_ranks_weekly_kit_admin_reset` WHERE `player_uuid` = ?",
                    ).use { statement ->
                        statement.setString(1, claimedPlayer.toString())
                        statement.executeQuery().use { result ->
                            check(result.next())
                            result.getString("claim_id") to result.getString("admin_actor")
                        }
                    }
                }.join()
                audit shouldBe claimed.reservation.claimId.toString() to "CONSOLE"

                repository.begin(
                    pendingPlayer, cycle, RankId("citizen"), "arcranks_weekly_citizen", "classic_survival",
                ).join().shouldBeInstanceOf<WeeklyKitBeginResult.Ready>()
                repository.adminReset(pendingPlayer, cycle, "CONSOLE").join() shouldBe
                    WeeklyKitAdminResetStorageResult.DELIVERY_PENDING
                repository.adminReset(UUID.randomUUID(), cycle, "CONSOLE").join() shouldBe
                    WeeklyKitAdminResetStorageResult.NOT_CLAIMED
            }
        }
    }
})
