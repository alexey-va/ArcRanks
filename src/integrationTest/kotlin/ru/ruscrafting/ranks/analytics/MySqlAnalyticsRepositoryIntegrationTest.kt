package ru.ruscrafting.ranks.analytics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlRuntime
import ru.arc.sql.SqlSslMode
import ru.arc.testing.containers.MySqlTestService
import ru.arc.testing.containers.MySqlTestSettings
import ru.ruscrafting.ranks.domain.RankId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class MySqlAnalyticsRepositoryIntegrationTest : StringSpec({
    "a telemetry batch is idempotent and summaries keep bounded product dimensions" {
        MySqlTestService.start(MySqlTestSettings(database = "arc_ranks_analytics_test")).use { mysql ->
            val endpoint = mysql.endpoint
            SqlRuntime.create(
                SqlConnectionConfig(
                    endpoint.host, endpoint.port, endpoint.database, endpoint.username, endpoint.password,
                    sslMode = SqlSslMode.DISABLED, minimumIdle = 0, maximumPoolSize = 2,
                ),
                "arc-ranks-analytics-test",
            ).use { runtime ->
                val repository = MySqlAnalyticsRepository(runtime)
                repository.initialize().join()
                val now = Instant.parse("2026-08-29T18:42:00Z")
                val firstPlayer = UUID.randomUUID()
                val secondPlayer = UUID.randomUUID()
                val batch = TelemetryBatch(
                    bucketStart = Instant.parse("2026-08-29T18:00:00Z"),
                    serverId = "survival",
                    counters = mapOf(
                        ProductMetricKey(ProductEvent.PROMOTION_ATTEMPT, ProductDimension.NONE) to 4,
                        ProductMetricKey(ProductEvent.PROMOTION_SUCCESS, ProductDimension.NONE) to 3,
                        ProductMetricKey(
                            ProductEvent.RECOMMENDATION_SHOWN,
                            ProductDimension("path:farming"),
                        ) to 2,
                    ),
                    players = listOf(
                        PlayerDaySignal(
                            LocalDate.parse("2026-08-29"),
                            firstPlayer,
                            setOf(
                                PlayerSignal.SEEN,
                                PlayerSignal.PASSPORT_OPENED,
                                PlayerSignal.CONTRACT_ACCEPTED,
                                PlayerSignal.CONTRACT_COMPLETED,
                                PlayerSignal.PERK_SELECTED,
                            ),
                            RankId("citizen"),
                        ),
                        PlayerDaySignal(
                            LocalDate.parse("2026-08-29"),
                            secondPlayer,
                            setOf(PlayerSignal.SEEN),
                            RankId("settler"),
                        ),
                    ),
                    batchId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                )

                repository.write(batch).join()
                repository.write(batch).join()

                val summary = repository.summary(7, now).join()
                summary.uniqueSeenPlayers shouldBe 2
                summary.passportPlayers shouldBe 1
                summary.contractAcceptedPlayers shouldBe 1
                summary.contractCompletedPlayers shouldBe 1
                summary.perkSelectedPlayers shouldBe 1
                summary.eventTotals[ProductEvent.PROMOTION_ATTEMPT] shouldBe 4
                summary.eventTotals[ProductEvent.PROMOTION_SUCCESS] shouldBe 3
                summary.recommendationTotals["path:farming"] shouldBe 2
            }
        }
    }
})
