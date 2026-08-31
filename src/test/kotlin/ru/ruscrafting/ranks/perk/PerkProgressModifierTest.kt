package ru.ruscrafting.ranks.perk

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.domain.MasteryLevel
import ru.ruscrafting.ranks.domain.MasteryThresholds
import ru.ruscrafting.ranks.domain.PlayerProgressProfile
import ru.ruscrafting.ranks.domain.ProgressSnapshot
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.progress.ProgressBuffer
import java.nio.file.Files
import java.util.UUID

class PerkProgressModifierTest : StringSpec({
    val catalog = PerkCatalogLoader(Config(Files.createTempDirectory("arcranks-perk-modifier"), "perks.yml")).load()
    val player = UUID.fromString("00000000-0000-0000-0000-000000000001")

    "matching progress perk adds a deterministic bonus without storage reads" {
        val writes = mutableListOf<Long>()
        val buffer = ProgressBuffer(8) { _, mutations ->
            writes += (mutations.single() as ru.ruscrafting.ranks.progress.ProgressMutation.Add).delta
            java.util.concurrent.CompletableFuture.completedFuture(Unit)
        }
        var lookups = 0
        val modifier = PerkProgressModifier(buffer, catalog, FractionalProgressBonus()) {
            lookups++
            listOf(PerkId("building_momentum"))
        }

        repeat(10) { modifier.recordCounter(player, ProgressMetric.BLOCKS_PLACED, 1) shouldBe true }
        buffer.flush(player).join()

        writes shouldBe listOf(11L)
        lookups shouldBe 10
    }

    "unrelated perk and high-water metric receive no synthetic bonus" {
        val writes = mutableMapOf<ProgressMetric, Long>()
        val buffer = ProgressBuffer(8) { _, mutations ->
            mutations.forEach { writes[it.metric] = (it as ru.ruscrafting.ranks.progress.ProgressMutation.Add).delta }
            java.util.concurrent.CompletableFuture.completedFuture(Unit)
        }
        val modifier = PerkProgressModifier(buffer, catalog, FractionalProgressBonus()) {
            listOf(PerkId("farming_momentum"), PerkId("trade_reward"))
        }

        modifier.recordCounter(player, ProgressMetric.BLOCKS_PLACED, 10)
        modifier.recordCounter(player, ProgressMetric.WEALTH_PEAK, 10)
        buffer.flush(player).join()

        writes[ProgressMetric.BLOCKS_PLACED] shouldBe 10
        writes[ProgressMetric.WEALTH_PEAK] shouldBe 10
    }

    "each counter record captures the current catalog exactly once" {
        val writes = mutableListOf<Long>()
        val buffer = ProgressBuffer(8) { _, mutations ->
            writes += (mutations.single() as ru.ruscrafting.ranks.progress.ProgressMutation.Add).delta
            java.util.concurrent.CompletableFuture.completedFuture(Unit)
        }
        val strongerCatalog = PerkCatalog(
            catalog.perks.map { perk ->
                if (perk.id == PerkId("building_momentum")) perk.copy(basisPoints = 5_000) else perk
            },
        )
        var currentCatalog = catalog
        var catalogReads = 0
        val modifier = PerkProgressModifier(
            buffer = buffer,
            catalogProvider = {
                catalogReads++
                currentCatalog
            },
            fractionalBonus = FractionalProgressBonus(),
            selectedPerks = { listOf(PerkId("building_momentum")) },
        )

        modifier.recordCounter(player, ProgressMetric.BLOCKS_PLACED, 10) shouldBe true
        currentCatalog = strongerCatalog
        modifier.recordCounter(player, ProgressMetric.BLOCKS_PLACED, 10) shouldBe true
        buffer.flush(player).join()

        writes shouldBe listOf(26L)
        catalogReads shouldBe 2
    }

    "reload mastery requirements immediately filter a previously selected progress perk" {
        val perkId = PerkId("building_momentum")
        val profile = PlayerProgressProfile(
            ProgressSnapshot(mapOf(ProgressMetric.BLOCKS_PLACED to 1L)),
            SpecializationPath.FARMING,
        )
        val thresholds = SpecializationPath.entries.associateWith { MasteryThresholds(1, 2, 3) }
        catalog.eligibleForProgress(setOf(perkId), profile, thresholds) shouldBe setOf(perkId)

        val reloaded = PerkCatalog(
            catalog.perks.map { perk ->
                if (perk.id == perkId) perk.copy(requiredMastery = MasteryLevel.III) else perk
            },
        )

        reloaded.eligibleForProgress(setOf(perkId), profile, thresholds) shouldBe emptySet()
    }
})
