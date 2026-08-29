package ru.ruscrafting.ranks.progress

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class MovementAccumulatorTest : StringSpec({
    "same-world finite movement accumulates fractional blocks" {
        val player = UUID.randomUUID()
        val movement = MovementAccumulator(maximumStepBlocks = 16.0)

        movement.observe(player, point("world", 0.0), point("world", 0.6)) shouldBe 0
        movement.observe(player, point("world", 0.6), point("world", 1.2)) shouldBe 1
        movement.observe(player, point("world", 1.2), point("world", 2.4)) shouldBe 1
    }

    "teleport-sized jumps and world changes are ignored" {
        val player = UUID.randomUUID()
        val movement = MovementAccumulator(maximumStepBlocks = 16.0)

        movement.observe(player, point("world", 0.0), point("world", 100.0)) shouldBe 0
        movement.observe(player, point("world", 100.0), point("nether", 101.0)) shouldBe 0
        movement.observe(player, point("nether", 101.0), point("nether", 102.0)) shouldBe 1
    }

    "non-finite coordinates are ignored and player state can be cleared" {
        val player = UUID.randomUUID()
        val movement = MovementAccumulator(maximumStepBlocks = 16.0)

        movement.observe(player, point("world", 0.0), point("world", Double.NaN)) shouldBe 0
        movement.observe(player, point("world", 0.0), point("world", 0.9)) shouldBe 0
        movement.clear(player)
        movement.observe(player, point("world", 0.9), point("world", 1.1)) shouldBe 0
    }
})

private fun point(world: String, x: Double) = MovementPoint(world, x, 64.0, 0.0)
