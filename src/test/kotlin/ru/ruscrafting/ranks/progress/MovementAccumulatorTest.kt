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

    "dynamic tuning is read once per observation and can exclude vertical distance" {
        val player = UUID.randomUUID()
        var reads = 0
        var tuning = MovementTuning(maximumStepBlocks = 2.0, includeVertical = false)
        val movement = MovementAccumulator {
            reads++
            tuning
        }

        movement.observe(player, point("world", 0.0, 64.0), point("world", 0.6, 164.0)) shouldBe 0
        reads shouldBe 1
        movement.observe(player, point("world", 0.6, 164.0), point("world", 1.2, 264.0)) shouldBe 1
        reads shouldBe 2

        tuning = MovementTuning(maximumStepBlocks = 0.5, includeVertical = true)
        movement.observe(player, point("world", 1.2, 264.0), point("world", 1.8, 264.0)) shouldBe 0
        reads shouldBe 3
    }

    "clearAll drops every player's fractional residual" {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val movement = MovementAccumulator(maximumStepBlocks = 16.0)

        movement.observe(first, point("world", 0.0), point("world", 0.75)) shouldBe 0
        movement.observe(second, point("world", 0.0), point("world", 0.75)) shouldBe 0
        movement.clearAll()

        movement.observe(first, point("world", 0.75), point("world", 1.25)) shouldBe 0
        movement.observe(second, point("world", 0.75), point("world", 1.25)) shouldBe 0
    }
})

private fun point(world: String, x: Double, y: Double = 64.0) = MovementPoint(world, x, y, 0.0)
