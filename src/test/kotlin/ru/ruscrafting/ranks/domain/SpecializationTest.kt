package ru.ruscrafting.ranks.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe

class SpecializationTest : StringSpec({
    val thresholds = MasteryThresholds(100, 500, 1_000)

    "mastery changes exactly at configured boundaries" {
        MasteryEvaluator.level(99, thresholds) shouldBe MasteryLevel.NONE
        MasteryEvaluator.level(100, thresholds) shouldBe MasteryLevel.I
        MasteryEvaluator.level(499, thresholds) shouldBe MasteryLevel.I
        MasteryEvaluator.level(500, thresholds) shouldBe MasteryLevel.II
        MasteryEvaluator.level(999, thresholds) shouldBe MasteryLevel.II
        MasteryEvaluator.level(1_000, thresholds) shouldBe MasteryLevel.III
    }

    "mastery thresholds must be strictly increasing" {
        shouldThrow<IllegalArgumentException> { MasteryThresholds(100, 100, 1_000) }
        shouldThrow<IllegalArgumentException> { MasteryThresholds(-1, 100, 1_000) }
    }

    "focus selection changes presentation without changing progress" {
        val before = PlayerProgressProfile(
            progress = ProgressSnapshot(
                mapOf(
                    ProgressMetric.CROPS_HARVESTED to 600,
                    ProgressMetric.BLOCKS_PLACED to 200,
                ),
            ),
            selectedFocus = SpecializationPath.FARMING,
        )

        val after = before.selectFocus(SpecializationPath.BUILDING)

        after.selectedFocus shouldBe SpecializationPath.BUILDING
        after.progress.asMap() shouldBe before.progress.asMap()
        MasteryEvaluator.level(after, SpecializationPath.FARMING, thresholds) shouldBe MasteryLevel.II
    }
})
