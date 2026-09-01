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
