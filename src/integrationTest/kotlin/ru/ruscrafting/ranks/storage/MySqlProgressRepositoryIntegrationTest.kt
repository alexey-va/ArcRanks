package ru.ruscrafting.ranks.storage

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlRuntime
import ru.arc.sql.SqlSslMode
import ru.arc.testing.containers.MySqlTestService
import ru.arc.testing.containers.MySqlTestSettings
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.progress.ProgressMutation
import java.util.UUID

class MySqlProgressRepositoryIntegrationTest : StringSpec({
    "migrations and cross-server progress operations are idempotent" {
        MySqlTestService.start(MySqlTestSettings(database = "arc_ranks_test")).use { mysql ->
            val endpoint = mysql.endpoint
            SqlRuntime.create(
                SqlConnectionConfig(
                    host = endpoint.host,
                    port = endpoint.port,
                    database = endpoint.database,
                    username = endpoint.username,
                    password = endpoint.password,
                    sslMode = SqlSslMode.DISABLED,
                    minimumIdle = 0,
                    maximumPoolSize = 2,
                ),
                "arc-ranks-test",
            ).use { runtime ->
                val repository = MySqlProgressRepository(runtime)
                repository.initialize().join()
                repository.initialize().join()
                val player = UUID.randomUUID()

                repository.applyMutations(
                    player,
                    listOf(
                        ProgressMutation.Add(ProgressMetric.CROPS_HARVESTED, 4),
                        ProgressMutation.Maximum(ProgressMetric.WEALTH_PEAK, 100),
                    ),
                ).join()
                repository.applyMutations(
                    player,
                    listOf(
                        ProgressMutation.Add(ProgressMetric.CROPS_HARVESTED, 6),
                        ProgressMutation.Maximum(ProgressMetric.WEALTH_PEAK, 80),
                    ),
                ).join()
                repository.selectFocus(player, SpecializationPath.BUILDING).join()

                val event = ExternalProgressEvent(
                    player, "integration", "event-1", ProgressMetric.BLOCKS_PLACED, 9,
                )
                repository.recordExternalEvent(event).join() shouldBe ExternalProgressResult.APPLIED
                repository.recordExternalEvent(event).join() shouldBe ExternalProgressResult.DUPLICATE

                val profile = repository.load(player).join()
                profile.progress.value(ProgressMetric.CROPS_HARVESTED) shouldBe 10
                profile.progress.value(ProgressMetric.WEALTH_PEAK) shouldBe 100
                profile.progress.value(ProgressMetric.BLOCKS_PLACED) shouldBe 9
                profile.selectedFocus shouldBe SpecializationPath.BUILDING
            }
        }
    }
})
