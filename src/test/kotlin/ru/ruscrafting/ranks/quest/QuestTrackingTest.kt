package ru.ruscrafting.ranks.quest

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class QuestTrackingTest : StringSpec({
    "missing or invalid display modes remain recoverable" {
        QuestDisplayMode.fromStored("OFF") shouldBe QuestDisplayMode.OFF
        QuestDisplayMode.fromStored(null) shouldBe QuestDisplayMode.SCOREBOARD
        QuestDisplayMode.fromStored("BROKEN") shouldBe QuestDisplayMode.SCOREBOARD
    }
    "chain HUD and hints follow the current unfinished stage" {
        val plan = QuestPlan(QuestMode.CHAIN, listOf(QuestStep("harvest", 16, "harvest"), QuestStep("craft", 4, "craft")))
        val quest = DailyQuest.ALL.first().copy(plan = plan, target = plan.target)
        val first = DailyQuestProgress(quest, 0, stepValues = listOf(12, 0))
        QuestTrackingView.of(first) shouldBe QuestTrackingView("farming", 12, 16, "harvest", "harvest", 0)
        DailyQuestHints.categories(first) shouldBe listOf("chain", "harvest")
        val second = first.copy(value = 1, stepValues = listOf(16, 2))
        QuestTrackingView.of(second) shouldBe QuestTrackingView("farming", 2, 4, "craft", "craft", 1)
        DailyQuestHints.categories(second) shouldBe listOf("chain", "craft")
    }
    "alternative goals show partial progress instead of a permanent zero of one" {
        val plan = QuestPlan(QuestMode.ANY, listOf(QuestStep("craft", 20, "craft"), QuestStep("builder.use", 4, "builder")))
        val state = DailyQuestProgress(DailyQuest.ALL.first().copy(plan = plan, target = 1), 0, stepValues = listOf(1, 2))
        QuestTrackingView.of(state) shouldBe QuestTrackingView("farming", 2, 4, "builder", "builder.use", 1)
    }
    "provider and team hints describe real events" {
        DailyQuestHints.categories(DailyQuestProgress(DailyQuest.ALL.first().copy(objective = "vote.confirmed"), 0)) shouldBe listOf("vote")
        val plan = QuestPlan(QuestMode.ANY, listOf(QuestStep("farm.job:team", 1, "farm_job")))
        DailyQuestHints.categories(DailyQuestProgress(DailyQuest.ALL.first().copy(plan = plan, target = 1), 0)) shouldBe listOf("any", "team")
    }
    "near-completion suggestions compare entire plans and never select unavailable or completed goals" {
        val q = DailyQuest.ALL.first()
        val near = DailyQuestProgress(q, 88)
        val plan = QuestPlan(QuestMode.CHAIN, listOf(QuestStep("harvest", 100, "harvest"), QuestStep("craft", 100, "craft")))
        val early = DailyQuestProgress(q.copy(id = "chain", plan = plan, target = 2), 0, stepValues = listOf(99, 0))
        QuestGuidance.progressPermille(early) shouldBe 495
        val board = DailyQuestBoard(java.time.LocalDate.parse("2026-09-08"), listOf(early, near))
        QuestGuidance.suggestion(board) shouldBe q.id
        QuestGuidance.suggestion(board.copy(unavailableQuestIds = setOf(q.id))) shouldBe null
        QuestGuidance.suggestion(board.copy(quests = listOf(near.copy(value = 100)))) shouldBe null
    }

})
