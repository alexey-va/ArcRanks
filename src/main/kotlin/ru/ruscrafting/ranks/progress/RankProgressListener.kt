package ru.ruscrafting.ranks.progress

import io.papermc.paper.event.player.PlayerTradeEvent
import org.bukkit.block.data.Ageable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.entity.EntityBreedEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.FurnaceExtractEvent
import org.bukkit.event.inventory.SmithItemEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.perk.PerkProgressModifier

class RankProgressListener(
    private val buffer: ProgressBuffer,
    private val movement: MovementAccumulator,
    private val modifier: PerkProgressModifier,
    private val settings: () -> ArcRanksSettings,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val collection = settings().collection
        if (
            collection.blockPlace.enabled &&
            collection.blockPlace.allows(event.block.type.name) &&
            collection.allows(event.player.gameMode.name, event.player.world.name)
        ) {
            modifier.recordCounter(event.player.uniqueId, ProgressMetric.BLOCKS_PLACED, collection.blockPlace.amount)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val collection = settings().collection
        if (!collection.matureCrop.enabled || !collection.allows(event.player.gameMode.name, event.player.world.name)) return
        val ageable = event.block.blockData as? Ageable
        if (ProgressEventRules.isMatureCrop(
                event.block.type,
                ageable?.age,
                ageable?.maximumAge,
                collection.matureCropMaterials,
            )
        ) {
            modifier.recordCounter(event.player.uniqueId, ProgressMetric.CROPS_HARVESTED, collection.matureCrop.amount)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreed(event: EntityBreedEvent) {
        val player = event.breeder as? org.bukkit.entity.Player ?: return
        val source = settings().collection.animalBreeding
        if (source.enabled && settings().collection.allows(player.gameMode.name, player.world.name)) {
            modifier.recordCounter(player.uniqueId, ProgressMetric.CROPS_HARVESTED, source.amount)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        val source = settings().collection.fishing
        if (
            source.enabled &&
            event.state == PlayerFishEvent.State.CAUGHT_FISH &&
            settings().collection.allows(event.player.gameMode.name, event.player.world.name)
        ) {
            modifier.recordCounter(event.player.uniqueId, ProgressMetric.CROPS_HARVESTED, source.amount)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCraft(event: CraftItemEvent) {
        val player = event.whoClicked as? org.bukkit.entity.Player ?: return
        val collection = settings().collection
        if (
            collection.crafting.enabled &&
            collection.crafting.allows(event.recipe.result.type.name) &&
            collection.allows(player.gameMode.name, player.world.name) &&
            event.recipe.result.type.isItem
        ) {
            modifier.recordCounter(player.uniqueId, ProgressMetric.PRODUCTION_ACTIONS, collection.crafting.amount)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFurnaceExtract(event: FurnaceExtractEvent) {
        val collection = settings().collection
        if (
            collection.furnace.enabled &&
            collection.furnace.allows(event.itemType.name) &&
            collection.allows(event.player.gameMode.name, event.player.world.name) &&
            event.itemAmount > 0
        ) {
            modifier.recordCounter(
                event.player.uniqueId,
                ProgressMetric.PRODUCTION_ACTIONS,
                Math.multiplyExact(event.itemAmount.toLong(), collection.furnace.amount),
            )
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEnchant(event: EnchantItemEvent) {
        val source = settings().collection.enchanting
        if (source.enabled && settings().collection.allows(event.enchanter.gameMode.name, event.enchanter.world.name)) {
            modifier.recordCounter(event.enchanter.uniqueId, ProgressMetric.PRODUCTION_ACTIONS, source.amount)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSmith(event: SmithItemEvent) {
        val player = event.whoClicked as? org.bukkit.entity.Player ?: return
        val source = settings().collection.smithing
        if (source.enabled && settings().collection.allows(player.gameMode.name, player.world.name)) {
            modifier.recordCounter(player.uniqueId, ProgressMetric.PRODUCTION_ACTIONS, source.amount)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onVillagerTrade(event: PlayerTradeEvent) {
        val source = settings().collection.villagerTrade
        if (source.enabled && settings().collection.allows(event.player.gameMode.name, event.player.world.name)) {
            modifier.recordCounter(event.player.uniqueId, ProgressMetric.TRADE_ACTIONS, source.amount)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDecorationPlace(event: HangingPlaceEvent) {
        val player = event.player ?: return
        val source = settings().collection.decorationPlace
        if (source.enabled && settings().collection.allows(player.gameMode.name, player.world.name)) {
            modifier.recordCounter(player.uniqueId, ProgressMetric.BLOCKS_PLACED, source.amount)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onAdvancement(event: PlayerAdvancementDoneEvent) {
        val player = event.player
        val collection = settings().collection
        if (!collection.allows(player.gameMode.name, player.world.name)) return
        val key = event.advancement.key
        if (collection.explorationAdvancement.enabled && ProgressEventRules.isExplorationAdvancement(key.namespace, key.key)) {
            modifier.recordCounter(
                player.uniqueId,
                ProgressMetric.TRAVEL_BLOCKS,
                collection.explorationAdvancement.amount,
            )
        }
        if (
            collection.communityEnabled &&
            collection.sharedAdvancement.enabled &&
            ProgressEventRules.isMeaningfulAdvancement(key.namespace, key.key) &&
            nearbyEligiblePlayers(player, collection.communityMinimumNearbyPlayers)
        ) {
            modifier.recordCounter(
                player.uniqueId,
                ProgressMetric.COMMUNITY_MINUTES,
                collection.sharedAdvancement.amount,
            )
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val player = event.player
        val to = event.to
        val collection = settings().collection
        if (!collection.travelEnabled || !collection.allows(player.gameMode.name, player.world.name)) {
            movement.clear(player.uniqueId)
            return
        }
        val blocks = movement.observe(
            player.uniqueId,
            MovementPoint(event.from.world.uid.toString(), event.from.x, event.from.y, event.from.z),
            MovementPoint(to.world.uid.toString(), to.x, to.y, to.z),
        )
        if (blocks > 0) modifier.recordCounter(player.uniqueId, ProgressMetric.TRAVEL_BLOCKS, blocks)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        movement.clear(event.player.uniqueId)
        modifier.clear(event.player.uniqueId)
        buffer.flush(event.player.uniqueId)
    }

    private fun nearbyEligiblePlayers(player: org.bukkit.entity.Player, required: Int): Boolean {
        val collection = settings().collection
        return player.world.getNearbyPlayers(player.location, settings().communityRadiusBlocks)
            .asSequence()
            .filter { it.uniqueId != player.uniqueId }
            .filter { collection.allows(it.gameMode.name, it.world.name) }
            .take(required)
            .count() >= required
    }
}
