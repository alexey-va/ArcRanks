package ru.ruscrafting.ranks.gui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.config.RankCatalogLoader
import ru.ruscrafting.ranks.domain.MasteryLevel
import ru.ruscrafting.ranks.domain.PathAvailability
import ru.ruscrafting.ranks.domain.PlayerProgressProfile
import ru.ruscrafting.ranks.domain.ProgressSnapshot
import ru.ruscrafting.ranks.domain.RankEvaluator
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.kit.WeeklyKitAdminResetResult
import ru.ruscrafting.ranks.kit.WeeklyKitCatalogLoader
import ru.ruscrafting.ranks.kit.WeeklyKitClaimState
import ru.ruscrafting.ranks.kit.WeeklyKitService
import ru.ruscrafting.ranks.rankstate.RankState
import ru.ruscrafting.ranks.service.RankPlayerService
import ru.ruscrafting.ranks.service.RankPlayerSnapshot
import ru.ruscrafting.ranks.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.ranks.text.RankLocale
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

class WeeklyKitMenuAdminMockBukkitTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "weekly kit reset is hidden from players and cannot double-submit for an authorized admin" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("ArcRanksWeeklyKitAdminMenuTest")
                val player = paper.addPlayer("KitAdminTester")
                player.isOp = false
                val root = Files.createTempDirectory("arcranks-weekly-kit-admin-menu")
                val settings = ArcRanksSettings.loadFresh(root) { "secret" }
                val locale = RankLocale.fresh(root, { settings.defaultLocale }, { settings.useClientLocale })
                val rankCatalog = RankCatalogLoader(Config(root, "ranks.yml")).loadWithMastery().catalog
                val kitCatalog = WeeklyKitCatalogLoader(Config(root, "weekly-kits.yml")).load()
                val rankId = rankCatalog.ranks.first().id
                val profile = PlayerProgressProfile(ProgressSnapshot.EMPTY, SpecializationPath.FARMING)
                val snapshot = RankPlayerSnapshot(
                    RankState.Exact(rankId),
                    profile,
                    RankEvaluator(rankCatalog).evaluate(rankId, profile.progress, PathAvailability.allAvailable()),
                    SpecializationPath.entries.associateWith { MasteryLevel.NONE },
                    PathAvailability.allAvailable(),
                    emptySet(),
                )
                val players = mockk<RankPlayerService>()
                every { players.load(player.uniqueId) } returns CompletableFuture.completedFuture(snapshot)
                val service = mockk<WeeklyKitService>()
                every { service.state(player.uniqueId) } returns CompletableFuture.completedFuture(WeeklyKitClaimState.CLAIMED)
                every { service.adminReset(player.uniqueId, player.uniqueId.toString()) } returns
                    CompletableFuture.completedFuture(WeeklyKitAdminResetResult.Reset)
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val layouts = ArcRanksMenuLayouts(root)
                try {
                    val menu = WeeklyKitMenu(
                        { settings }, { locale }, { kitCatalog }, players, service, tasks, null,
                        back = {}, layouts = layouts,
                    )
                    paper.server.pluginManager.registerEvents(menu, plugin)

                    menu.open(player)
                    paper.performTicks(1)
                    player.openInventory.topInventory.getItem(layouts.slot(WeeklyKitMenu.MENU, "admin-reset"))?.type shouldBe
                        Material.GRAY_STAINED_GLASS_PANE

                    player.addAttachment(plugin, WeeklyKitMenu.ADMIN_PERMISSION, true)
                    menu.open(player)
                    paper.performTicks(1)
                    val slot = layouts.slot(WeeklyKitMenu.MENU, "admin-reset")
                    val reset = requireNotNull(player.openInventory.topInventory.getItem(slot))
                    reset.type shouldBe Material.COMMAND_BLOCK
                    PlainTextComponentSerializer.plainText().serialize(requireNotNull(reset.itemMeta.displayName())) shouldBe
                        "Админ: сбросить получение набора"

                    val first = paper.callEvent(click(player, slot))
                    val second = paper.callEvent(click(player, slot))
                    first.isCancelled shouldBe true
                    second.isCancelled shouldBe true
                    verify(exactly = 1) { service.adminReset(player.uniqueId, player.uniqueId.toString()) }
                } finally {
                    tasks.close()
                }
            }
        }
    }
})

private fun click(player: org.bukkit.entity.Player, slot: Int) = InventoryClickEvent(
    player.openInventory,
    InventoryType.SlotType.CONTAINER,
    slot,
    ClickType.LEFT,
    InventoryAction.PICKUP_ALL,
)
