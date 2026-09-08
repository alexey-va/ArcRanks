package ru.ruscrafting.ranks.progress

import io.papermc.paper.event.player.AsyncChatEvent
import io.papermc.paper.event.player.PlayerTradeEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
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
import ru.arc.core.LifecycleTaskScope
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.perk.PerkProgressModifier
import ru.ruscrafting.ranks.quest.QuestProgressDiagnostics
import ru.ruscrafting.ranks.quest.QuestProgressRejectReason
import java.time.Instant

class RankProgressListener(
    private val buffer: ProgressBuffer,
    private val movement: MovementAccumulator,
    private val modifier: PerkProgressModifier,
    private val settings: () -> ArcRanksSettings,
    private val tasks: LifecycleTaskScope,
    private val communityChat: CommunityChatProgressGate = CommunityChatProgressGate(),
    private val building: BuildingProgressGate = BuildingProgressGate(),
    private val diagnostics: QuestProgressDiagnostics? = null,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val collection = settings().collection
        val objective = "build:${event.block.type.name.lowercase(java.util.Locale.ROOT)}"
        when {
            !collection.blockPlace.enabled -> diagnostics?.rejected(event.player.uniqueId, objective, QuestProgressRejectReason.SOURCE_DISABLED)
            !collection.blockPlace.allows(event.block.type.name) -> diagnostics?.rejected(event.player.uniqueId, objective, QuestProgressRejectReason.MATERIAL_FILTERED)
            !collection.allows(event.player.gameMode.name, event.player.world.name) -> diagnostics?.rejected(event.player.uniqueId, objective, QuestProgressRejectReason.CONTEXT_INELIGIBLE)
            !building.credit(event.block.world.uid, event.block.x, event.block.y, event.block.z,
                Instant.now(), collection.buildingRepeatWindowSeconds) -> diagnostics?.rejected(event.player.uniqueId, objective, QuestProgressRejectReason.DUPLICATE_POSITION)
            else -> recordCounter(event.player.uniqueId, ProgressMetric.BLOCKS_PLACED, collection.blockPlace.amount, objective,
                mapOf(objective to 1L))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val collection = settings().collection
        val objective = "harvest:${event.block.type.name.lowercase(java.util.Locale.ROOT)}"
        when {
            !collection.matureCrop.enabled -> {
                diagnostics?.rejected(event.player.uniqueId, objective, QuestProgressRejectReason.SOURCE_DISABLED)
                return
            }
            event.block.type.name !in collection.matureCropMaterials -> {
                diagnostics?.rejected(event.player.uniqueId, objective, QuestProgressRejectReason.MATERIAL_FILTERED)
                return
            }
            !collection.allows(event.player.gameMode.name, event.player.world.name) -> {
                diagnostics?.rejected(event.player.uniqueId, objective, QuestProgressRejectReason.CONTEXT_INELIGIBLE)
                return
            }
        }
        val ageable = event.block.blockData as? Ageable
        if (ProgressEventRules.isMatureCrop(
                event.block.type,
                ageable?.age,
                ageable?.maximumAge,
                collection.matureCropMaterials,
        )
        ) {
            recordCounter(event.player.uniqueId, ProgressMetric.CROPS_HARVESTED, collection.matureCrop.amount, objective,
                mapOf(objective to 1L))
        } else {
            diagnostics?.rejected(event.player.uniqueId, objective, QuestProgressRejectReason.NOT_MATURE)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreed(event: EntityBreedEvent) {
        val player = event.breeder as? org.bukkit.entity.Player ?: return
        val source = settings().collection.animalBreeding
        val objective = "breed:${event.entity.type.name.lowercase(java.util.Locale.ROOT)}"
        when {
            !source.enabled -> diagnostics?.rejected(player.uniqueId, objective, QuestProgressRejectReason.SOURCE_DISABLED)
            !settings().collection.allows(player.gameMode.name, player.world.name) -> diagnostics?.rejected(player.uniqueId, objective, QuestProgressRejectReason.CONTEXT_INELIGIBLE)
            else -> recordCounter(player.uniqueId, ProgressMetric.CROPS_HARVESTED, source.amount, objective, mapOf(objective to 1L))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        val source = settings().collection.fishing
        val objective = "fish:${(event.caught as? org.bukkit.entity.Item)?.itemStack?.type?.name?.lowercase(java.util.Locale.ROOT) ?: "other"}"
        when {
            !source.enabled -> diagnostics?.rejected(event.player.uniqueId, objective, QuestProgressRejectReason.SOURCE_DISABLED)
            event.state != PlayerFishEvent.State.CAUGHT_FISH -> return
            !settings().collection.allows(event.player.gameMode.name, event.player.world.name) -> diagnostics?.rejected(event.player.uniqueId, objective, QuestProgressRejectReason.CONTEXT_INELIGIBLE)
            else -> recordCounter(event.player.uniqueId, ProgressMetric.CROPS_HARVESTED, source.amount, objective, mapOf(objective to 1L))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCraft(event: CraftItemEvent) {
        val player = event.whoClicked as? org.bukkit.entity.Player ?: return
        val collection = settings().collection
        val objective = "craft:${event.recipe.result.type.name.lowercase(java.util.Locale.ROOT)}"
        when {
            !collection.crafting.enabled -> diagnostics?.rejected(player.uniqueId, objective, QuestProgressRejectReason.SOURCE_DISABLED)
            !collection.crafting.allows(event.recipe.result.type.name) -> diagnostics?.rejected(player.uniqueId, objective, QuestProgressRejectReason.MATERIAL_FILTERED)
            !collection.allows(player.gameMode.name, player.world.name) -> diagnostics?.rejected(player.uniqueId, objective, QuestProgressRejectReason.CONTEXT_INELIGIBLE)
            !event.recipe.result.type.isItem || event.action == org.bukkit.event.inventory.InventoryAction.NOTHING -> return
            else -> recordCounter(player.uniqueId, ProgressMetric.PRODUCTION_ACTIONS, collection.crafting.amount, objective, mapOf(objective to 1L))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFurnaceExtract(event: FurnaceExtractEvent) {
        val collection = settings().collection
        val objective = "smelt:${event.itemType.name.lowercase(java.util.Locale.ROOT)}"
        when {
            !collection.furnace.enabled -> diagnostics?.rejected(event.player.uniqueId, objective, QuestProgressRejectReason.SOURCE_DISABLED)
            !collection.furnace.allows(event.itemType.name) -> diagnostics?.rejected(event.player.uniqueId, objective, QuestProgressRejectReason.MATERIAL_FILTERED)
            !collection.allows(event.player.gameMode.name, event.player.world.name) -> diagnostics?.rejected(event.player.uniqueId, objective, QuestProgressRejectReason.CONTEXT_INELIGIBLE)
            event.itemAmount <= 0 -> return
            else -> recordCounter(
                event.player.uniqueId,
                ProgressMetric.PRODUCTION_ACTIONS,
                Math.multiplyExact(event.itemAmount.toLong(), collection.furnace.amount),
                objective,
                mapOf(objective to event.itemAmount.toLong()),
            )
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEnchant(event: EnchantItemEvent) {
        val source = settings().collection.enchanting
        when {
            !source.enabled -> diagnostics?.rejected(event.enchanter.uniqueId, "enchant", QuestProgressRejectReason.SOURCE_DISABLED)
            !settings().collection.allows(event.enchanter.gameMode.name, event.enchanter.world.name) -> diagnostics?.rejected(event.enchanter.uniqueId, "enchant", QuestProgressRejectReason.CONTEXT_INELIGIBLE)
            else -> recordCounter(event.enchanter.uniqueId, ProgressMetric.PRODUCTION_ACTIONS, source.amount, "enchant", mapOf("enchant" to 1L))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSmith(event: SmithItemEvent) {
        val player = event.whoClicked as? org.bukkit.entity.Player ?: return
        val source = settings().collection.smithing
        when {
            !source.enabled -> diagnostics?.rejected(player.uniqueId, "smith", QuestProgressRejectReason.SOURCE_DISABLED)
            !settings().collection.allows(player.gameMode.name, player.world.name) -> diagnostics?.rejected(player.uniqueId, "smith", QuestProgressRejectReason.CONTEXT_INELIGIBLE)
            else -> recordCounter(player.uniqueId, ProgressMetric.PRODUCTION_ACTIONS, source.amount, "smith", mapOf("smith" to 1L))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onVillagerTrade(event: PlayerTradeEvent) {
        val source = settings().collection.villagerTrade
        when {
            !source.enabled -> diagnostics?.rejected(event.player.uniqueId, "trade", QuestProgressRejectReason.SOURCE_DISABLED)
            !settings().collection.allows(event.player.gameMode.name, event.player.world.name) -> diagnostics?.rejected(event.player.uniqueId, "trade", QuestProgressRejectReason.CONTEXT_INELIGIBLE)
            else -> recordCounter(event.player.uniqueId, ProgressMetric.TRADE_ACTIONS, source.amount, "trade", mapOf("trade" to 1L))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDecorationPlace(event: HangingPlaceEvent) {
        val player = event.player ?: return
        val source = settings().collection.decorationPlace
        val location = event.entity.location
        when {
            !source.enabled -> diagnostics?.rejected(player.uniqueId, "decorate", QuestProgressRejectReason.SOURCE_DISABLED)
            !settings().collection.allows(player.gameMode.name, player.world.name) -> diagnostics?.rejected(player.uniqueId, "decorate", QuestProgressRejectReason.CONTEXT_INELIGIBLE)
            !building.credit(location.world.uid, location.blockX, location.blockY, location.blockZ,
                Instant.now(), settings().collection.buildingRepeatWindowSeconds) -> diagnostics?.rejected(player.uniqueId, "decorate", QuestProgressRejectReason.DUPLICATE_POSITION)
            else -> recordCounter(player.uniqueId, ProgressMetric.BLOCKS_PLACED, source.amount, "decorate", mapOf("decorate" to 1L))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onAdvancement(event: PlayerAdvancementDoneEvent) {
        val player = event.player
        val collection = settings().collection
        if (!collection.allows(player.gameMode.name, player.world.name)) return
        val key = event.advancement.key
        val exploration = ProgressEventRules.isExplorationAdvancement(key.namespace, key.key)
        if (exploration && !collection.explorationAdvancement.enabled) {
            diagnostics?.rejected(player.uniqueId, "advancement", QuestProgressRejectReason.SOURCE_DISABLED)
        } else if (exploration) {
            recordCounter(
                player.uniqueId,
                ProgressMetric.TRAVEL_BLOCKS,
                collection.explorationAdvancement.amount,
                "advancement",
                mapOf("advancement" to 1L),
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
            diagnostics?.rejected(
                player.uniqueId,
                "travel",
                if (!collection.travelEnabled) QuestProgressRejectReason.SOURCE_DISABLED else QuestProgressRejectReason.CONTEXT_INELIGIBLE,
            )
            return
        }
        val blocks = movement.observe(
            player.uniqueId,
            MovementPoint(event.from.world.uid.toString(), event.from.x, event.from.y, event.from.z),
            MovementPoint(to.world.uid.toString(), to.x, to.y, to.z),
        )
        if (blocks > 0) recordCounter(player.uniqueId, ProgressMetric.TRAVEL_BLOCKS, blocks, "travel", mapOf("travel" to blocks))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        val playerId = player.uniqueId
        val message = PlainTextComponentSerializer.plainText().serialize(event.message())
        tasks.runSync {
            if (!player.isOnline) return@runSync
            val collection = settings().collection
            if (!collection.communityEnabled || !collection.allows(player.gameMode.name, player.world.name)) return@runSync
            val amount = communityChat.credit(playerId, message, Instant.now(), collection.communityChat)
            if (amount > 0) modifier.recordCounter(playerId, ProgressMetric.COMMUNITY_MINUTES, amount)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        movement.clear(event.player.uniqueId)
        modifier.clear(event.player.uniqueId)
        diagnostics?.clear(event.player.uniqueId)
        buffer.flush(event.player.uniqueId)
    }

    private fun recordCounter(
        playerId: java.util.UUID,
        metric: ProgressMetric,
        delta: Long,
        objective: String,
        questDeltas: Map<String, Long>,
    ): Boolean {
        val accepted = modifier.recordCounter(playerId, metric, delta, questDeltas)
        if (accepted) {
            diagnostics?.accepted(playerId, objective)
        } else {
            diagnostics?.rejected(playerId, objective, QuestProgressRejectReason.BUFFER_FULL)
        }
        return accepted
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
