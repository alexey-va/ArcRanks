package ru.ruscrafting.ranks.gui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class RankMenuContractTest : StringSpec({
    "contract board owns stable offers, stamps, claim, reroll, and navigation slots" {
        ContractMenu.INVENTORY_SIZE shouldBe 54
        ContractMenu.OFFER_SLOTS.shouldContainExactly(20, 22, 24)
        ContractMenu.STAMP_SLOTS.shouldContainExactly(10, 11, 12)
        setOf(ContractMenu.CLAIM_SLOT, ContractMenu.REROLL_SLOT, ContractMenu.BACK_SLOT, ContractMenu.REFRESH_SLOT, ContractMenu.CLOSE_SLOT).size shouldBe 5
    }

    "perk board exposes exactly two active slots and twelve cards" {
        PerkMenu.ACTIVE_SLOTS.shouldContainExactly(10, 16)
        PerkMenu.PERK_SLOTS.size shouldBe 12
        PerkMenu.PERK_SLOTS.distinct().size shouldBe 12
    }

    "analytics board supports only the approved cached windows" {
        AnalyticsMenu.WINDOWS.shouldContainExactly(7, 14, 30)
        AnalyticsMenu.WINDOW_SLOTS.shouldContainExactly(10, 11, 12)
    }
})
