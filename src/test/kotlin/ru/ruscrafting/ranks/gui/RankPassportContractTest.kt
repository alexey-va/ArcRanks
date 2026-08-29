package ru.ruscrafting.ranks.gui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.domain.SpecializationPath
import java.nio.file.Files

class RankPassportContractTest : StringSpec({
    "rank passport keeps the approved 54-slot visual hierarchy" {
        MockBukkitTestRuntime.open().use {
            RankPassportMenu.INVENTORY_SIZE shouldBe 54
            RankPassportMenu.RANK_SLOTS.shouldContainExactly((9..17).toList())
            RankPassportMenu.PATH_SLOTS.shouldContainExactly(28, 29, 30, 32, 33, 34)
            RankPassportMenu.CONTRACTS_SLOT shouldBe 37
            RankPassportMenu.PERKS_SLOT shouldBe 43
            RankPassportMenu.ANALYTICS_SLOT shouldBe 40
            RankPassportMenu.PROMOTION_SLOT shouldBe 49
            RankPassportMenu.PATH_SLOTS.shouldBeHorizontallySymmetric()
            listOf(
                RankPassportMenu.CONTRACTS_SLOT,
                RankPassportMenu.ANALYTICS_SLOT,
                RankPassportMenu.PERKS_SLOT,
            ).shouldBeHorizontallySymmetric()
            RankPassportMenu.PATH_ITEMS.keys shouldBe SpecializationPath.entries.toSet()
            RankPassportMenu.PATH_ITEMS.values.map { it.material }.toSet().size shouldBe SpecializationPath.entries.size
        }
    }

    "portable GUI fallback uses materials known to the target Paper API" {
        MockBukkitTestRuntime.open().use {
            val settings = ArcRanksSettings.load(Files.createTempDirectory("arcranks-gui")) { "secret" }
            listOf(
                settings.gui.background,
                settings.gui.rankCompleted,
                settings.gui.rankCurrent,
                settings.gui.rankNext,
                settings.gui.path,
                settings.gui.promotion,
                settings.gui.contracts,
                settings.gui.perks,
                settings.gui.analytics,
                *RankPassportMenu.PATH_ITEMS.values.toTypedArray(),
            ).forEach { spec ->
                Material.matchMaterial(spec.material) shouldBe Material.valueOf(spec.material)
            }
        }
    }
})

private fun List<Int>.shouldBeHorizontallySymmetric() {
    groupBy { it / 9 }.values.forEach { rowSlots ->
        val columns = rowSlots.mapTo(sortedSetOf()) { it % 9 }
        columns shouldBe columns.mapTo(sortedSetOf()) { 8 - it }
    }
}
