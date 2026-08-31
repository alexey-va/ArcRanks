package ru.ruscrafting.ranks.progress

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class DynamicTickCadenceTest : StringSpec({
    "cadence becomes due exactly at its configured period" {
        val cadence = DynamicTickCadence(20)

        repeat(59) { cadence.advance(1_200) shouldBe 0L }
        cadence.carriedTicks() shouldBe 1_180L
        cadence.advance(1_200) shouldBe 1_200L
        cadence.carriedTicks() shouldBe 0L
    }

    "shortening a period preserves and releases the elapsed carry" {
        val cadence = DynamicTickCadence(1_200)

        cadence.advance(3_600) shouldBe 0L
        cadence.advance(3_600) shouldBe 0L
        cadence.carriedTicks() shouldBe 2_400L

        cadence.advance(1_200) shouldBe 3_600L
        cadence.carriedTicks() shouldBe 0L
    }

    "lengthening a period preserves elapsed carry without an early run" {
        val cadence = DynamicTickCadence(1_200)

        cadence.advance(2_400) shouldBe 0L
        cadence.advance(3_600) shouldBe 0L
        cadence.carriedTicks() shouldBe 2_400L
        cadence.advance(3_600) shouldBe 3_600L
    }
})
