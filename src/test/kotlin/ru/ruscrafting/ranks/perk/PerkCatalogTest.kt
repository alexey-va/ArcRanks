package ru.ruscrafting.ranks.perk

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.ruscrafting.ranks.domain.MasteryLevel
import ru.ruscrafting.ranks.domain.SpecializationPath
import java.nio.file.Files

class PerkCatalogTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "bundled catalog has three safe enhancements for every path" {
        val catalog = PerkCatalogLoader(Config(Files.createTempDirectory("arcranks-perks"), "perks.yml")).load()

        catalog.perks.size shouldBe 18
        SpecializationPath.entries.forEach { path -> catalog.forPath(path).size shouldBe 3 }
        catalog.perks.none { it.requiredMastery == MasteryLevel.NONE } shouldBe true
        catalog.perks.filter { it.effect == PerkEffectKind.PROGRESS_BONUS }
            .none { it.path == SpecializationPath.TRADE } shouldBe true
    }

    "bundled catalog covers every gameplay effect and preserves path progress" {
        val catalog = PerkCatalogLoader(Config(Files.createTempDirectory("arcranks-perks-gameplay"), "perks.yml")).load()
        val expected = mapOf(
            "farming_momentum" to (PerkEffectKind.HARVEST_BONUS to 500),
            "farming_contract" to (PerkEffectKind.HOE_PRESERVATION to 2000),
            "farming_mastery" to (PerkEffectKind.HARVEST_BONUS to 1000),
            "industry_momentum" to (PerkEffectKind.PICKAXE_PRESERVATION to 1000),
            "industry_contract" to (PerkEffectKind.SMELTING_XP to 1500),
            "industry_mastery" to (PerkEffectKind.PICKAXE_PRESERVATION to 2000),
            "trade_terms" to (PerkEffectKind.TRADE_XP to 1500),
            "trade_reward" to (PerkEffectKind.MOVEMENT_EXHAUSTION_REDUCTION to 1500),
            "trade_mastery" to (PerkEffectKind.TRADE_XP to 3000),
            "exploration_momentum" to (PerkEffectKind.MOVEMENT_EXHAUSTION_REDUCTION to 1000),
            "exploration_contract" to (PerkEffectKind.FALL_REDUCTION to 2000),
            "exploration_mastery" to (PerkEffectKind.MOVEMENT_EXHAUSTION_REDUCTION to 2000),
            "building_momentum" to (PerkEffectKind.BUILDING_TOOL_PRESERVATION to 1000),
            "building_contract" to (PerkEffectKind.FALL_REDUCTION to 3000),
            "building_mastery" to (PerkEffectKind.BUILDING_TOOL_PRESERVATION to 2000),
            "community_momentum" to (PerkEffectKind.NEARBY_XP to 500),
            "community_contract" to (PerkEffectKind.NEARBY_DEFENCE to 1000),
            "community_mastery" to (PerkEffectKind.NEARBY_XP to 1000),
        )
        expected.forEach { (id, effect) ->
            catalog.require(PerkId(id)).effect shouldBe effect.first
            catalog.require(PerkId(id)).basisPoints shouldBe effect.second
        }
        catalog.effect(listOf(PerkId("farming_momentum")), SpecializationPath.FARMING, PerkEffectKind.PROGRESS_BONUS) shouldBe 1000
        catalog.effect(listOf(PerkId("farming_mastery")), SpecializationPath.FARMING, PerkEffectKind.PROGRESS_BONUS) shouldBe 1500
    }

    "gameplay effects use strongest value, ignore unknown ids and enforce caps" {
        val catalog = PerkCatalogLoader(Config(Files.createTempDirectory("arcranks-perks-caps"), "perks.yml")).load()
        catalog.gameplayEffect(listOf(PerkId("missing"), PerkId("farming_momentum"), PerkId("farming_mastery")), PerkEffectKind.HARVEST_BONUS) shouldBe 1000
        catalog.gameplayEffect(listOf(PerkId("missing")), PerkEffectKind.HARVEST_BONUS) shouldBe 0

        shouldThrow<IllegalArgumentException> {
            PerkDefinition(PerkId("too_much"), SpecializationPath.FARMING, MasteryLevel.I,
                PerkEffectKind.HARVEST_BONUS, 1001, "perks.too_much.name", "perks.too_much.description")
        }
        shouldThrow<IllegalArgumentException> {
            PerkDefinition(PerkId("trade_progress"), SpecializationPath.TRADE, MasteryLevel.I,
                PerkEffectKind.TRADE_XP, 100, "perks.trade_progress.name", "perks.trade_progress.description", 1)
        }
    }

    "catalog rejects duplicate ids and incompatible progress effects" {
        val base = validDefinitions().toMutableList()
        base[1] = base[1].copy(id = base[0].id)
        shouldThrow<IllegalArgumentException> { PerkCatalog(base) }

        shouldThrow<IllegalArgumentException> {
            val invalid = validDefinitions().toMutableList()
            val tradeIndex = invalid.indexOfFirst { it.path == SpecializationPath.TRADE }
            invalid[tradeIndex] = invalid[tradeIndex].copy(effect = PerkEffectKind.PROGRESS_BONUS)
            PerkCatalog(invalid)
        }
    }
})

private fun validDefinitions(): List<PerkDefinition> = SpecializationPath.entries.flatMap { path ->
    listOf(
        PerkDefinition(
            PerkId("${path.name.lowercase()}_one"),
            path,
            MasteryLevel.I,
            if (path == SpecializationPath.TRADE) PerkEffectKind.CONTRACT_TARGET_REDUCTION else PerkEffectKind.PROGRESS_BONUS,
            500,
            "perks.${path.name.lowercase()}_one.name",
            "perks.${path.name.lowercase()}_one.description",
        ),
        PerkDefinition(
            PerkId("${path.name.lowercase()}_two"),
            path,
            MasteryLevel.II,
            if (path == SpecializationPath.TRADE) PerkEffectKind.CONTRACT_REWARD_BONUS else PerkEffectKind.CONTRACT_TARGET_REDUCTION,
            1_000,
            "perks.${path.name.lowercase()}_two.name",
            "perks.${path.name.lowercase()}_two.description",
        ),
        PerkDefinition(
            PerkId("${path.name.lowercase()}_three"),
            path,
            MasteryLevel.III,
            if (path == SpecializationPath.TRADE) PerkEffectKind.CONTRACT_REWARD_BONUS else PerkEffectKind.PROGRESS_BONUS,
            1_500,
            "perks.${path.name.lowercase()}_three.name",
            "perks.${path.name.lowercase()}_three.description",
        ),
    )
}
