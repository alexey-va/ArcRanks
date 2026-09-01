package ru.ruscrafting.ranks.gui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class RankMenuContractTest : StringSpec({
    "contract board owns stable offers, stamps, claim, reroll, and navigation slots" {
        ContractMenu.INVENTORY_SIZE shouldBe 54
        ContractMenu.OFFER_SLOTS.shouldContainExactly(20, 22, 24)
        ContractMenu.STAMP_SLOTS.shouldContainExactly(10, 13, 16)
        ContractMenu.OFFER_SLOTS.shouldBeHorizontallySymmetric()
        ContractMenu.STAMP_SLOTS.shouldBeHorizontallySymmetric()
        ContractMenu.STAMP_LAYOUTS.values.forEach { it.shouldBeHorizontallySymmetric() }
        setOf(ContractMenu.CLAIM_SLOT, ContractMenu.REROLL_SLOT, ContractMenu.BACK_SLOT, ContractMenu.REFRESH_SLOT).size shouldBe 4
        listOf(ContractMenu.BACK_SLOT, ContractMenu.REFRESH_SLOT).shouldBeHorizontallySymmetric()
    }

    "perk board exposes two slot cards and six path groups with three choices each" {
        PerkMenu.SLOT_CARDS.shouldContainExactly(21, 23)
        PerkMenu.PATH_GROUPS.size shouldBe 6
        PerkMenu.PATH_GROUPS.values.flatMap { listOf(it.header) + it.perks }.let { slots ->
            slots.size shouldBe 24
            slots.distinct().size shouldBe 24
            slots.shouldBeHorizontallySymmetric()
        }
        PerkMenu.SLOT_CARDS.shouldBeHorizontallySymmetric()
    }

    "analytics board reserves three configurable cached-window controls" {
        AnalyticsMenu.WINDOW_SLOTS.shouldContainExactly(10, 13, 16)
        AnalyticsMenu.WINDOW_SLOTS.shouldBeHorizontallySymmetric()
        listOf(AnalyticsMenu.BACK_SLOT, AnalyticsMenu.REFRESH_SLOT).shouldBeHorizontallySymmetric()
    }
})

private fun List<Int>.shouldBeHorizontallySymmetric() {
    groupBy { it / 9 }.values.forEach { rowSlots ->
        val columns = rowSlots.mapTo(sortedSetOf()) { it % 9 }
        columns shouldBe columns.mapTo(sortedSetOf()) { 8 - it }
    }
}
