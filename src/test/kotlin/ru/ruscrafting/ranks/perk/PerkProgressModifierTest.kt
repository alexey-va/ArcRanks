package ru.ruscrafting.ranks.perk

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import ru.ruscrafting.ranks.domain.ProgressMetric
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
})
