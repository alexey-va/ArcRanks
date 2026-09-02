package ru.ruscrafting.ranks.gui

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.ruscrafting.ranks.analytics.ProductDimension
import ru.ruscrafting.ranks.analytics.ProductEvent
import ru.ruscrafting.ranks.analytics.ProductTelemetry
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.config.GuiItemSpec
import ru.ruscrafting.ranks.kit.WeeklyKitCatalog
import ru.ruscrafting.ranks.kit.WeeklyKitClaimRequest
import ru.ruscrafting.ranks.kit.WeeklyKitClaimResult
import ru.ruscrafting.ranks.kit.WeeklyKitClaimState
import ru.ruscrafting.ranks.kit.WeeklyKitDefinition
import ru.ruscrafting.ranks.kit.WeeklyKitService
import ru.ruscrafting.ranks.rankstate.RankState
import ru.ruscrafting.ranks.service.RankPlayerService
import ru.ruscrafting.ranks.text.RankLocale
import java.util.UUID

class WeeklyKitMenu(
    private val settings: () -> ArcRanksSettings,
    private val locale: () -> RankLocale,
    private val catalog: () -> WeeklyKitCatalog,
    private val players: RankPlayerService,
    private val service: WeeklyKitService,
    private val tasks: LifecycleTaskScope,
    private val telemetry: ProductTelemetry?,
    private val back: (Player) -> Unit,
    private val layouts: ArcRanksMenuLayouts,
    private val configGeneration: () -> Long = { 0L },
) : Listener {
    private val items = RankMenuItemFactory { settings().gui.background }

    fun open(player: Player) {
        if (!settings().features.weeklyKits) {
            player.sendMessage(
                locale().render(
                    "commands.feature-disabled",
                    player,
                    mapOf("feature" to locale().render("features.weekly-kits", player)),
                ),
            )
            return
        }
        val holder = WeeklyKitHolder(player.uniqueId, configGeneration())
        val inventory = layouts.create(holder, MENU, locale().render("gui.weekly-kit.title", player))
        holder.menuInventory = inventory
        renderLoading(player, inventory)
        player.openInventory(inventory)
        telemetry?.record(ProductEvent.WEEKLY_KIT_OPEN, ProductDimension.NONE)
        refresh(player, holder)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? WeeklyKitHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory !== event.view.topInventory) return
        val player = event.whoClicked as? Player ?: return
        if (holder.playerId != player.uniqueId) return
        when (event.rawSlot) {
            slot("back") -> back(player)
            slot("claim") -> if (!holder.actionPending && holder.state == WeeklyKitClaimState.AVAILABLE) claim(player, holder)
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is WeeklyKitHolder && event.rawSlots.any { it < event.view.topInventory.size }) {
            event.isCancelled = true
        }
    }

    private fun refresh(player: Player, holder: WeeklyKitHolder) {
        holder.actionPending = true
        holder.generation++
        val generation = holder.generation
        renderLoading(player, holder.menuInventory)
        val currentCatalog = catalog()
        players.load(player.uniqueId).thenCompose { snapshot ->
            val rankId = (snapshot.rankState as? RankState.Exact)?.rankId
                ?: return@thenCompose java.util.concurrent.CompletableFuture.failedFuture(
                    IllegalStateException("Player has no exact progression rank"),
                )
            val definition = currentCatalog.require(rankId)
            service.state(player.uniqueId).thenApply { state -> definition to state }
        }.whenCompleteSync(tasks) { loaded, failure ->
            if (!holder.current(player, generation)) return@whenCompleteSync
            holder.actionPending = false
            if (failure != null || loaded == null) {
                renderError(player, holder.menuInventory)
            } else {
                holder.definition = loaded.first
                holder.state = loaded.second
                render(player, holder.menuInventory, loaded.first, loaded.second)
            }
        }
    }

    private fun claim(player: Player, holder: WeeklyKitHolder) {
        val definition = holder.definition ?: return
        holder.actionPending = true
        holder.generation++
        val generation = holder.generation
        renderClaiming(player, holder.menuInventory, definition)
        val freeSlots = player.inventory.storageContents.count { it == null || it.type.isAir }
        service.claim(
            WeeklyKitClaimRequest(player.uniqueId, player.name, definition, settings().serverId, freeSlots),
        ).whenCompleteSync(tasks) { result, failure ->
            val resolved = if (failure == null && result != null) result else WeeklyKitClaimResult.StorageUnavailable
            val key = when (resolved) {
                WeeklyKitClaimResult.Claimed -> "commands.weekly-kit.claimed"
                WeeklyKitClaimResult.AlreadyClaimed -> "commands.weekly-kit.already-claimed"
                WeeklyKitClaimResult.DeliveryPending -> "commands.weekly-kit.delivery-pending"
                is WeeklyKitClaimResult.InventoryFull -> "commands.weekly-kit.inventory-full"
                WeeklyKitClaimResult.ProviderRejected -> "commands.weekly-kit.provider-rejected"
                WeeklyKitClaimResult.StorageUnavailable -> "commands.weekly-kit.storage-unavailable"
            }
            val dimension = when (resolved) {
                WeeklyKitClaimResult.Claimed -> "rank:${definition.rankId.value}"
                WeeklyKitClaimResult.AlreadyClaimed -> "already"
                WeeklyKitClaimResult.DeliveryPending -> "pending"
                is WeeklyKitClaimResult.InventoryFull -> "inventory"
                WeeklyKitClaimResult.ProviderRejected -> "provider"
                WeeklyKitClaimResult.StorageUnavailable -> "storage"
            }
            telemetry?.record(
                if (resolved == WeeklyKitClaimResult.Claimed) ProductEvent.WEEKLY_KIT_CLAIM else ProductEvent.WEEKLY_KIT_REJECTED,
                ProductDimension(dimension),
            )
            val values = if (resolved is WeeklyKitClaimResult.InventoryFull) {
                mapOf("required" to locale().text(resolved.requiredFreeSlots))
            } else emptyMap()
            player.sendMessage(locale().render(key, player, values))
            if (holder.current(player, generation)) {
                holder.actionPending = false
                refresh(player, holder)
            }
        }
    }

    private fun render(
        player: Player,
        inventory: Inventory,
        definition: WeeklyKitDefinition,
        state: WeeklyKitClaimState,
    ) {
        items.fill(inventory)
        val rank = locale().render("ranks.${definition.rankId.value}.name", player)
        val values = mapOf(
            "rank" to rank,
            "required" to locale().text(definition.minimumFreeSlots),
        )
        inventory.setItem(
            slot("summary"),
            items.item(
                definition.icon,
                locale().render("gui.weekly-kit.summary.name", player, values),
                locale().renderLines("gui.weekly-kit.summary.lore", player, values) +
                    locale().render(definition.summaryKey, player),
            ),
        )
        inventory.setItem(
            slot("contents"),
            items.item(
                settings().gui.item("weekly-kit-contents", GuiItemSpec("BOOK", 0)),
                locale().render("gui.weekly-kit.contents.name", player),
                locale().renderLines("gui.weekly-kit.contents.lore", player) +
                    definition.contentKeys.map { locale().render(it, player) },
            ),
        )
        val stateKey = state.name.lowercase()
        inventory.setItem(
            slot("claim"),
            items.item(
                claimItem(state),
                locale().render("gui.weekly-kit.claim.$stateKey.name", player, values),
                locale().renderLines("gui.weekly-kit.claim.$stateKey.lore", player, values),
            ),
        )
        renderBack(player, inventory)
    }

    private fun renderLoading(player: Player, inventory: Inventory) {
        items.fill(inventory)
        inventory.setItem(
            slot("contents"),
            items.item(
                settings().gui.item("loading", GuiItemSpec("CLOCK", 0)),
                locale().render("gui.weekly-kit.loading.name", player),
                locale().renderLines("gui.weekly-kit.loading.lore", player),
            ),
        )
        renderBack(player, inventory)
    }

    private fun renderClaiming(player: Player, inventory: Inventory, definition: WeeklyKitDefinition) {
        inventory.setItem(
            slot("claim"),
            items.item(definition.icon, locale().render("gui.weekly-kit.claiming.name", player), locale().renderLines("gui.weekly-kit.claiming.lore", player)),
        )
    }

    private fun renderError(player: Player, inventory: Inventory) {
        items.fill(inventory)
        inventory.setItem(
            slot("contents"),
            items.item(
                settings().gui.item("error", GuiItemSpec("RED_STAINED_GLASS_PANE", 0)),
                locale().render("gui.weekly-kit.error.name", player),
                locale().renderLines("gui.weekly-kit.error.lore", player),
            ),
        )
        renderBack(player, inventory)
    }

    private fun renderBack(player: Player, inventory: Inventory) {
        inventory.setItem(
            slot("back"),
            items.item(settings().gui.back, locale().render("gui.common.back.name", player), locale().renderLines("gui.common.back.lore", player)),
        )
    }

    private fun claimItem(state: WeeklyKitClaimState): GuiItemSpec = when (state) {
        WeeklyKitClaimState.AVAILABLE ->
            settings().gui.item("weekly-kit-available", GuiItemSpec("CHEST", 0))
        WeeklyKitClaimState.DELIVERING ->
            settings().gui.item("weekly-kit-delivering", GuiItemSpec("CLOCK", 0))
        WeeklyKitClaimState.CLAIMED ->
            settings().gui.item("weekly-kit-claimed", GuiItemSpec("LIME_DYE", 0))
    }

    companion object {
        val MENU = ArcRanksMenuLayouts.WEEKLY_KIT
    }

    private fun slot(id: String): Int = layouts.slot(MENU, id)
}

private class WeeklyKitHolder(
    override val playerId: UUID,
    override val configGeneration: Long,
) : ArcRanksInventoryHolder {
    lateinit var menuInventory: Inventory
    var generation = 0L
    var actionPending = false
    var definition: WeeklyKitDefinition? = null
    var state: WeeklyKitClaimState = WeeklyKitClaimState.AVAILABLE

    fun current(player: Player, expectedGeneration: Long): Boolean =
        player.isOnline && generation == expectedGeneration && player.openInventory.topInventory === menuInventory

    override fun getInventory(): Inventory = menuInventory
}
