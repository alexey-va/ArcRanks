package ru.ruscrafting.ranks.gui

import org.bukkit.Server

object ArcRanksMenuSessions {
    /**
     * Closes ArcRanks-owned menus without disturbing inventories owned by other plugins.
     *
     * Bukkit inventory mutations must remain on the primary server thread; callers own that
     * scheduling boundary.
    */
    fun closeOpen(server: Server): Int = server.onlinePlayers.count { player ->
        // MockBukkit exposes a transient null top inventory after close; the real Paper contract
        // is non-null, but treating that transient state as "no ArcRanks menu" keeps this helper
        // safely idempotent across platform implementations.
        val topInventory = runCatching { player.openInventory.topInventory }.getOrNull()
            ?: return@count false
        val holder = topInventory.holder
        if (holder !is ArcRanksInventoryHolder) {
            false
        } else {
            player.closeInventory()
            true
        }
    }
}
