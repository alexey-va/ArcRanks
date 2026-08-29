package ru.ruscrafting.ranks.rankstate

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.ruscrafting.ranks.domain.RankCatalog
import ru.ruscrafting.ranks.domain.RankDefinition
import ru.ruscrafting.ranks.domain.RankId
import ru.ruscrafting.ranks.domain.SpecializationPath

class RankStateGatewayTest : StringSpec({
    val catalog = testCatalog()

    "zero direct progression parents resolves to the implicit default rank" {
        RankStateClassifier.resolveGroups(listOf("vip"), catalog) shouldBe
            RankState.Exact(RankId("settler"))
    }

    "one direct progression parent resolves exactly" {
        RankStateClassifier.resolveGroups(listOf("pesant", "vip"), catalog) shouldBe
            RankState.Exact(RankId("peasant"))
    }

    "multiple direct progression parents are an ordered conflict" {
        RankStateClassifier.resolveGroups(listOf("pesant", "default", "vip"), catalog) shouldBe
            RankState.Conflict(listOf("default", "pesant"))
    }

    "mutation plan removes only progression parents and preserves role nodes" {
        val plan = RankMutationPlan.create(
            listOf("pesant", "vip"), catalog, RankId("peasant"), RankId("citizen"),
        )

        plan.result shouldBe RankMutationPlanResult.READY
        plan.removals.shouldContainExactly("pesant")
        plan.addition shouldBe "burgher"
        plan.preserved.shouldContainExactly("vip")
    }

    "mutation plan promotes an implicit default rank while preserving role nodes" {
        val plan = RankMutationPlan.create(
            listOf("vip"), catalog, RankId("settler"), RankId("peasant"),
        )

        plan.result shouldBe RankMutationPlanResult.READY
        plan.removals shouldBe emptyList()
        plan.addition shouldBe "pesant"
        plan.preserved.shouldContainExactly("vip")
    }

    "stale expected rank produces no mutation" {
        val plan = RankMutationPlan.create(
            listOf("default"), catalog, RankId("peasant"), RankId("citizen"),
        )

        plan.result shouldBe RankMutationPlanResult.STALE_EXPECTED_RANK
        plan.removals shouldBe emptyList()
        plan.addition shouldBe null
    }
})

private fun testCatalog(): RankCatalog = RankCatalog(
    listOf(
        stateRank("settler", "default", 1),
        stateRank("peasant", "pesant", 2),
        stateRank("citizen", "burgher", 3),
    ),
)

private fun stateRank(id: String, group: String, order: Int): RankDefinition = RankDefinition(
    id = RankId(id),
    luckPermsGroup = group,
    order = order,
    displayNameKey = "ranks.$id.name",
    activeMinutesRequired = order.toLong(),
    requiredChoices = 0,
    pathGoals = SpecializationPath.entries.associateWith { 0L },
    benefitKeys = listOf("ranks.$id.benefit"),
)
