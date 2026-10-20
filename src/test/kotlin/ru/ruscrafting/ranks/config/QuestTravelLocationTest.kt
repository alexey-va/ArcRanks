package ru.ruscrafting.ranks.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrowAny

class QuestTravelLocationTest : StringSpec({
    val destination = DungeonQuestDestination("spawn", "rc_origin_spawn", 68.5, 70.0, 63.5)
    val locations = mapOf(
        "forge" to QuestTravelLocation(setOf("contract.items", "contract.items:forge_iron_ingot", "sidejob.complete:forge"), destination),
        "bank" to QuestTravelLocation(setOf("contract.items"), destination),
    )

    "general contracts offer choices but a concrete contract only offers its customer" {
        locations.locationsFor("contract.items") shouldBe listOf("forge", "bank")
        locations.locationsFor("contract.items:forge_iron_ingot") shouldBe listOf("forge")
        locations.locationsFor("sidejob.complete:forge") shouldBe listOf("forge")
        locations.locationsFor("sidejob.complete:forge_other") shouldBe emptyList()
        locations.locationsFor("travel") shouldBe emptyList()
    }

    "unsafe empty selectors cannot turn destinations into arbitrary commands" {
        listOf(emptySet(), setOf("contract.items say unsafe"), setOf("../forge")).forEach {
            shouldThrowAny { QuestTravelLocation(it, destination) }
        }
    }
})
