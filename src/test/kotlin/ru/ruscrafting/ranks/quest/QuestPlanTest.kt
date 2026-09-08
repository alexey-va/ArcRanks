package ru.ruscrafting.ranks.quest

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class QuestPlanTest : StringSpec({
    fun step(objective: String, target: Long = 10, textId: String = objective.replace(Regex("[^a-z0-9_-]"), "_")) =
        QuestStep(objective, target, textId)

    "chain advances only the current step and does not carry excess" {
        val plan = QuestPlan(QuestMode.CHAIN, listOf(step("farm"), step("craft", 3)))
        val first = plan.advance(plan.initialValues, "craft", 10)
        first shouldBe listOf(0L, 0L)

        val second = plan.advance(plan.initialValues, "farm", 15)
        second shouldBe listOf(10L, 0L)
        plan.advance(second, "farm", 1) shouldBe second
        plan.advance(second, "craft", 2) shouldBe listOf(10L, 2L)
    }

    "any completes after one matching step" {
        val plan = QuestPlan(QuestMode.ANY, listOf(step("farm"), step("craft")))
        val values = plan.advance(plan.initialValues, "craft", 10)

        values shouldBe listOf(0L, 10L)
        plan.progress(values) shouldBe 1L
        plan.target shouldBe 1L
        plan.completed(values) shouldBe true
    }

    "distinct counts completed steps and ignores duplicate events" {
        val plan = QuestPlan(
            QuestMode.DISTINCT,
            listOf(step("farm", 1), step("craft", 1), step("travel", 1)),
            requiredDistinct = 2,
        )
        val farm = plan.advance(plan.initialValues, "farm", 1)
        plan.progress(farm) shouldBe 1L
        plan.advance(farm, "farm", 1) shouldBe farm

        val complete = plan.advance(farm, "craft", 1)
        plan.progress(complete) shouldBe 2L
        plan.completed(complete) shouldBe true
    }

    "objective events accept exact and colon-prefixed matches" {
        val plan = QuestPlan(QuestMode.ALL, listOf(step("dungeon.complete"), step("builder.blocks")))
        plan.advance(plan.initialValues, "dungeon.complete:the_mines", 4) shouldBe listOf(4L, 0L)
        plan.advance(plan.initialValues, "dungeon.completed", 4) shouldBe plan.initialValues
    }

    "overlapping objectives are rejected for every mode" {
        QuestMode.entries.forEach { mode ->
            runCatching { QuestPlan(mode, listOf(step("dungeon.complete"), step("dungeon.complete:the_mines"))) }
                .isFailure shouldBe true
        }
    }

    "codec round trips plans and values independently" {
        val plan = QuestPlan(
            QuestMode.DISTINCT,
            listOf(step("farm", 3, "farm_card"), step("craft", 5, "craft_card")),
            requiredDistinct = 1,
        )
        val encodedPlan = QuestPlanCodec.encode(plan)
        val encodedValues = QuestPlanCodec.encodeValues(listOf(3L, 2L))

        QuestPlanCodec.decode(encodedPlan) shouldBe plan
        QuestPlanCodec.decodeValues(encodedValues, plan) shouldBe listOf(3L, 2L)
        QuestPlanCodec.decodeValues(encodedValues, 2) shouldBe listOf(3L, 2L)
        encodedPlan shouldNotBe encodedValues
    }

    "scaling rounds every step up without changing completion mode" {
        val plan = QuestPlan(QuestMode.ALL, listOf(step("farm", 3), step("craft", 100)))
        val scaled = plan.scaled(150)

        scaled.steps.map(QuestStep::target) shouldBe listOf(5L, 150L)
        scaled.mode shouldBe QuestMode.ALL
        scaled.target shouldBe 2L
    }

    "invalid plans, state and codec payloads fail closed" {
        runCatching { QuestStep("unsafe objective!", 1, "text") }.isFailure shouldBe true
        runCatching { QuestStep("farm", 0, "text") }.isFailure shouldBe true
        runCatching { QuestStep("farm", 1, "unsafe.text") }.isFailure shouldBe true
        runCatching { QuestPlan(QuestMode.ALL, (1..9).map { step("step_$it") }) }.isFailure shouldBe true
        runCatching { QuestPlan(QuestMode.DISTINCT, listOf(step("farm")), requiredDistinct = 0) }.isFailure shouldBe true

        val plan = QuestPlan(QuestMode.ALL, listOf(step("farm", 3)))
        runCatching { plan.advance(plan.initialValues, "farm", 0) }.isFailure shouldBe true
        runCatching { plan.advance(listOf(4L), "farm", 1) }.isFailure shouldBe true
        val chain = QuestPlan(QuestMode.CHAIN, listOf(step("farm", 2), step("craft", 2)))
        runCatching { chain.completed(listOf(0L, 2L)) }.isFailure shouldBe true
        runCatching { QuestPlanCodec.decodeValues("QV1|0,2", chain) }.isFailure shouldBe true
        runCatching { QuestPlanCodec.decode("QP2|ALL|1|farm~1~farm") }.isFailure shouldBe true
        runCatching { QuestPlanCodec.decodeValues("QV1|01", 1) }.isFailure shouldBe true
        runCatching { QuestPlanCodec.decodeValues("QV1|4", plan) }.isFailure shouldBe true
    }
})
