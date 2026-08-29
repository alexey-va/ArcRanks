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

    "perk board exposes exactly two active slots and twelve cards" {
        PerkMenu.ACTIVE_SLOTS.shouldContainExactly(10, 16)
        PerkMenu.PERK_SLOTS.size shouldBe 12
        PerkMenu.PERK_SLOTS.distinct().size shouldBe 12
        PerkMenu.ACTIVE_SLOTS.shouldBeHorizontallySymmetric()
        PerkMenu.PERK_SLOTS.shouldBeHorizontallySymmetric()
        listOf(PerkMenu.BACK_SLOT, PerkMenu.REFRESH_SLOT).shouldBeHorizontallySymmetric()
    }

    "analytics board supports only the approved cached windows" {
        AnalyticsMenu.WINDOWS.shouldContainExactly(7, 14, 30)
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
