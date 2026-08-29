package ru.ruscrafting.ranks.perk

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlRuntime
import ru.arc.sql.SqlSslMode
import ru.arc.testing.containers.MySqlTestService
import ru.arc.testing.containers.MySqlTestSettings
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MySqlPerkSelectionRepositoryIntegrationTest : StringSpec({
    "two concurrent slots remain bounded and removal preserves the other perk" {
        MySqlTestService.start(MySqlTestSettings(database = "arc_ranks_perks_test")).use { mysql ->
            val endpoint = mysql.endpoint
            SqlRuntime.create(
                SqlConnectionConfig(
                    endpoint.host, endpoint.port, endpoint.database, endpoint.username, endpoint.password,
                    sslMode = SqlSslMode.DISABLED, minimumIdle = 0, maximumPoolSize = 3,
                ),
                "arc-ranks-perks-test",
            ).use { runtime ->
                val repository = MySqlPerkSelectionRepository(runtime)
                repository.initialize().join()
                val player = UUID.randomUUID()
                val attempts = listOf(
                    repository.equip(player, PerkId("farming_momentum")),
                    repository.equip(player, PerkId("industry_momentum")),
                    repository.equip(player, PerkId("building_momentum")),
                )
                CompletableFuture.allOf(*attempts.toTypedArray()).join()

                val selected = repository.load(player).join()
                selected.active.size shouldBe PerkSelection.MAX_SLOTS
                val retained = selected.active.last()
                repository.remove(player, selected.active.first()).join().removed shouldBe true
                repository.load(player).join().active shouldBe listOf(retained)
            }
        }
    }
})
