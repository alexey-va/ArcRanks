package ru.ruscrafting.ranks.gui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import ru.ruscrafting.ranks.config.GuiItemSpec

class RankMenuItemFactory(private val background: () -> GuiItemSpec) {
    fun fill(inventory: Inventory) {
        val filler = item(background(), Component.empty(), emptyList())
        repeat(inventory.size) { inventory.setItem(it, filler) }
    }

    @Suppress("DEPRECATION")
    fun item(spec: GuiItemSpec, name: Component, lore: List<Component>): ItemStack {
        val material = Material.matchMaterial(spec.material) ?: error("Unknown GUI material ${spec.material}")
        return ItemStack(material).apply {
            itemMeta = itemMeta.apply {
                displayName(name.decoration(TextDecoration.ITALIC, false))
                lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
                if (spec.customModelData > 0) setCustomModelData(spec.customModelData)
            }
        }
    }
}
