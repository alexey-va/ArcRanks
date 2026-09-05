package ru.ruscrafting.ranks.progress

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

class BuildingProgressGateTest : StringSpec({
    "replacing a frame or undoing a tool operation cannot earn twice at the same position" {
        val gate = BuildingProgressGate()
        val world = UUID.randomUUID()
        val now = Instant.parse("2026-09-06T00:00:00Z")
        gate.credit(world, 1, 64, 1, now, 21600) shouldBe true
        repeat(18) { gate.credit(world, 1, 64, 1, now.plusSeconds(it + 1L), 21600) shouldBe false }
        gate.credit(world, 2, 64, 1, now.plusSeconds(20), 21600) shouldBe true
        gate.credit(UUID.randomUUID(), 1, 64, 1, now.plusSeconds(21), 21600) shouldBe true
        gate.credit(world, 1, 64, 1, now.plusSeconds(21600), 21600) shouldBe true
    }

    "a failed external award releases only its own reservation" {
        val gate = BuildingProgressGate()
        val world = UUID.randomUUID()
        val now = Instant.parse("2026-09-06T00:00:00Z")
        gate.credit(world, 1, 64, 1, now, 21600) shouldBe true
        gate.release(world, 1, 64, 1, now.minusSeconds(1))
        gate.credit(world, 1, 64, 1, now, 21600) shouldBe false
        gate.release(world, 1, 64, 1, now)
        gate.credit(world, 1, 64, 1, now, 21600) shouldBe true
    }

    "the placement history stays bounded without blocking new construction" {
        val gate = BuildingProgressGate(2)
        val world = UUID.randomUUID()
        val now = Instant.parse("2026-09-06T00:00:00Z")
        repeat(3) { gate.credit(world, it, 64, 1, now, 21600) shouldBe true }
        gate.credit(world, 2, 64, 1, now, 21600) shouldBe false
        gate.credit(world, 0, 64, 1, now, 21600) shouldBe true
    }
})
