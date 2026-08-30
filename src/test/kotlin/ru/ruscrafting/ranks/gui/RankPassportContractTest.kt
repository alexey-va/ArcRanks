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
    "rank overview stays compact and paths have their own symmetrical page" {
        MockBukkitTestRuntime.open().use {
            RankPassportMenu.INVENTORY_SIZE shouldBe 45
            RankPassportMenu.PATH_INVENTORY_SIZE shouldBe 45
            RankPassportMenu.RANK_SLOTS.shouldContainExactly((9..17).toList())
            RankPassportMenu.CONTRACTS_SLOT shouldBe 21
            RankPassportMenu.PERKS_SLOT shouldBe 23
            RankPassportMenu.WEEKLY_KIT_SLOT shouldBe 30
            RankPassportMenu.PROMOTION_SLOT shouldBe 31
            RankPassportMenu.BENEFITS_SLOT shouldBe 32
            RankPassportMenu.PATH_SLOTS.shouldContainExactly(19, 20, 21, 23, 24, 25)
            RankPassportMenu.PATH_GUIDE_SLOT shouldBe 22
            RankPassportMenu.PATH_BACK_SLOT shouldBe 40
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
