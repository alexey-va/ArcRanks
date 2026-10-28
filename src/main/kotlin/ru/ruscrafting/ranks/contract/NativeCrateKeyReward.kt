package ru.ruscrafting.ranks.contract

import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import su.nightexpress.excellentcrates.CratesAPI

/** Loaded only on backends with ExcellentCrates. Other backends retain the reward outbox. */
internal object NativeCrateKeyReward {
    fun apply(player: Player, keyId: String, amount: Int): Boolean {
        if (!CratesAPI.isLoaded()) return false
        val key = CratesAPI.getKeyManager().getKeyById(keyId) ?: return false
        if (key.isVirtual) return false
        val item = key.itemStack.clone().also { stack ->
            stack.amount = amount
            stack.editMeta { meta ->
                meta.displayName(MiniMessage.miniMessage().deserialize(key.name).decoration(TextDecoration.ITALIC, false))
            }
        }
        if (!canFit(player.inventory.storageContents, item, player.inventory.maxStackSize)) return false
        check(player.inventory.addItem(item).isEmpty()) { "Native crate key inventory changed during reward delivery" }
        return true
    }

    internal fun canFit(contents: Array<ItemStack?>, item: ItemStack, inventoryStackLimit: Int): Boolean {
        val limit = minOf(item.maxStackSize, inventoryStackLimit)
        val capacity = contents.sumOf { existing ->
            when {
                existing == null || existing.type.isAir -> limit
                existing.isSimilar(item) -> (limit - existing.amount).coerceAtLeast(0)
                else -> 0
            }
        }
        return capacity >= item.amount
    }
}
