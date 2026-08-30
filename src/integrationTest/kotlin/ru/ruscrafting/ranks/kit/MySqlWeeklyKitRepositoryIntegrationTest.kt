package ru.ruscrafting.ranks.kit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
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
})
