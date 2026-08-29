package ru.ruscrafting.ranks.progress

import org.bukkit.block.data.Ageable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.FurnaceExtractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import ru.ruscrafting.ranks.domain.ProgressMetric

class RankProgressListener(
    private val buffer: ProgressBuffer,
    private val movement: MovementAccumulator,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (ProgressEventRules.eligible(event.player.gameMode)) {
            buffer.recordCounter(event.player.uniqueId, ProgressMetric.BLOCKS_PLACED, 1)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (!ProgressEventRules.eligible(event.player.gameMode)) return
        val ageable = event.block.blockData as? Ageable
        if (ProgressEventRules.isMatureCrop(event.block.type, ageable?.age, ageable?.maximumAge)) {
            buffer.recordCounter(event.player.uniqueId, ProgressMetric.CROPS_HARVESTED, 1)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCraft(event: CraftItemEvent) {
        val player = event.whoClicked as? org.bukkit.entity.Player ?: return
        if (ProgressEventRules.eligible(player.gameMode) && event.recipe.result.type.isItem) {
            buffer.recordCounter(player.uniqueId, ProgressMetric.PRODUCTION_ACTIONS, 1)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFurnaceExtract(event: FurnaceExtractEvent) {
        if (ProgressEventRules.eligible(event.player.gameMode) && event.itemAmount > 0) {
            buffer.recordCounter(event.player.uniqueId, ProgressMetric.PRODUCTION_ACTIONS, event.itemAmount.toLong())
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val player = event.player
        val to = event.to
        if (!ProgressEventRules.eligible(player.gameMode)) {
            movement.clear(player.uniqueId)
            return
        }
        val blocks = movement.observe(
            player.uniqueId,
            MovementPoint(event.from.world.uid.toString(), event.from.x, event.from.y, event.from.z),
            MovementPoint(to.world.uid.toString(), to.x, to.y, to.z),
        )
        if (blocks > 0) buffer.recordCounter(player.uniqueId, ProgressMetric.TRAVEL_BLOCKS, blocks)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        movement.clear(event.player.uniqueId)
        buffer.flush(event.player.uniqueId)
    }
}
