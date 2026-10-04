package ru.ruscrafting.ranks.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe

class SpecializationTest : StringSpec({
    val thresholds = MasteryThresholds(100, 500, 1_000)

    "advanced mastery requires both permanent progress and active time without removing old mastery" {
        val extended = MasteryThresholds(100, 500, 1_000, listOf(2_000, 4_000, 8_000), listOf(0, 0, 0, 7_200, 14_400, 21_600))
        fun profile(minutes: Long, crops: Long = 8_000) = PlayerProgressProfile(
            ProgressSnapshot(mapOf(ProgressMetric.CROPS_HARVESTED to crops, ProgressMetric.ACTIVE_MINUTES to minutes)),
            SpecializationPath.FARMING,
        )
        MasteryEvaluator.level(profile(0), SpecializationPath.FARMING, extended) shouldBe MasteryLevel.III
        MasteryEvaluator.level(profile(7_199), SpecializationPath.FARMING, extended) shouldBe MasteryLevel.III
        MasteryEvaluator.level(profile(7_200), SpecializationPath.FARMING, extended) shouldBe MasteryLevel.IV
        MasteryEvaluator.level(profile(14_400), SpecializationPath.FARMING, extended) shouldBe MasteryLevel.V
        MasteryEvaluator.level(profile(21_600, 7_999), SpecializationPath.FARMING, extended) shouldBe MasteryLevel.V
        MasteryEvaluator.level(profile(21_600), SpecializationPath.FARMING, extended) shouldBe MasteryLevel.VI
    }

    "wealth alone preserves old trade mastery but cannot unlock advanced trade mastery" {
        val extended = MasteryThresholds(100, 500, 1_000, listOf(2_000, 4_000, 8_000), listOf(0, 0, 0, 7_200, 14_400, 21_600))
        fun profile(turnover: Long) = PlayerProgressProfile(
            ProgressSnapshot(mapOf(ProgressMetric.WEALTH_PEAK to Long.MAX_VALUE,
                ProgressMetric.TRADE_ACTIONS to turnover, ProgressMetric.ACTIVE_MINUTES to 21_600)),
            SpecializationPath.TRADE,
        )
        MasteryEvaluator.level(profile(0), SpecializationPath.TRADE, extended) shouldBe MasteryLevel.III
        MasteryEvaluator.level(profile(2_000), SpecializationPath.TRADE, extended) shouldBe MasteryLevel.IV
        MasteryEvaluator.level(profile(8_000), SpecializationPath.TRADE, extended) shouldBe MasteryLevel.VI
    }

    "active time configuration cannot revoke old mastery or decrease between levels" {
        shouldThrow<IllegalArgumentException> { MasteryThresholds(1, 2, 3, activeMinutes = listOf(0, 0, 1)) }
        shouldThrow<IllegalArgumentException> { MasteryThresholds(1, 2, 3, listOf(4, 5, 6), listOf(0, 0, 0, 2, 1, 3)) }
    }

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

    "trade mastery uses the stronger route instead of summing wealth and turnover" {
        val profile = PlayerProgressProfile(
            progress = ProgressSnapshot(
                mapOf(ProgressMetric.WEALTH_PEAK to 400, ProgressMetric.TRADE_ACTIONS to 600),
            ),
            selectedFocus = SpecializationPath.TRADE,
        )

        SpecializationPath.TRADE.progressValue(profile.progress) shouldBe 600
        MasteryEvaluator.level(profile, SpecializationPath.TRADE, thresholds) shouldBe MasteryLevel.II
    }
})
