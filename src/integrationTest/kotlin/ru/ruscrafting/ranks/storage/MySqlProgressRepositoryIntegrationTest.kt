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
import ru.ruscrafting.ranks.quest.DailyQuest
import ru.ruscrafting.ranks.quest.DailyQuestCatalog
import ru.ruscrafting.ranks.quest.MySqlDailyQuestRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture

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
                val dayOne = Instant.parse("2026-09-08T12:00:00Z")
                val dayTwo = Instant.parse("2026-09-09T12:00:00Z")
                var rankId = "settler"
                val dailyCatalog = DailyQuestCatalog(
                    countByRank = mapOf("settler" to 1, "caesar" to 2),
                    pool = listOf(
                        DailyQuest(
                            "farming_daily", ProgressMetric.CROPS_HARVESTED, 100, 10, "WHEAT",
                            money = 50, objective = "path.farming",
                        ),
                        DailyQuest(
                            "industry_daily", ProgressMetric.PRODUCTION_ACTIONS, 100, 10, "CRAFTING_TABLE",
                            money = 50, objective = "path.industry",
                        ),
                    ),
                    rareChancePercent = 0,
                )
                val daily = MySqlDailyQuestRepository(
                    runtime,
                    catalog = { dailyCatalog },
                    rank = { CompletableFuture.completedFuture(rankId) },
                    clock = Clock.fixed(dayOne, ZoneOffset.UTC),
                )
                val repository = MySqlProgressRepository(runtime, daily)
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

                val frozenPlayer = UUID.randomUUID()
                val frozenIds = repository.dailyQuests.board(frozenPlayer).join().quests.map { it.quest.id }
                rankId = "caesar"
                repository.dailyQuests.board(frozenPlayer).join().quests.map { it.quest.id } shouldBe frozenIds
                rankId = "settler"

                val dailyPlayer = UUID.randomUUID()
                val selectedQuest = repository.dailyQuests.board(dailyPlayer).join().quests.first().quest
                val peer = MySqlProgressRepository(runtime, daily)
                val writes = (1..10).map { index ->
                    (if (index % 2 == 0) repository else peer).applyMutations(
                        dailyPlayer,
                        listOf(ProgressMutation.Add(selectedQuest.metric, 10, mapOf(selectedQuest.objective to 10L))),
                    )
                }
                java.util.concurrent.CompletableFuture.allOf(*writes.toTypedArray()).join()
                repository.load(dailyPlayer).join().progress.value(selectedQuest.metric) shouldBe 110
                peer.dailyQuests.board(dailyPlayer).join().quests.first { it.quest.id == selectedQuest.id }.value shouldBe 100
                val adminPlayer = UUID.randomUUID()
                val adminQuest = repository.dailyQuests.board(adminPlayer).join().quests.first().quest
                repository.recordExternalEvent(ExternalProgressEvent(
                    adminPlayer, "admin-gui", "daily-admin", adminQuest.metric, 100,
                )).join()
                repository.dailyQuests.board(adminPlayer).join().quests.first { it.quest.id == adminQuest.id }.value shouldBe 0
                peer.applyMutations(
                    dailyPlayer,
                    listOf(ProgressMutation.Add(selectedQuest.metric, 10, mapOf(selectedQuest.objective to 10L))),
                ).join()
                repository.load(dailyPlayer).join().progress.value(selectedQuest.metric) shouldBe 120

                val rolloverPlayer = UUID.randomUUID()
                val rolloverQuest = repository.dailyQuests.board(rolloverPlayer).join().quests.first().quest
                repository.recordQuestEvent(
                    "integration", "daily-event-1", rolloverPlayer, rolloverQuest.objective, rolloverQuest.target,
                ).join() shouldBe ExternalProgressResult.APPLIED
                repository.recordQuestEvent(
                    "integration", "daily-event-1", rolloverPlayer, rolloverQuest.objective, rolloverQuest.target,
                ).join() shouldBe ExternalProgressResult.DUPLICATE
                repository.dailyQuests.board(rolloverPlayer).join().quests.first { it.quest.id == rolloverQuest.id }.value shouldBe rolloverQuest.target
                runtime.executor.read { connection ->
                    connection.prepareStatement(
                        "SELECT COUNT(*) FROM arc_ranks_quest_event WHERE source = ? AND event_id = ?",
                    ).use {
                        it.setString(1, "integration"); it.setString(2, "daily-event-1")
                        it.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) }
                    }
                }.join() shouldBe 1
                repository.dailyQuests.pendingRewards(rolloverPlayer).join().size shouldBe 1

                rankId = "caesar"
                val nextDay = MySqlDailyQuestRepository(
                    runtime,
                    catalog = { dailyCatalog },
                    rank = { CompletableFuture.completedFuture(rankId) },
                    clock = Clock.fixed(dayTwo, ZoneOffset.UTC),
                )
                nextDay.board(rolloverPlayer).join().quests.size shouldBe 2
                nextDay.board(rolloverPlayer).join().quests.all { it.value == 0L } shouldBe true
                nextDay.pendingRewards(rolloverPlayer).join().size shouldBe 1
                rankId = "settler"

                val rolledBackPlayer = UUID.randomUUID()
                val rollbackQuest = repository.dailyQuests.board(rolledBackPlayer).join().quests.first().quest
                val rollbackDay = repository.dailyQuests.board(rolledBackPlayer).join().day
                runCatching {
                    runtime.executor.transaction { connection ->
                        repository.dailyQuests.advance(connection, rolledBackPlayer, rollbackDay, rollbackQuest.objective, rollbackQuest.target) shouldBe
                            mapOf(rollbackQuest.metric to rollbackQuest.bonus)
                        error("rollback fixture")
                    }.join()
                }.isFailure shouldBe true
                repository.dailyQuests.board(rolledBackPlayer).join().quests.first { it.quest.id == rollbackQuest.id }.value shouldBe 0

            }
        }
    }
})
