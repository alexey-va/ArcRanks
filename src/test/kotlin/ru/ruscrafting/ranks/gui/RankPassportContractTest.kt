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
            val layouts = ArcRanksMenuLayouts.loadConfiguration(Files.createTempDirectory("arcranks-passport-layout"))
            val passport = layouts.require(ArcRanksMenuLayouts.PASSPORT)
            val paths = layouts.require(ArcRanksMenuLayouts.PATHS)
            passport.rows shouldBe 5
            paths.rows shouldBe 5
            passport.region("ranks").map { slot -> slot.index }.shouldContainExactly((9..17).toList())
            passport.slot("contracts").index shouldBe 30
            passport.slot("paths").index shouldBe 31
            passport.slot("perks").index shouldBe 32
            passport.slot("weekly-kit").index shouldBe 39
            passport.slot("promotion").index shouldBe 40
            paths.region("paths").map { slot -> slot.index }.shouldContainExactly(19, 20, 21, 23, 24, 25)
            paths.slot("guide").index shouldBe 22
            paths.slot("back").index shouldBe 36
            RankPassportMenu.PATH_ITEMS.keys shouldBe SpecializationPath.entries.toSet()
            RankPassportMenu.PATH_ITEMS.values.map { it.material }.toSet().size shouldBe SpecializationPath.entries.size
        }
    }

    "portable GUI fallback uses materials known to the target Paper API" {
        MockBukkitTestRuntime.open().use {
            val settings = ArcRanksSettings.load(Files.createTempDirectory("arcranks-gui")) { "secret" }
            listOf(
                settings.gui.background,
                settings.gui.back,
                settings.gui.rankLocked,
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
