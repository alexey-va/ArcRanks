package ru.ruscrafting.ranks.rankstate

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.luckperms.api.context.ImmutableContextSet
import net.luckperms.api.node.types.InheritanceNode
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

    "cumulative legacy progression parents resolve to the highest mapped rank" {
        RankStateClassifier.resolveGroups(listOf("burgher", "default", "pesant", "vip"), catalog) shouldBe
            RankState.Exact(RankId("citizen"))
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

    "mutation plan canonicalizes cumulative legacy progression parents" {
        val plan = RankMutationPlan.create(
            listOf("default", "pesant", "monk", "vip"), catalog, RankId("peasant"), RankId("citizen"),
        )

        plan.result shouldBe RankMutationPlanResult.READY
        plan.removals.shouldContainExactly("default", "pesant")
        plan.addition shouldBe "burgher"
        plan.preserved.shouldContainExactly("monk", "vip")
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

    "only positive permanent global inheritance nodes are progression authority" {
        val permanent = inheritanceNode()
        val temporary = inheritanceNode(expiring = true)
        val contextual = inheritanceNode(hasContexts = true)
        val negated = inheritanceNode(value = false)

        permanent.isPermanentGlobal() shouldBe true
        temporary.isPermanentGlobal() shouldBe false
        contextual.isPermanentGlobal() shouldBe false
        negated.isPermanentGlobal() shouldBe false
    }
})

private fun inheritanceNode(
    value: Boolean = true,
    expiring: Boolean = false,
    hasContexts: Boolean = false,
): InheritanceNode {
    val contexts = mockk<ImmutableContextSet> {
        every { isEmpty } returns !hasContexts
    }
    return mockk {
        every { getValue() } returns value
        every { hasExpiry() } returns expiring
        every { getContexts() } returns contexts
    }
}

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
