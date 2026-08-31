package ru.ruscrafting.ranks.gui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.config.FeatureSettings
import ru.ruscrafting.ranks.contract.ContractService
import ru.ruscrafting.ranks.kit.WeeklyKitCatalog
import ru.ruscrafting.ranks.kit.WeeklyKitService
import ru.ruscrafting.ranks.perk.PerkCatalog
import ru.ruscrafting.ranks.perk.PerkSelectionService
import ru.ruscrafting.ranks.service.RankPlayerService
import ru.ruscrafting.ranks.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.ranks.text.RankLocale
import java.nio.file.Files

class FeatureGateMenusMockBukkitTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "live feature gates reject contracts perks and weekly kits before storage or inventory work" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("ArcRanksFeatureGateTest")
                val player = paper.addPlayer("FeatureGateTester")
                val root = Files.createTempDirectory("arcranks-feature-gates")
                val baseline = ArcRanksSettings.loadFresh(root) { "secret" }
                val settings = baseline.copy(features = FeatureSettings(false, false, false))
                val locale = RankLocale.fresh(root, { settings.defaultLocale }, { settings.useClientLocale })
                val players = mockk<RankPlayerService>(relaxed = true)
                val contracts = mockk<ContractService>(relaxed = true)
                val perks = mockk<PerkSelectionService>(relaxed = true)
                val weeklyKits = mockk<WeeklyKitService>(relaxed = true)
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                try {
                    val contractMenu = ContractMenu(
                        { settings }, { locale }, players, contracts, tasks, null, back = {},
                    )
                    val perkMenu = PerkMenu(
                        { settings }, { locale }, { mockk<PerkCatalog>() }, players, perks, tasks, null, back = {},
                    )
                    val weeklyKitMenu = WeeklyKitMenu(
                        { settings }, { locale }, { mockk<WeeklyKitCatalog>() }, players, weeklyKits, tasks, null, back = {},
                    )

                    contractMenu.open(player)
                    plain(player.nextComponentMessage()) shouldBe plain(
                        locale.render(
                            "commands.feature-disabled",
                            player,
                            mapOf("feature" to locale.render("features.contracts", player)),
                        ),
                    )
                    perkMenu.open(player)
                    plain(player.nextComponentMessage()) shouldBe plain(
                        locale.render(
                            "commands.feature-disabled",
                            player,
                            mapOf("feature" to locale.render("features.perks", player)),
                        ),
                    )
                    weeklyKitMenu.open(player)
                    plain(player.nextComponentMessage()) shouldBe plain(
                        locale.render(
                            "commands.feature-disabled",
                            player,
                            mapOf("feature" to locale.render("features.weekly-kits", player)),
                        ),
                    )

                    verify(exactly = 0) { players.load(any()) }
                    verify(exactly = 0) { contracts.board(any(), any()) }
                    verify(exactly = 0) { weeklyKits.state(any()) }
                } finally {
                    tasks.close()
                }
            }
        }
    }
})

private fun plain(component: net.kyori.adventure.text.Component?): String =
    PlainTextComponentSerializer.plainText().serialize(requireNotNull(component))
