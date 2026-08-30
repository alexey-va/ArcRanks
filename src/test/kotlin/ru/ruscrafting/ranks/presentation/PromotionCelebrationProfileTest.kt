package ru.ruscrafting.ranks.presentation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PromotionCelebrationProfileTest : StringSpec({
    "promotion presentation stays bounded and announces only prestigious ranks" {
        PromotionCelebrationProfile.forOrder(1) shouldBe PromotionCelebrationProfile(12, 0, false)
        PromotionCelebrationProfile.forOrder(3) shouldBe PromotionCelebrationProfile(12, 0, false)
        PromotionCelebrationProfile.forOrder(4) shouldBe PromotionCelebrationProfile(24, 1, false)
        PromotionCelebrationProfile.forOrder(6) shouldBe PromotionCelebrationProfile(24, 1, false)
        PromotionCelebrationProfile.forOrder(7) shouldBe PromotionCelebrationProfile(36, 2, true)
        PromotionCelebrationProfile.forOrder(9) shouldBe PromotionCelebrationProfile(36, 2, true)
    }

    "rank order outside the catalog is rejected" {
        runCatching { PromotionCelebrationProfile.forOrder(0) }.isFailure shouldBe true
        runCatching { PromotionCelebrationProfile.forOrder(10) }.isFailure shouldBe true
    }
})
