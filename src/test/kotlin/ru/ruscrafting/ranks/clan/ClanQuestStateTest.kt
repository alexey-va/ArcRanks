package ru.ruscrafting.ranks.clan

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.ranks.quest.QuestMode
import ru.ruscrafting.ranks.quest.QuestPlan
import ru.ruscrafting.ranks.quest.QuestStep
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class ClanQuestStateTest : StringSpec({
    val first = UUID(0, 1)
    val second = UUID(0, 2)
    fun project(vararg steps: Pair<String, Long>) = ClanQuestDefinition("test", "test",
        QuestPlan(QuestMode.ALL, steps.map { (objective, target) -> QuestStep(objective, target, "step") }), "BOOK")

    "cycles change precisely at Monday midnight UTC" {
        ClanQuestCycle.week(Instant.parse("2026-10-25T23:59:59.999Z")) shouldBe LocalDate.parse("2026-10-19")
        ClanQuestCycle.week(Instant.parse("2026-10-26T00:00:00Z")) shouldBe LocalDate.parse("2026-10-26")
        ClanQuestCycle.week(Instant.parse("2026-10-26T00:00:00+03:00")) shouldBe LocalDate.parse("2026-10-19")
        shouldThrow<IllegalArgumentException> { ClanQuestCycle.validate(LocalDate.parse("2026-10-25")) }
    }

    "one member is capped independently on every numeric step and a second member completes the shared plan" {
        val definition = project("harvest:wheat" to 128L, "craft:bread" to 32L)
        val initial = ClanQuestState(definition)
        val capped = initial.advance(first, "harvest:wheat", Long.MAX_VALUE)
            .advance(first, "craft:bread", Long.MAX_VALUE)
        capped.values shouldBe listOf(76L, 19L)
        capped.contributions[first] shouldBe listOf(76L, 19L)
        capped.completed shouldBe false
        capped.advance(first, "craft:bread", 100L) shouldBe capped
        initial.values shouldBe listOf(0L, 0L)

        val completed = capped.advance(second, "harvest:wheat", 100L).advance(second, "craft:bread", 100L)
        completed.values shouldBe listOf(128L, 32L)
        completed.contributions[second] shouldBe listOf(52L, 13L)
        completed.contributors shouldBe 2
        completed.completed shouldBe true
        completed.advance(second, "craft:bread", 100L) shouldBe completed
    }

    "unrelated and grouped actions cannot bypass ordinary contribution accounting" {
        val initial = ClanQuestState(project("build:stone_bricks" to 128L, "build:glass" to 64L))
        initial.advance(first, "build:oak_planks", 64L) shouldBe initial
        initial.advanceGroup(setOf(first, second), "build:glass") shouldBe initial
        initial.advance(first, "build:glass", 64L).values shouldBe listOf(0L, 38L)
    }

    "group credit advances once per event and records each member attendance without the numeric cap" {
        val initial = ClanQuestState(project("clan.dungeon_run" to 2L))
        initial.advanceGroup(setOf(first), "clan.dungeon_run") shouldBe initial
        initial.advance(first, "clan.dungeon_run", 2L) shouldBe initial
        val once = initial.advanceGroup(setOf(first, second), "clan.dungeon_run")
        once.values shouldBe listOf(1L)
        once.contributions[first] shouldBe listOf(1L)
        once.contributions[second] shouldBe listOf(1L)
        once.completed shouldBe false
        val twice = once.advanceGroup(setOf(first, second), "clan.dungeon_run")
        twice.values shouldBe listOf(2L)
        twice.contributions[first] shouldBe listOf(2L)
        twice.completed shouldBe true
        twice.advanceGroup(setOf(first, second), "clan.dungeon_run") shouldBe twice
    }

    "a boss target of one still requires a captured cooperative event" {
        val initial = ClanQuestState(project("clan.boss_kill" to 1L, "clan.arena_match" to 3L))
        val boss = initial.advanceGroup(setOf(first, second), "clan.boss_kill")
        boss.values shouldBe listOf(1L, 0L)
        boss.contributors shouldBe 2
        boss.completed shouldBe false
        val finished = (1..3).fold(boss) { state, _ -> state.advanceGroup(setOf(first, second), "clan.arena_match") }
        finished.completed shouldBe true
        finished.values shouldBe listOf(1L, 3L)
    }

    "invalid caps rewards and mixed participation units are rejected" {
        val numeric = project("craft:bread" to 32L)
        shouldThrow<IllegalArgumentException> { numeric.copy(capPercent = 61) }
        shouldThrow<IllegalArgumentException> { numeric.copy(minContributors = 1) }
        shouldThrow<IllegalArgumentException> { numeric.copy(renown = 2) }
        shouldThrow<IllegalArgumentException> { project("craft:bread" to 1L) }
        shouldThrow<IllegalArgumentException> { project("craft:bread" to 32L, "clan.boss_kill" to 1L) }
    }
})
