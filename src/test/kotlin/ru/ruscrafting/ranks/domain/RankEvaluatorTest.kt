package ru.ruscrafting.ranks.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class RankEvaluatorTest : StringSpec({
    val catalog = RankCatalog(
        listOf(
            rank("settler", 1, activeMinutes = 0, choices = 0, goal = 0),
            rank("peasant", 2, activeMinutes = 120, choices = 2, goal = 100),
            rank("citizen", 3, activeMinutes = 480, choices = 3, goal = 500),
        ),
    )
    val evaluator = RankEvaluator(catalog)

    "mandatory active time blocks promotion even when enough paths are complete" {
        val result = evaluator.evaluate(
            RankId("settler"),
            ProgressSnapshot(
                mapOf(
                    ProgressMetric.ACTIVE_MINUTES to 119,
                    ProgressMetric.CROPS_HARVESTED to 100,
                    ProgressMetric.PRODUCTION_ACTIONS to 100,
                ),
            ),
            PathAvailability.allAvailable(),
        )

        result.eligibility shouldBe RankEligibility.CORE_INCOMPLETE
        result.completedChoices shouldBe 2
        result.recommendation shouldBe NextStep.ActiveMinutes(1)
    }

    "exactly the configured number of paths makes the next rank ready" {
        val result = evaluator.evaluate(
            RankId("settler"),
            ProgressSnapshot(
                mapOf(
                    ProgressMetric.ACTIVE_MINUTES to 120,
                    ProgressMetric.CROPS_HARVESTED to 100,
                    ProgressMetric.BLOCKS_PLACED to 100,
                ),
            ),
            PathAvailability.allAvailable(),
        )

        result.eligibility shouldBe RankEligibility.READY
        result.completedChoices shouldBe 2
        result.requiredChoices shouldBe 2
        result.recommendation shouldBe null
    }

    "unavailable path is visible but excluded from completion" {
        val result = evaluator.evaluate(
            RankId("settler"),
            ProgressSnapshot(
                mapOf(
                    ProgressMetric.ACTIVE_MINUTES to 120,
                    ProgressMetric.WEALTH_PEAK to 10_000,
                    ProgressMetric.CROPS_HARVESTED to 100,
                ),
            ),
            PathAvailability(setOf(SpecializationPath.TRADE)),
        )

        result.eligibility shouldBe RankEligibility.CHOICES_INCOMPLETE
        result.completedChoices shouldBe 1
        result.goals.first { it.path == SpecializationPath.TRADE }.state shouldBe GoalState.UNAVAILABLE
    }

    "too few available paths reports a runtime configuration defect" {
        val unavailable = SpecializationPath.entries.drop(1).toSet()
        val result = evaluator.evaluate(
            RankId("settler"),
            ProgressSnapshot(mapOf(ProgressMetric.ACTIVE_MINUTES to 120)),
            PathAvailability(unavailable),
        )

        result.eligibility shouldBe RankEligibility.INSUFFICIENT_AVAILABLE_PATHS
        result.availableChoices shouldBe 1
        result.recommendation shouldBe null
    }

    "recommendation chooses the closest available path and breaks ties by catalog order" {
        val result = evaluator.evaluate(
            RankId("settler"),
            ProgressSnapshot(
                mapOf(
                    ProgressMetric.ACTIVE_MINUTES to 120,
                    ProgressMetric.CROPS_HARVESTED to 50,
                    ProgressMetric.PRODUCTION_ACTIONS to 75,
                    ProgressMetric.WEALTH_PEAK to 75,
                ),
            ),
            PathAvailability.allAvailable(),
        )

        result.eligibility shouldBe RankEligibility.CHOICES_INCOMPLETE
        result.recommendation shouldBe NextStep.PathGoal(SpecializationPath.INDUSTRY, 25)
    }

    "top rank has no next requirements" {
        val result = evaluator.evaluate(
            RankId("citizen"),
            ProgressSnapshot.EMPTY,
            PathAvailability.allAvailable(),
        )

        result.eligibility shouldBe RankEligibility.TOP_RANK
        result.nextRank shouldBe null
        result.goals.shouldContainExactly(emptyList())
    }
})

private fun rank(
    id: String,
    order: Int,
    activeMinutes: Long,
    choices: Int,
    goal: Long,
): RankDefinition = RankDefinition(
    id = RankId(id),
    luckPermsGroup = if (id == "settler") "default" else id,
    order = order,
    displayNameKey = "rank.$id.name",
    activeMinutesRequired = activeMinutes,
    requiredChoices = choices,
    pathGoals = SpecializationPath.entries.associateWith { goal },
    benefitKeys = listOf("rank.$id.benefit"),
)
