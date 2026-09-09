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
import ru.ruscrafting.ranks.quest.QuestMode
import ru.ruscrafting.ranks.quest.QuestPlan
import ru.ruscrafting.ranks.quest.QuestStep
import ru.ruscrafting.ranks.quest.QuestReplaceResult
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
                // Simulate a retry after DDL committed but before schema history was written.
                runtime.executor.write { connection ->
                    RankMigrations.ALL.single { it.version == 13 }.statements.forEach { sql ->
                        connection.createStatement().use { it.execute(sql) }
                    }
                }.join()
                val legacyPlayer = UUID.randomUUID()
                runtime.executor.write { connection ->
                    connection.prepareStatement("INSERT INTO arc_ranks_daily_quest (player_uuid, quest_id, quest_day, value) VALUES (?, 'farming', UTC_DATE(), 37)").use {
                        it.setString(1, legacyPlayer.toString()); it.executeUpdate()
                    }
                }.join()
                val legacyFailure = runCatching { repository.initialize().join() }.exceptionOrNull()
                (legacyFailure?.cause?.message?.contains("Legacy v11 daily progress") == true) shouldBe true
                runtime.executor.write { connection ->
                    connection.prepareStatement("SELECT value FROM arc_ranks_daily_quest WHERE player_uuid = ?").use {
                        it.setString(1, legacyPlayer.toString())
                        it.executeQuery().use { rows -> rows.next() shouldBe true; rows.getLong(1) shouldBe 37L }
                    }
                    connection.prepareStatement("DELETE FROM arc_ranks_daily_quest WHERE player_uuid = ?").use {
                        it.setString(1, legacyPlayer.toString()); it.executeUpdate()
                    }
                }.join()
                repository.initialize().join()
                val trackingPlayer = UUID.randomUUID()
                val tracking = ru.ruscrafting.ranks.quest.MySqlQuestTrackingRepository(runtime)
                tracking.loadMode(trackingPlayer).join() shouldBe ru.ruscrafting.ranks.quest.QuestDisplayMode.SCOREBOARD
                tracking.saveMode(trackingPlayer, ru.ruscrafting.ranks.quest.QuestDisplayMode.OFF).join()
                ru.ruscrafting.ranks.quest.MySqlQuestTrackingRepository(runtime).loadMode(trackingPlayer).join() shouldBe ru.ruscrafting.ranks.quest.QuestDisplayMode.OFF
                val firstPin = ru.ruscrafting.ranks.quest.TrackedQuest(DailyQuest.day(dayOne), "farming_daily")
                val secondPin = firstPin.copy(questId = "industry_daily")
                tracking.save(trackingPlayer, firstPin).join()
                ru.ruscrafting.ranks.quest.MySqlQuestTrackingRepository(runtime).load(trackingPlayer).join() shouldBe firstPin
                tracking.save(trackingPlayer, secondPin).join()
                tracking.clear(trackingPlayer, firstPin).join()
                tracking.load(trackingPlayer).join() shouldBe secondPin
                tracking.clear(trackingPlayer, secondPin).join()
                tracking.load(trackingPlayer).join() shouldBe null
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

                val focusCatalog = DailyQuestCatalog(
                    countByRank = mapOf("settler" to 2),
                    pool = listOf(
                        DailyQuest("focus_farming", ProgressMetric.CROPS_HARVESTED, 12, 3, "WHEAT", objective = "focus.farming", family = "focus_farming"),
                        DailyQuest("focus_farming_alt", ProgressMetric.CROPS_HARVESTED, 14, 3, "WHEAT", objective = "focus.farming_alt", family = "focus_farming_alt"),
                        DailyQuest("focus_building", ProgressMetric.BLOCKS_PLACED, 16, 3, "BRICKS", objective = "focus.building", family = "focus_building"),
                        DailyQuest("focus_industry", ProgressMetric.PRODUCTION_ACTIONS, 18, 3, "CRAFTING_TABLE", objective = "focus.industry", family = "focus_industry"),
                    ),
                    rareChancePercent = 0,
                    focusPercent = 100,
                )
                val focusDaily = MySqlDailyQuestRepository(
                    runtime,
                    catalog = { focusCatalog },
                    rank = { CompletableFuture.completedFuture("settler") },
                    clock = Clock.fixed(dayOne, ZoneOffset.UTC),
                )
                val focusPlayer = UUID.randomUUID()
                MySqlProgressRepository(runtime, focusDaily).selectFocus(focusPlayer, SpecializationPath.BUILDING).join()
                val focusBoard = focusDaily.board(focusPlayer).join()
                focusBoard.quests.count { SpecializationPath.BUILDING.owns(it.quest.metric) } shouldBe 1
                focusBoard.quests.size shouldBe 2
                focusBoard.quests.count { !SpecializationPath.BUILDING.owns(it.quest.metric) } shouldBe 1

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

                val chainCatalog = DailyQuestCatalog(
                    countByRank = mapOf("settler" to 1),
                    pool = listOf(
                        DailyQuest(
                            "chain_order", ProgressMetric.PRODUCTION_ACTIONS, 2, 10, "CRAFTING_TABLE",
                            objective = "quest.chain_order",
                            plan = QuestPlan(
                                QuestMode.CHAIN,
                                listOf(
                                    QuestStep("harvest:wheat", 16, "harvest_wheat"),
                                    QuestStep("craft:bread", 4, "craft_bread"),
                                ),
                            ),
                            family = "chain_order",
                        ),
                    ),
                    rareChancePercent = 0,
                )
                val chainDaily = MySqlDailyQuestRepository(
                    runtime,
                    catalog = { chainCatalog },
                    rank = { CompletableFuture.completedFuture("settler") },
                    clock = Clock.fixed(dayOne, ZoneOffset.UTC),
                )
                val chainRepository = MySqlProgressRepository(runtime, chainDaily)
                val chainPlayer = UUID.randomUUID()
                chainDaily.board(chainPlayer).join().quests.single().stepValues shouldBe listOf(0L, 0L)
                chainRepository.recordQuestEvent(
                    "integration", "chain-before-first", chainPlayer, "craft:bread", 4,
                ).join() shouldBe ExternalProgressResult.APPLIED
                chainDaily.board(chainPlayer).join().quests.single().stepValues shouldBe listOf(0L, 0L)
                chainRepository.recordQuestEvent(
                    "integration", "chain-first", chainPlayer, "harvest:wheat", 32,
                ).join() shouldBe ExternalProgressResult.APPLIED
                val halfway = chainDaily.board(chainPlayer).join().quests.single()
                halfway.stepValues shouldBe listOf(16L, 0L)
                halfway.value shouldBe 1L
                MySqlDailyQuestRepository(
                    runtime,
                    catalog = { chainCatalog },
                    rank = { CompletableFuture.completedFuture("settler") },
                    clock = Clock.fixed(dayOne, ZoneOffset.UTC),
                ).board(chainPlayer).join().quests.single().stepValues shouldBe listOf(16L, 0L)
                // Operator resets cannot bypass event deduplication or manufacture a payout.
                chainDaily.adminResetProgress(chainPlayer, DailyQuest.day(dayTwo), null).join() shouldBe 0
                chainDaily.adminResetProgress(chainPlayer, DailyQuest.day(dayOne), "missing").join() shouldBe 0
                chainDaily.adminResetProgress(chainPlayer, DailyQuest.day(dayOne), "chain_order").join() shouldBe 1
                chainDaily.board(chainPlayer).join().quests.single().stepValues shouldBe listOf(0L, 0L)
                chainDaily.board(chainPlayer).join().quests.single().value shouldBe 0L
                chainRepository.recordQuestEvent(
                    "integration", "chain-first", chainPlayer, "harvest:wheat", 32,
                ).join() shouldBe ExternalProgressResult.DUPLICATE
                chainDaily.board(chainPlayer).join().quests.single().value shouldBe 0L
                chainDaily.pendingRewards(chainPlayer).join().size shouldBe 0
                chainRepository.recordQuestEvent(
                    "integration", "chain-first-after-reset", chainPlayer, "harvest:wheat", 16,
                ).join() shouldBe ExternalProgressResult.APPLIED
                chainRepository.recordQuestEvent(
                    "integration", "chain-final", chainPlayer, "craft:bread", 4,
                ).join() shouldBe ExternalProgressResult.APPLIED
                chainRepository.recordQuestEvent(
                    "integration", "chain-final", chainPlayer, "craft:bread", 4,
                ).join() shouldBe ExternalProgressResult.DUPLICATE
                chainDaily.board(chainPlayer).join().quests.single().let {
                    it.value shouldBe 2L
                    it.stepValues shouldBe listOf(16L, 4L)
                }
                chainDaily.pendingRewards(chainPlayer).join().size shouldBe 1
                val summaryBeforeRollover = chainDaily.pendingRewards(chainPlayer).join().single().questSummary
                summaryBeforeRollover shouldBe ru.ruscrafting.ranks.reward.QuestRewardSummary(
                    "chain_order", ProgressMetric.PRODUCTION_ACTIONS, 10,
                )
                val completedProgress = chainRepository.load(chainPlayer).join().progress
                chainDaily.adminResetProgress(chainPlayer, DailyQuest.day(dayOne), null).join() shouldBe 0
                chainDaily.board(chainPlayer).join().quests.single().value shouldBe 2L
                chainDaily.pendingRewards(chainPlayer).join().size shouldBe 1
                chainRepository.load(chainPlayer).join().progress shouldBe completedProgress
                MySqlDailyQuestRepository(runtime, catalog = { chainCatalog },
                    clock = Clock.fixed(dayTwo, ZoneOffset.UTC)).board(chainPlayer).join()
                chainDaily.pendingRewards(chainPlayer).join().single().questSummary shouldBe summaryBeforeRollover

                val onceCatalog = DailyQuestCatalog(
                    countByRank = mapOf("settler" to 1),
                    pool = listOf(
                        DailyQuest(
                            "once_only", ProgressMetric.COMMUNITY_MINUTES, 1, 2, "CAMPFIRE",
                            objective = "once.objective", once = true, rareEligible = false,
                        ),
                        DailyQuest(
                            "ordinary_alt", ProgressMetric.COMMUNITY_MINUTES, 1, 2, "CAMPFIRE",
                            objective = "ordinary.objective",
                        ),
                    ),
                    rareChancePercent = 0,
                    replacementsPerDay = 1,
                )
                val onceDaily = MySqlDailyQuestRepository(
                    runtime,
                    catalog = { onceCatalog },
                    rank = { CompletableFuture.completedFuture("settler") },
                    clock = Clock.fixed(dayOne, ZoneOffset.UTC),
                )
                val onceRepository = MySqlProgressRepository(runtime, onceDaily)
                val oncePlayer = UUID.randomUUID()
                val onceQuest = onceDaily.board(oncePlayer).join().quests.single().quest
                onceQuest.id shouldBe "once_only"
                onceRepository.recordQuestEvent(
                    "integration", "once-complete", oncePlayer, onceQuest.objective, onceQuest.target,
                ).join() shouldBe ExternalProgressResult.APPLIED
                onceRepository.recordQuestEvent(
                    "integration", "once-complete", oncePlayer, onceQuest.objective, onceQuest.target,
                ).join() shouldBe ExternalProgressResult.DUPLICATE
                onceDaily.replace(oncePlayer, DailyQuest.day(dayOne), onceQuest.id).join() shouldBe QuestReplaceResult.COMPLETED
                onceDaily.pendingRewards(oncePlayer).join().size shouldBe 1
                val onceNextDay = MySqlDailyQuestRepository(
                    runtime,
                    catalog = { onceCatalog },
                    rank = { CompletableFuture.completedFuture("settler") },
                    clock = Clock.fixed(dayTwo, ZoneOffset.UTC),
                )
                onceNextDay.board(oncePlayer).join().quests.single().quest.id shouldBe "ordinary_alt"

                val rerollPlayer = UUID.randomUUID()
                val initialReroll = onceDaily.board(rerollPlayer).join().quests.single().quest
                onceDaily.replace(rerollPlayer, DailyQuest.day(dayOne), initialReroll.id).join() shouldBe QuestReplaceResult.REPLACED
                val replacement = onceDaily.board(rerollPlayer).join().quests.single().quest
                onceDaily.replace(rerollPlayer, DailyQuest.day(dayOne), replacement.id).join() shouldBe QuestReplaceResult.LIMIT

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
