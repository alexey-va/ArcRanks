package ru.ruscrafting.ranks.perk

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.ranks.domain.ProgressMetric
import java.util.UUID

class FractionalProgressBonusTest : StringSpec({
    "ten one-unit events at ten percent produce one deterministic bonus" {
        val bonus = FractionalProgressBonus()
        val player = UUID.fromString("00000000-0000-0000-0000-000000000001")

        (1..10).sumOf { bonus.apply(player, ProgressMetric.BLOCKS_PLACED, 1, 1_000) } shouldBe 1
        bonus.apply(player, ProgressMetric.BLOCKS_PLACED, 10, 1_000) shouldBe 1
    }

    "remainders are isolated by player and metric and can be cleared" {
        val bonus = FractionalProgressBonus()
        val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val second = UUID.fromString("00000000-0000-0000-0000-000000000002")

        repeat(9) { bonus.apply(first, ProgressMetric.BLOCKS_PLACED, 1, 1_000) }
        bonus.apply(second, ProgressMetric.BLOCKS_PLACED, 1, 1_000) shouldBe 0
        bonus.apply(first, ProgressMetric.CROPS_HARVESTED, 1, 1_000) shouldBe 0
        bonus.clear(first)
        bonus.apply(first, ProgressMetric.BLOCKS_PLACED, 1, 1_000) shouldBe 0
    }
})
