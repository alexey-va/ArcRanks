package ru.ruscrafting.ranks.gui

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import ru.ruscrafting.ranks.text.RankLocale
import java.util.UUID

/** Compatibility entry point for retired kit menus and already-open inventories. */
class WeeklyKitMenu(private val locale: () -> RankLocale) : Listener {
    fun open(player: Player) {
        player.sendMessage(locale().chat("commands.weekly-kit.retired", player))
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.view.topInventory.holder !is WeeklyKitHolder) return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        player.closeInventory()
        open(player)
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is WeeklyKitHolder && event.rawSlots.any { it < event.view.topInventory.size }) {
            event.isCancelled = true
        }
    }

    companion object {
        val MENU = ArcRanksMenuLayouts.WEEKLY_KIT
        const val ADMIN_PERMISSION = "arcranks.admin.kit"
    }
}

private class WeeklyKitHolder(
    override val playerId: UUID,
    override val configGeneration: Long,
) : ArcRanksInventoryHolder {
    lateinit var menuInventory: Inventory
    override fun getInventory(): Inventory = menuInventory
}
