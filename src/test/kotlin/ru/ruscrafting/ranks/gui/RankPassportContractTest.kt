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
    "rank passport keeps the approved compact visual hierarchy without holes" {
        MockBukkitTestRuntime.open().use {
            RankPassportMenu.INVENTORY_SIZE shouldBe 36
            RankPassportMenu.RANK_SLOTS.shouldContainExactly((9..17).toList())
            RankPassportMenu.PATH_SLOTS.shouldContainExactly(19, 20, 21, 23, 24, 25)
            RankPassportMenu.CONTRACTS_SLOT shouldBe 18
            RankPassportMenu.RECOMMENDATION_SLOT shouldBe 22
            RankPassportMenu.PERKS_SLOT shouldBe 26
            RankPassportMenu.BENEFITS_SLOT shouldBe 29
            RankPassportMenu.ANALYTICS_SLOT shouldBe 30
            RankPassportMenu.PROMOTION_SLOT shouldBe 31
            RankPassportMenu.REFRESH_SLOT shouldBe 32
            RankPassportMenu.CLOSE_SLOT shouldBe 33
            listOf(
                RankPassportMenu.CONTRACTS_SLOT,
                *RankPassportMenu.PATH_SLOTS.toTypedArray(),
                RankPassportMenu.RECOMMENDATION_SLOT,
                RankPassportMenu.PERKS_SLOT,
            ).sorted().shouldContainExactly((18..26).toList())
            listOf(
                RankPassportMenu.BENEFITS_SLOT,
                RankPassportMenu.ANALYTICS_SLOT,
                RankPassportMenu.PROMOTION_SLOT,
                RankPassportMenu.REFRESH_SLOT,
                RankPassportMenu.CLOSE_SLOT,
            ).shouldContainExactly((29..33).toList())
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
