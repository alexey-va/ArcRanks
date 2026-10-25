package ru.ruscrafting.ranks.clan

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlRuntime
import ru.arc.sql.SqlSslMode
import ru.arc.testing.containers.MySqlTestService
import ru.arc.testing.containers.MySqlTestSettings
import ru.ruscrafting.ranks.api.ClanMembership
import ru.ruscrafting.ranks.quest.QuestMode
import ru.ruscrafting.ranks.quest.QuestPlan
import ru.ruscrafting.ranks.quest.QuestStep
import ru.ruscrafting.ranks.storage.RankMigrations
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class ClanQuestRepositoryIntegrationTest : StringSpec({
    "frozen clan projects deduplicate across servers preserve history and bind only real weekly contributions" {
        MySqlTestService.start(MySqlTestSettings(database = "arc_ranks_clan_test")).use { mysql ->
            val endpoint = mysql.endpoint
            SqlRuntime.create(SqlConnectionConfig(endpoint.host, endpoint.port, endpoint.database,
                endpoint.username, endpoint.password, sslMode = SqlSslMode.DISABLED,
                minimumIdle = 0, maximumPoolSize = 4), "arc-ranks-clan-test").use { runtime ->
                runtime.executor.submit { MySqlMigrator(runtime.dataSource, "arc_ranks").migrate(RankMigrations.ALL) }.join()
                runtime.executor.submit { MySqlMigrator(runtime.dataSource, "arc_ranks").migrate(RankMigrations.ALL) }.join()
                val now = Instant.parse("2026-10-25T12:00:00Z")
                val next = Instant.parse("2026-10-26T00:00:00Z")
                val first = UUID(0, 1)
                val second = UUID(0, 2)
                val third = UUID(0, 3)
                val fourth = UUID(0, 4)
                val alpha = ClanMembership("justteams:1", "Alpha", setOf(first, second))
                val beta = ClanMembership("justteams:2", "Beta", setOf(first, third, fourth))
                fun project(id: String, objective: String, target: Long) = ClanQuestDefinition(id, id,
                    QuestPlan(QuestMode.ALL, listOf(QuestStep(objective, target, "step"))), "BOOK")
                var config = ClanQuestCatalog(listOf(project("boss", "clan.boss_kill", 1),
                    project("arena", "clan.arena_match", 3), project("dungeon", "clan.dungeon_run", 1)))
                val repository = ClanQuestRepository(runtime, { config }, Clock.fixed(now, ZoneOffset.UTC))
                val peer = ClanQuestRepository(runtime, { config }, Clock.fixed(now, ZoneOffset.UTC))
                repository.board(alpha, first).join().boundClanId shouldBe null
                repository.recordGroup("test", "solo", listOf(first to alpha), "clan.boss_kill", now).join() shouldBe 0
                repository.board(alpha, first).join().boundClanId shouldBe null

                val duplicateAttempts = listOf(repository, peer).map {
                    it.recordGroup("test", "boss-1", listOf(first to alpha, second to alpha), "clan.boss_kill", now)
                }
                CompletableFuture.allOf(*duplicateAttempts.toTypedArray()).join()
                duplicateAttempts.sumOf { it.join() } shouldBe 1
                val completed = repository.board(alpha, first).join()
                completed.renown shouldBe 1
                completed.totalCompletedCount shouldBe 1
                completed.quests.single { it.quest.id == "boss" }.contributors shouldBe 2
                completed.quests.single { it.quest.id == "boss" }.myContributions shouldBe listOf(1L)
                completed.boundClanId shouldBe alpha.clanId()

                repository.board(beta, first).join().contributionAllowed shouldBe false
                repository.recordGroup("test", "hopping", listOf(first to beta, third to beta), "clan.boss_kill", now).join() shouldBe 0
                repository.board(beta, third).join().boundClanId shouldBe null
                repository.recordGroup("test", "beta-boss", listOf(third to beta, fourth to beta), "clan.boss_kill", now).join() shouldBe 1
                repository.board(beta, first).join().quests.single { it.quest.id == "boss" }.myContributions shouldBe listOf(0L)

                config = config.copy(pool = config.pool.map { if (it.id == "boss") it.copy(plan = QuestPlan(QuestMode.ALL,
                    listOf(QuestStep("clan.boss_kill", 2, "step")))) else it })
                repository.board(alpha, first).join().quests.single { it.quest.id == "boss" }.quest.plan.steps.single().target shouldBe 1
                val nextWeek = ClanQuestRepository(runtime, { config }, Clock.fixed(next, ZoneOffset.UTC))
                nextWeek.board(alpha, first).join().quests.single { it.quest.id == "boss" }.quest.plan.steps.single().target shouldBe 2
                nextWeek.board(beta, first).join().contributionAllowed shouldBe true
                // Identity is global per clan/source/event, even if a producer retries with a new date.
                nextWeek.recordGroup("test", "boss-1", listOf(first to alpha, second to alpha), "clan.boss_kill", next).join() shouldBe 0
                nextWeek.recordGroup("test", "boss-2", listOf(first to alpha, second to alpha), "clan.boss_kill", next).join() shouldBe 1
                nextWeek.recordGroup("test", "boss-3", listOf(first to alpha, second to alpha), "clan.boss_kill", next).join() shouldBe 1
                nextWeek.board(alpha, first).join().renown shouldBe 2
                // Delayed work advances its captured old cycle after the newer board already exists.
                nextWeek.recordGroup("test", "old-arena", listOf(first to alpha, second to alpha), "clan.arena_match", now).join() shouldBe 1
                repository.board(alpha, first).join().quests.single { it.quest.id == "arena" }.values shouldBe listOf(1L)
                nextWeek.board(alpha, first).join().quests.single { it.quest.id == "arena" }.values shouldBe listOf(0L)

                config = ClanQuestCatalog(listOf(project("bread", "craft:bread", 10),
                    project("glass", "smelt:glass", 10), project("stone", "build:stone_bricks", 10)))
                val fifth = UUID(0, 5)
                val sixth = UUID(0, 6)
                val gamma = ClanMembership("justteams:3", "Gamma", setOf(fifth, sixth))
                val week = ClanQuestCycle.week(now)
                shouldThrow<CompletionException> {
                    runtime.executor.transaction<Unit> { connection ->
                        repository.advance(connection, fifth, gamma, "craft:bread", 10, week)
                        error("rollback")
                    }.join()
                }
                repository.board(gamma, fifth).join().boundClanId shouldBe null
                repository.board(gamma, fifth).join().quests.single { it.quest.id == "bread" }.values shouldBe listOf(0L)
                runtime.executor.transaction { connection -> repository.advance(connection, fifth, gamma, "craft:bread", 100, week) }.join()
                repository.board(gamma, fifth).join().quests.single { it.quest.id == "bread" }.values shouldBe listOf(6L)
                runtime.executor.transaction { connection -> repository.advance(connection, sixth, gamma, "craft:bread", 100, week) }.join()
                repository.board(gamma, sixth).join().renown shouldBe 1
                repository.board(gamma, sixth).join().quests.single { it.quest.id == "bread" }.myContributions shouldBe listOf(4L)
            }
        }
    }
})
