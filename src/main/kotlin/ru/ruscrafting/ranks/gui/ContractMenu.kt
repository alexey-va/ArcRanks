package ru.ruscrafting.ranks.gui

import org.bukkit.Bukkit
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
import ru.ruscrafting.ranks.contract.ContractAcceptResult
import ru.ruscrafting.ranks.contract.ContractBoard
import ru.ruscrafting.ranks.contract.ContractClaimResult
import ru.ruscrafting.ranks.contract.ContractId
import ru.ruscrafting.ranks.contract.ContractPlayerContext
import ru.ruscrafting.ranks.contract.ContractRerollResult
import ru.ruscrafting.ranks.contract.ContractService
import ru.ruscrafting.ranks.domain.NextStep
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.service.RankPlayerService
import ru.ruscrafting.ranks.service.RankPlayerSnapshot
import ru.ruscrafting.ranks.text.RankLocale
import java.util.UUID

class ContractMenu(
    private val settings: () -> ArcRanksSettings,
    private val locale: () -> RankLocale,
    private val players: RankPlayerService,
    private val contracts: ContractService,
    private val tasks: LifecycleTaskScope,
    private val telemetry: ProductTelemetry?,
    private val back: (Player) -> Unit,
) : Listener {
    private data class Loaded(val snapshot: RankPlayerSnapshot, val board: ContractBoard)

    private val items = RankMenuItemFactory { settings().gui.background }

    fun open(player: Player) {
        val holder = ContractMenuHolder(player.uniqueId)
        val inventory = Bukkit.createInventory(holder, INVENTORY_SIZE, locale().render("gui.contracts.title", player))
        holder.menuInventory = inventory
        renderLoading(player, inventory)
        player.openInventory(inventory)
        telemetry?.record(ProductEvent.CONTRACT_BOARD_OPEN, ProductDimension.NONE)
        refresh(player, holder)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? ContractMenuHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory !== event.view.topInventory || holder.actionPending) return
        val player = event.whoClicked as? Player ?: return
        if (holder.playerId != player.uniqueId) return
        when (event.rawSlot) {
            BACK_SLOT -> back(player)
            REFRESH_SLOT -> refresh(player, holder)
            REROLL_SLOT -> reroll(player, holder)
            CLAIM_SLOT -> claim(player, holder)
            in OFFER_SLOTS -> holder.offerIds[event.rawSlot]?.let { accept(player, holder, it) }
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is ContractMenuHolder && event.rawSlots.any { it < INVENTORY_SIZE }) {
            event.isCancelled = true
        }
    }

    private fun refresh(player: Player, holder: ContractMenuHolder) {
        holder.actionPending = true
        holder.generation++
        val generation = holder.generation
        renderLoading(player, holder.menuInventory)
        val future = players.load(player.uniqueId).thenCompose { snapshot ->
            contracts.board(player.uniqueId, snapshot.contractContext()).thenApply { Loaded(snapshot, it) }
        }
        future.whenCompleteSync(tasks) { loaded, failure ->
            if (!holder.current(player, generation)) return@whenCompleteSync
            holder.actionPending = false
            if (failure != null || loaded == null) renderError(player, holder.menuInventory)
            else {
                holder.context = loaded.snapshot.contractContext()
                holder.board = loaded.board
                render(player, holder, loaded.board)
            }
        }
    }

    private fun accept(player: Player, holder: ContractMenuHolder, offerId: ContractId) {
        val context = holder.context ?: return
        runAction(player, holder) { contracts.accept(player.uniqueId, offerId, context) }
    }

    private fun claim(player: Player, holder: ContractMenuHolder) {
        if (holder.board?.active?.completed != true) return
        runAction(player, holder) { contracts.claim(player.uniqueId) }
    }

    private fun reroll(player: Player, holder: ContractMenuHolder) {
        if (holder.board?.rerollAvailable != true) return
        val context = holder.context ?: return
        runAction(player, holder) { contracts.reroll(player.uniqueId, context) }
    }

    private fun <T> runAction(
        player: Player,
        holder: ContractMenuHolder,
        operation: () -> java.util.concurrent.CompletableFuture<T>,
    ) {
        holder.actionPending = true
        holder.generation++
        val generation = holder.generation
        renderRunning(player, holder.menuInventory)
        operation().whenCompleteSync(tasks) { result, failure ->
            if (!holder.current(player, generation)) return@whenCompleteSync
            holder.actionPending = false
            val message = when {
                failure != null || result == null -> "commands.contracts.storage-unavailable"
                result is ContractAcceptResult.Accepted -> "commands.contracts.accepted"
                result is ContractAcceptResult.AlreadyActive -> "commands.contracts.already-active"
                result is ContractAcceptResult.OfferUnavailable -> "commands.contracts.offer-unavailable"
                result is ContractAcceptResult.CycleComplete -> "commands.contracts.cycle-complete"
                result is ContractAcceptResult.StorageUnavailable -> "commands.contracts.storage-unavailable"
                result is ContractClaimResult.Claimed -> "commands.contracts.claimed"
                result is ContractClaimResult.NotReady -> "commands.contracts.not-ready"
                result is ContractClaimResult.AlreadyClaimed -> "commands.contracts.already-claimed"
                result is ContractClaimResult.NoActive -> "commands.contracts.no-active"
                result is ContractClaimResult.StorageUnavailable -> "commands.contracts.storage-unavailable"
                result is ContractRerollResult.Rerolled -> "commands.contracts.rerolled"
                result is ContractRerollResult.Active -> "commands.contracts.already-active"
                result is ContractRerollResult.AlreadyUsed -> "commands.contracts.reroll-used"
                result is ContractRerollResult.CycleComplete -> "commands.contracts.cycle-complete"
                result is ContractRerollResult.StorageUnavailable -> "commands.contracts.storage-unavailable"
                else -> "commands.contracts.storage-unavailable"
            }
            player.sendMessage(locale().render(message, player))
            refresh(player, holder)
        }
    }

    private fun render(player: Player, holder: ContractMenuHolder, board: ContractBoard) {
        val inventory = holder.menuInventory
        items.fill(inventory)
        holder.offerIds.clear()
        val statusValues = mapOf(
            "cycle" to locale().text(board.cycle.start),
            "stamps" to locale().text(board.claimedStamps),
            "remaining" to locale().text(3 - board.claimedStamps),
        )
        inventory.setItem(
            STATUS_SLOT,
            items.item(GuiItemSpec("PAPER", 0), locale().render("gui.contracts.status.name", player), locale().renderLines("gui.contracts.status.lore", player, statusValues)),
        )
        STAMP_LAYOUTS.getValue(board.claimedStamps.coerceIn(0..3)).forEach { slot ->
            inventory.setItem(slot, items.item(GuiItemSpec("HONEYCOMB", 0), locale().render("gui.contracts.stamp.name", player), locale().renderLines("gui.contracts.stamp.lore", player)))
        }
        when {
            board.active != null -> renderActive(player, inventory, board)
            board.offers.isNotEmpty() -> board.offers.forEachIndexed { index, offer ->
                val values = mapOf(
                    "path" to locale().render(offer.path.nameKey(), player),
                    "target" to locale().text(offer.targetDelta),
                    "reward" to locale().text(offer.rewardDelta),
                )
                val slot = OFFER_SLOTS[index]
                holder.offerIds[slot] = offer.id
                inventory.setItem(slot, items.item(settings().gui.contracts, locale().render("gui.contracts.offer.name", player, values), locale().renderLines("gui.contracts.offer.lore", player, values)))
            }
            else -> inventory.setItem(
                EMPTY_SLOT,
                items.item(GuiItemSpec("SUNFLOWER", 0), locale().render("gui.contracts.complete.name", player), locale().renderLines("gui.contracts.complete.lore", player)),
            )
        }
        if (board.rerollAvailable) {
            inventory.setItem(REROLL_SLOT, items.item(GuiItemSpec("AMETHYST_SHARD", 0), locale().render("gui.contracts.reroll.name", player), locale().renderLines("gui.contracts.reroll.lore", player)))
        } else {
            val state = when {
                board.active != null -> "reroll-active"
                board.offers.isEmpty() -> "reroll-complete"
                else -> "reroll-used"
            }
            inventory.setItem(REROLL_SLOT, items.item(GuiItemSpec("GRAY_DYE", 0), locale().render("gui.contracts.$state.name", player), locale().renderLines("gui.contracts.$state.lore", player)))
        }
        renderControls(player, inventory)
    }

    private fun renderActive(player: Player, inventory: Inventory, board: ContractBoard) {
        val active = checkNotNull(board.active)
        val values = mapOf(
            "path" to locale().render(active.path.nameKey(), player),
            "value" to locale().text(active.completedDelta),
            "target" to locale().text(active.targetDelta),
            "reward" to locale().text(active.rewardDelta),
        )
        val state = if (active.completed) "ready" else "active"
        inventory.setItem(ACTIVE_SLOT, items.item(settings().gui.contracts, locale().render("gui.contracts.$state.name", player, values), locale().renderLines("gui.contracts.$state.lore", player, values)))
        inventory.setItem(CLAIM_SLOT, items.item(GuiItemSpec(if (active.completed) "CHEST" else "LIGHT_GRAY_DYE", 0), locale().render("gui.contracts.claim.$state.name", player), locale().renderLines("gui.contracts.claim.$state.lore", player, values)))
    }

    private fun renderLoading(player: Player, inventory: Inventory) {
        items.fill(inventory)
        inventory.setItem(STATUS_SLOT, items.item(settings().gui.contracts, locale().render("gui.contracts.loading.name", player), locale().renderLines("gui.contracts.loading.lore", player)))
        renderControls(player, inventory)
    }

    private fun renderRunning(player: Player, inventory: Inventory) {
        inventory.setItem(STATUS_SLOT, items.item(GuiItemSpec("CLOCK", 0), locale().render("gui.contracts.running.name", player), locale().renderLines("gui.contracts.running.lore", player)))
    }

    private fun renderError(player: Player, inventory: Inventory) {
        items.fill(inventory)
        inventory.setItem(STATUS_SLOT, items.item(GuiItemSpec("BARRIER", 0), locale().render("gui.contracts.error.name", player), locale().renderLines("gui.contracts.error.lore", player)))
        renderControls(player, inventory)
    }

    private fun renderControls(player: Player, inventory: Inventory) {
        inventory.setItem(BACK_SLOT, items.item(GuiItemSpec("ARROW", 0), locale().render("gui.common.back.name", player), locale().renderLines("gui.common.back.lore", player)))
        inventory.setItem(REFRESH_SLOT, items.item(GuiItemSpec("CLOCK", 0), locale().render("gui.common.refresh.name", player), locale().renderLines("gui.common.refresh.lore", player)))
    }

    companion object {
        const val INVENTORY_SIZE = 54
        const val STATUS_SLOT = 4
        val STAMP_SLOTS = listOf(10, 13, 16)
        val STAMP_LAYOUTS = mapOf(
            0 to emptyList(),
            1 to listOf(13),
            2 to listOf(10, 16),
            3 to STAMP_SLOTS,
        )
        val OFFER_SLOTS = listOf(20, 22, 24)
        const val ACTIVE_SLOT = 22
        const val EMPTY_SLOT = 22
        const val CLAIM_SLOT = 31
        const val REROLL_SLOT = 40
        const val BACK_SLOT = 45
        const val REFRESH_SLOT = 53
    }
}

private class ContractMenuHolder(val playerId: UUID) : InventoryHolder {
    lateinit var menuInventory: Inventory
    var generation = 0L
    var actionPending = false
    var context: ContractPlayerContext? = null
    var board: ContractBoard? = null
    val offerIds = mutableMapOf<Int, ContractId>()

    fun current(player: Player, expectedGeneration: Long): Boolean =
        player.isOnline && generation == expectedGeneration && player.openInventory.topInventory === menuInventory

    override fun getInventory(): Inventory = menuInventory
}

private fun RankPlayerSnapshot.contractContext(): ContractPlayerContext = ContractPlayerContext(
    rankOrder = evaluation?.currentRank?.order ?: 1,
    selectedFocus = profile.selectedFocus,
    nearestIncompletePath = (evaluation?.recommendation as? NextStep.PathGoal)?.path,
    progress = profile.progress,
    availability = availability,
    activePerks = activePerks,
)

private fun SpecializationPath.nameKey(): String = "paths.${name.lowercase()}.name"
