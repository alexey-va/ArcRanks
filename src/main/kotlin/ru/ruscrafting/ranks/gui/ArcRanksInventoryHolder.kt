package ru.ruscrafting.ranks.gui

import org.bukkit.inventory.InventoryHolder
import java.util.UUID

/** Identifies an ArcRanks menu session and the configuration snapshot that rendered it. */
interface ArcRanksInventoryHolder : InventoryHolder {
    val playerId: UUID
    val configGeneration: Long
}
