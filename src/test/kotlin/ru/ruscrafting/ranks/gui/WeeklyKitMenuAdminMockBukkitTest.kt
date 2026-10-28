package ru.ruscrafting.ranks.gui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.InventoryHolder
import ru.arc.config.ConfigManager
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.ranks.text.RankLocale
import java.nio.file.Files
import java.util.UUID

class WeeklyKitMenuAdminMockBukkitTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "retired menu refuses entry even for an administrator" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("ArcRanksRetiredKitMenu")
                val player = paper.addPlayer("KitAdminTester")
                player.addAttachment(plugin, WeeklyKitMenu.ADMIN_PERMISSION, true)
                val root = Files.createTempDirectory("arcranks-retired-kit-menu")
                val settings = ArcRanksSettings.loadFresh(root) { "secret" }
                val locale = RankLocale.fresh(root, { settings.defaultLocale }, { settings.useClientLocale })
                val menu = WeeklyKitMenu { locale }
                val before = paper.server.createInventory(null, 9, Component.text("Existing screen"))
                player.openInventory(before)

                menu.open(player)

                (player.openInventory.topInventory === before) shouldBe true
                plain(player.nextComponentMessage()) shouldBe plain(locale.chat("commands.weekly-kit.retired", player))
            }
        }
    }

    "stale kit inventory click closes the retired screen without taking its items" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("ArcRanksStaleKitMenu")
                val player = paper.addPlayer("StaleKitTester")
                player.addAttachment(plugin, WeeklyKitMenu.ADMIN_PERMISSION, true)
                val root = Files.createTempDirectory("arcranks-stale-kit-menu")
                val settings = ArcRanksSettings.loadFresh(root) { "secret" }
                val locale = RankLocale.fresh(root, { settings.defaultLocale }, { settings.useClientLocale })
                val menu = WeeklyKitMenu { locale }
                paper.server.pluginManager.registerEvents(menu, plugin)
                val holderType = Class.forName("ru.ruscrafting.ranks.gui.WeeklyKitHolder")
                val holder = holderType.getDeclaredConstructor(UUID::class.java, Long::class.javaPrimitiveType)
                    .apply { isAccessible = true }.newInstance(player.uniqueId, 0L) as InventoryHolder
                val layouts = ArcRanksMenuLayouts(root)
                val inventory = layouts.create(holder, WeeklyKitMenu.MENU, locale.render("gui.weekly-kit.title", player))
                holderType.getDeclaredField("menuInventory").apply { isAccessible = true }.set(holder, inventory)
                player.openInventory(inventory)

                val click = paper.callEvent(InventoryClickEvent(
                    player.openInventory, InventoryType.SlotType.CONTAINER,
                    layouts.slot(WeeklyKitMenu.MENU, "admin-reset"), ClickType.LEFT, InventoryAction.PICKUP_ALL,
                ))

                click.isCancelled shouldBe true
                (player.openInventory.topInventory === inventory) shouldBe false
                plain(player.nextComponentMessage()) shouldBe plain(locale.chat("commands.weekly-kit.retired", player))
            }
        }
    }
})

private fun plain(component: net.kyori.adventure.text.Component?): String =
    PlainTextComponentSerializer.plainText().serialize(requireNotNull(component))
