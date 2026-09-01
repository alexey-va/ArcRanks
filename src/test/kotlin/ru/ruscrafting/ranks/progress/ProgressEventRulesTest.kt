package ru.ruscrafting.ranks.progress

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.GameMode
import org.bukkit.Material

class ProgressEventRulesTest : StringSpec({
    "only mature supported crops count as farming progress" {
        ProgressEventRules.isMatureCrop(Material.WHEAT, age = 7, maximumAge = 7) shouldBe true
        ProgressEventRules.isMatureCrop(Material.WHEAT, age = 6, maximumAge = 7) shouldBe false
        ProgressEventRules.isMatureCrop(Material.OAK_LOG, age = null, maximumAge = null) shouldBe false
    }

    "survival and adventure players progress while creative and spectator players do not" {
        ProgressEventRules.eligible(GameMode.SURVIVAL) shouldBe true
        ProgressEventRules.eligible(GameMode.ADVENTURE) shouldBe true
        ProgressEventRules.eligible(GameMode.CREATIVE) shouldBe false
        ProgressEventRules.eligible(GameMode.SPECTATOR) shouldBe false
    }

    "periodic active play excludes players beyond the idle grace window" {
        ProgressEventRules.activeSample(GameMode.SURVIVAL, idleSeconds = 299, maximumIdleSeconds = 300) shouldBe true
        ProgressEventRules.activeSample(GameMode.SURVIVAL, idleSeconds = 301, maximumIdleSeconds = 300) shouldBe false
        ProgressEventRules.activeSample(GameMode.CREATIVE, idleSeconds = 0, maximumIdleSeconds = 300) shouldBe false
    }

    "only real advancement families count and exploration excludes story recipes" {
        ProgressEventRules.isMeaningfulAdvancement("minecraft", "story/mine_stone") shouldBe true
        ProgressEventRules.isMeaningfulAdvancement("minecraft", "recipes/building_blocks/oak_planks") shouldBe false
        ProgressEventRules.isMeaningfulAdvancement("plugin", "adventure/custom") shouldBe false
        ProgressEventRules.isExplorationAdvancement("minecraft", "adventure/adventuring_time") shouldBe true
        ProgressEventRules.isExplorationAdvancement("minecraft", "nether/find_fortress") shouldBe true
        ProgressEventRules.isExplorationAdvancement("minecraft", "story/mine_stone") shouldBe false
    }
})
