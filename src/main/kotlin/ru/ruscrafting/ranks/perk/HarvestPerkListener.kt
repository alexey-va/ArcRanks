package ru.ruscrafting.ranks.perk

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.TileState
import org.bukkit.block.data.Ageable
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.player.PlayerAttemptPickupItemEvent
import org.bukkit.inventory.ItemStack
import ru.arc.core.LifecycleTaskScope
import ru.ruscrafting.ranks.config.ArcRanksSettings
import java.util.UUID

/** Replants mature native crops and gathers only the breaking player's ordinary block drops. */
class HarvestPerkListener(
    private val settings: () -> ArcRanksSettings,
    private val catalog: () -> PerkCatalog,
    private val selected: (UUID) -> Collection<PerkId>,
    private val tasks: LifecycleTaskScope,
    private val generation: () -> Long,
    private val canPlant: (Player, Block, Material) -> Boolean,
) : Listener {
    private data class Crop(val seed: Material)

    private data class PendingDrop(
        val entity: Item,
        val contents: ItemStack,
    )

    private fun eligible(player: Player): Boolean = settings().let {
        it.features.perks && it.collection.allows(player.gameMode.name, player.world.name)
    }

    private fun has(player: Player, kind: PerkEffectKind): Boolean = eligible(player) &&
        catalog().gameplayEffect(selected(player.uniqueId), kind) > 0

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockDrop(event: BlockDropItemEvent) {
        if (event.isCancelled) return
        val player = event.player
        val block = event.block
        val worldId = block.world.uid
        val epoch = generation()
        val token = tasks.token()

        val originalType = event.blockState.type
        val crop = CROPS[originalType]
        val originalData = event.blockState.blockData as? Ageable
        if (crop != null && originalData != null && originalData.age == originalData.maximumAge &&
            has(player, PerkEffectKind.CROP_REPLANT) && !player.isSneaking) {
            val plantedData = originalData.clone() as? Ageable
            if (plantedData != null) {
                plantedData.age = 0
                tasks.runLater(token, REPLANT_DELAY_TICKS) {
                    if (generation() != epoch || event.isCancelled || !player.isOnline || player.isDead ||
                        player.isSneaking || player.world.uid != worldId || !has(player, PerkEffectKind.CROP_REPLANT) ||
                        !block.world.isChunkLoaded(block.x shr 4, block.z shr 4) || !block.type.isAir ||
                        block.getRelative(0, -1, 0).type != Material.FARMLAND ||
                        !plantedData.isSupported(block.location) || !canPlant(player, block, originalType)) return@runLater

                    val inventory = player.inventory
                    val seedSlot = (0 until inventory.storageContents.size).firstOrNull { slot ->
                        inventory.getItem(slot)?.let { it.isSimilar(ItemStack(crop.seed)) && it.amount > 0 } == true
                    } ?: return@runLater
                    val seedStack = inventory.getItem(seedSlot) ?: return@runLater
                    if (seedStack.amount == 1) inventory.setItem(seedSlot, null)
                    else inventory.setItem(seedSlot, seedStack.clone().also { it.amount -= 1 })
                    block.setBlockData(plantedData, false)
                }
            }
        }

        if (event.blockState is TileState || !has(player, PerkEffectKind.BLOCK_DROP_PICKUP)) return
        val drops = event.items.mapNotNull { entity ->
            val contents = entity.itemStack
            if (isOrdinary(contents) && contents.amount > 0) PendingDrop(entity, contents.clone()) else null
        }
        if (drops.isEmpty()) return

        tasks.runLater(token, PICKUP_DELAY_TICKS) {
            if (generation() != epoch || event.isCancelled || !player.isOnline || player.isDead ||
                player.world.uid != worldId || !has(player, PerkEffectKind.BLOCK_DROP_PICKUP) ||
                event.blockState is TileState) return@runLater
            drops.forEach { pending ->
                if (event.items.none { it === pending.entity }) return@forEach
                collect(player, pending, epoch)
            }
        }
    }

    private fun collect(player: Player, pending: PendingDrop, epoch: Long) {
        val entity = pending.entity
        if (!canCollect(player, pending, epoch)) return
        val current = entity.itemStack
        val capacity = inventoryCapacity(player, current)
        if (capacity <= 0) return

        val remaining = (current.amount - capacity).coerceAtLeast(0)
        val attempt = PlayerAttemptPickupItemEvent(player, entity, remaining)
        Bukkit.getPluginManager().callEvent(attempt)
        if (attempt.isCancelled || !canCollect(player, pending, epoch)) return
        val pickupEvent = EntityPickupItemEvent(player, entity, current.amount - inventoryCapacity(player, current))
        Bukkit.getPluginManager().callEvent(pickupEvent)
        if (pickupEvent.isCancelled || !canCollect(player, pending, epoch)) return

        val fresh = entity.itemStack
        val amount = minOf(fresh.amount, inventoryCapacity(player, fresh))
        if (amount <= 0) return
        val requested = fresh.clone().also { it.amount = amount }
        val leftovers = player.inventory.addItem(requested)
        val added = amount - leftovers.values.sumOf { it.amount }
        if (added <= 0) return

        val left = fresh.clone().also { it.amount -= added }
        if (left.amount == 0) entity.remove() else entity.itemStack = left
    }

    private fun canCollect(player: Player, pending: PendingDrop, epoch: Long): Boolean {
        val entity = pending.entity
        return generation() == epoch && player.isOnline && !player.isDead &&
            has(player, PerkEffectKind.BLOCK_DROP_PICKUP) && entity.isValid && !entity.isDead &&
            entity.world.uid == player.world.uid && player.canPickupItems && entity.canPlayerPickup() &&
            entity.pickupDelay <= 0 && (entity.owner == null || entity.owner == player.uniqueId) &&
            (entity.thrower == null || entity.thrower == player.uniqueId) && sameStack(entity.itemStack, pending.contents)
    }

    private fun inventoryCapacity(player: Player, stack: ItemStack): Int {
        val inventory = player.inventory
        val maxStack = minOf(stack.maxStackSize, inventory.maxStackSize)
        val slots = inventory.storageContents
        val available = slots.sumOf { existing ->
            when {
                existing == null || existing.type.isAir -> maxStack
                existing.isSimilar(stack) -> (maxStack - existing.amount).coerceAtLeast(0)
                else -> 0
            }
        }
        return minOf(stack.amount, available)
    }

    private fun isOrdinary(stack: ItemStack): Boolean = !stack.type.isAir && stack.isSimilar(ItemStack(stack.type))

    private fun sameStack(left: ItemStack, right: ItemStack): Boolean = left.amount == right.amount && left.isSimilar(right)

    companion object {
        private const val REPLANT_DELAY_TICKS = 5L
        private const val PICKUP_DELAY_TICKS = 10L
        private val CROPS = mapOf(
            Material.WHEAT to Crop(Material.WHEAT_SEEDS),
            Material.CARROTS to Crop(Material.CARROT),
            Material.POTATOES to Crop(Material.POTATO),
            Material.BEETROOTS to Crop(Material.BEETROOT_SEEDS),
        )
    }
}
