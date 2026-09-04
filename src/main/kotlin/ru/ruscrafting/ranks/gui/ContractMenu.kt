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
import ru.ruscrafting.ranks.contract.ContractAcceptResult
import ru.ruscrafting.ranks.contract.ActiveContract
import ru.ruscrafting.ranks.contract.ContractAdminCompleteResult
import ru.ruscrafting.ranks.contract.ContractBoard
import ru.ruscrafting.ranks.contract.ContractClaimResult
import ru.ruscrafting.ranks.contract.ContractId
import ru.ruscrafting.ranks.contract.ContractPlayerContext
import ru.ruscrafting.ranks.contract.ContractRerollResult
import ru.ruscrafting.ranks.contract.ContractRewardDeliveryResult
import ru.ruscrafting.ranks.contract.ContractService
import ru.ruscrafting.ranks.domain.NextStep
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.service.RankPlayerService
import ru.ruscrafting.ranks.service.RankPlayerSnapshot
import ru.ruscrafting.ranks.text.RankLocale
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ContractMenu(
    private val settings: () -> ArcRanksSettings,
    private val locale: () -> RankLocale,
    private val players: RankPlayerService,
    private val contracts: ContractService,
    private val tasks: LifecycleTaskScope,
    private val telemetry: ProductTelemetry?,
    private val back: (Player) -> Unit,
    private val layouts: ArcRanksMenuLayouts,
    private val configGeneration: () -> Long = { 0L },
    private val rewardDelivery: (Player, ActiveContract) -> CompletableFuture<ContractRewardDeliveryResult> = { _, _ ->
        CompletableFuture.completedFuture(ContractRewardDeliveryResult.GRANTED)
    },
) : Listener {
    private data class Loaded(val snapshot: RankPlayerSnapshot, val board: ContractBoard)

    private val items = RankMenuItemFactory { settings().gui.background }

    fun open(player: Player) {
        if (!settings().features.contracts) {
            player.sendMessage(
                locale().render(
                    "commands.feature-disabled",
                    player,
                    mapOf("feature" to locale().render("features.contracts", player)),
                ),
            )
            return
        }
        val holder = ContractMenuHolder(player.uniqueId, configGeneration())
        val inventory = layouts.create(holder, MENU, locale().render("gui.contracts.title", player))
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
            slot("back") -> back(player)
            slot("refresh") -> refresh(player, holder)
            slot("reroll") -> reroll(player, holder)
            slot("admin-complete") -> adminComplete(player, holder)
            in region("cards") -> {
                if (holder.board?.active != null && event.rawSlot == region("cards")[2]) {
                    claim(player, holder)
                } else {
                    holder.offerIds[event.rawSlot]?.let { accept(player, holder, it) }
                }
            }
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is ContractMenuHolder && event.rawSlots.any { it < event.view.topInventory.size }) {
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
        holder.actionPending = true
        holder.generation++
        val generation = holder.generation
        renderRunning(player, holder.menuInventory)
        contracts.claim(player.uniqueId).whenCompleteSync(tasks) { result, failure ->
            if (failure != null || result == null) {
                finishClaim(player, holder, generation, "commands.contracts.storage-unavailable")
                return@whenCompleteSync
            }
            if (result is ContractClaimResult.Claimed) {
                rewardDelivery(player, result.contract).whenCompleteSync(tasks) { delivery, deliveryFailure ->
                    val message = when {
                        deliveryFailure != null || delivery == ContractRewardDeliveryResult.RECOVERY ->
                            "commands.contracts.delivery-recovery"
                        delivery == ContractRewardDeliveryResult.PENDING -> "commands.contracts.delivery-pending"
                        else -> "commands.contracts.claimed"
                    }
                    finishClaim(player, holder, generation, message, claimValues(player, result.contract))
                }
                return@whenCompleteSync
            }
            val message = when (result) {
                is ContractClaimResult.NotReady -> "commands.contracts.not-ready"
                is ContractClaimResult.AlreadyClaimed -> "commands.contracts.already-claimed"
                is ContractClaimResult.NoActive -> "commands.contracts.no-active"
                is ContractClaimResult.StorageUnavailable -> "commands.contracts.storage-unavailable"
                is ContractClaimResult.Claimed -> error("Handled above")
            }
            finishClaim(player, holder, generation, message)
        }
    }

    private fun finishClaim(
        player: Player,
        holder: ContractMenuHolder,
        generation: Long,
        message: String,
        values: Map<String, net.kyori.adventure.text.Component> = emptyMap(),
    ) {
        player.sendMessage(locale().render(message, player, values))
        if (holder.current(player, generation)) {
            holder.actionPending = false
            refresh(player, holder)
        }
    }

    private fun claimValues(player: Player, contract: ActiveContract) = mapOf(
        "reward" to locale().text(contract.rewardDelta),
    ) + rewardValues(player, contract.bonusReward)

    private fun reroll(player: Player, holder: ContractMenuHolder) {
        if (holder.board?.rerollAvailable != true) return
        val context = holder.context ?: return
        runAction(player, holder) { contracts.reroll(player.uniqueId, context) }
    }

    private fun adminComplete(player: Player, holder: ContractMenuHolder) {
        if (!player.hasPermission(ADMIN_CONTRACT_PERMISSION)) return
        if (holder.board?.active?.completed != false) return
        runAction(player, holder) { contracts.adminComplete(player.uniqueId, player.uniqueId.toString()) }
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
                result is ContractAdminCompleteResult.Completed -> "commands.admin.contract-completed"
                result is ContractAdminCompleteResult.AlreadyReady -> "commands.admin.contract-already-ready"
                result is ContractAdminCompleteResult.NoActive -> "commands.admin.contract-no-active"
                result is ContractAdminCompleteResult.StorageUnavailable -> "commands.contracts.storage-unavailable"
                else -> "commands.contracts.storage-unavailable"
            }
            val values = when (result) {
                is ContractAcceptResult.Accepted -> acceptedValues(player, result.contract)
                else -> emptyMap()
            }
            player.sendMessage(locale().render(message, player, values))
            if (holder.current(player, generation)) {
                holder.actionPending = false
                refresh(player, holder)
            }
        }
    }

    private fun render(player: Player, holder: ContractMenuHolder, board: ContractBoard) {
        val inventory = holder.menuInventory
        items.fill(inventory)
        holder.offerIds.clear()
        val statusValues = mapOf(
            "period" to locale().renderWeekPeriod(board.cycle.start, player),
            "stamps" to locale().text(board.claimedStamps),
            "remaining" to locale().text(3 - board.claimedStamps),
        )
        inventory.setItem(
            slot("status"),
            items.item(
                settings().gui.item("contract-status", GuiItemSpec("PAPER", 0)),
                locale().render("gui.contracts.status.name", player),
                locale().renderLines("gui.contracts.status.lore", player, statusValues),
            ),
        )
        stampSlots(board.claimedStamps).forEachIndexed { index, slot ->
            val stampValues = statusValues + mapOf(
                "contract-number" to locale().text(index + 1),
            )
            inventory.setItem(
                slot,
                items.item(
                    settings().gui.item("contract-stamp", GuiItemSpec("HONEYCOMB", 0)),
                    locale().render("gui.contracts.stamp.name", player, stampValues),
                    locale().renderLines("gui.contracts.stamp.lore", player, stampValues),
                ),
            )
        }
        when {
            board.active != null -> renderActive(player, inventory, board)
            board.offers.isNotEmpty() -> board.offers.forEachIndexed { index, offer ->
                val values = mapOf(
                    "path" to locale().render(offer.path.nameKey(), player),
                    "target" to locale().text(offer.targetDelta),
                    "reward" to locale().text(offer.rewardDelta),
                ) + actionValues(player, offer.path, offer.targetDelta) + rewardValues(player, offer.bonusReward)
                val slot = region("cards")[index]
                holder.offerIds[slot] = offer.id
                inventory.setItem(slot, items.item(settings().gui.contracts, locale().render("gui.contracts.offer.name", player, values), locale().renderLines("gui.contracts.offer.lore", player, values)))
            }
            else -> inventory.setItem(
                region("cards")[1],
                items.item(
                    settings().gui.item("contract-complete", GuiItemSpec("SUNFLOWER", 0)),
                    locale().render("gui.contracts.complete.name", player),
                    locale().renderLines("gui.contracts.complete.lore", player),
                ),
            )
        }
        if (board.active == null && board.offers.isNotEmpty() && board.rerollAvailable) {
            inventory.setItem(
                slot("reroll"),
                items.item(
                    settings().gui.item("contract-reroll", GuiItemSpec("AMETHYST_SHARD", 0)),
                    locale().render("gui.contracts.reroll.name", player),
                    locale().renderLines("gui.contracts.reroll.lore", player),
                ),
            )
        } else if (board.active == null && board.offers.isNotEmpty()) {
            inventory.setItem(
                slot("reroll"),
                items.item(
                    settings().gui.item("contract-disabled", GuiItemSpec("GRAY_DYE", 0)),
                    locale().render("gui.contracts.reroll-used.name", player),
                    locale().renderLines("gui.contracts.reroll-used.lore", player),
                ),
            )
        }
        renderControls(player, inventory)
        renderAdminControl(player, inventory, board)
    }

    private fun renderActive(player: Player, inventory: Inventory, board: ContractBoard) {
        val active = checkNotNull(board.active)
        val values = mapOf(
            "path" to locale().render(active.path.nameKey(), player),
            "value" to locale().text(active.completedDelta),
            "target" to locale().text(active.targetDelta),
            "reward" to locale().text(active.rewardDelta),
            "remaining" to locale().text((active.targetDelta - active.completedDelta).coerceAtLeast(0)),
            "progress-bar" to ContractProgressBar.render(active.completedDelta, active.targetDelta),
        ) + actionValues(player, active.path, active.targetDelta) + rewardValues(player, active.bonusReward)
        val state = if (active.completed) "ready" else "active"
        inventory.setItem(
            region("cards")[0],
            items.item(
                settings().gui.contracts,
                locale().render("gui.contracts.$state.name", player, values),
                locale().renderLines("gui.contracts.$state.lore", player, values),
            ),
        )
        inventory.setItem(
            region("cards")[1],
            items.item(
                settings().gui.item("contract-progress", GuiItemSpec("COMPASS", 0)),
                locale().render("gui.contracts.progress.$state.name", player, values),
                locale().renderLines("gui.contracts.progress.$state.lore", player, values),
            ),
        )
        val claimItem = if (active.completed) {
            settings().gui.item("contract-claim-ready", GuiItemSpec("CHEST", 0))
        } else {
            settings().gui.item("contract-claim-active", GuiItemSpec("LIGHT_GRAY_DYE", 0))
        }
        inventory.setItem(
            region("cards")[2],
            items.item(
                claimItem,
                locale().render("gui.contracts.claim.$state.name", player),
                locale().renderLines("gui.contracts.claim.$state.lore", player, values),
            ),
        )
    }

    private fun actionValues(
        player: Player,
        path: SpecializationPath,
        target: Long,
    ): Map<String, net.kyori.adventure.text.Component> {
        val prefix = "gui.contracts.actions.${path.name.lowercase()}"
        val nested = mapOf("target" to locale().text(target))
        return mapOf(
            "goal" to locale().render("$prefix.goal", player, nested),
            "action-first" to locale().render("$prefix.first", player, nested),
            "action-second" to locale().render("$prefix.second", player, nested),
            "action-third" to locale().render("$prefix.third", player, nested),
        )
    }

    private fun acceptedValues(
        player: Player,
        contract: ActiveContract,
    ): Map<String, net.kyori.adventure.text.Component> = mapOf(
        "path" to locale().render(contract.path.nameKey(), player),
        "target" to locale().text(contract.targetDelta),
        "reward" to locale().text(contract.rewardDelta),
    ) + actionValues(player, contract.path, contract.targetDelta) + rewardValues(player, contract.bonusReward)

    private fun rewardValues(
        player: Player,
        reward: ru.ruscrafting.ranks.contract.ContractBonusReward,
    ): Map<String, net.kyori.adventure.text.Component> = mapOf(
        "money" to locale().text(reward.money),
        "tokens" to locale().text(reward.tokens),
        "item-amount" to locale().text(reward.itemAmount),
        "item" to locale().render("gui.contracts.rewards.items.${reward.itemPreset}", player),
    )

    private fun renderAdminControl(player: Player, inventory: Inventory, board: ContractBoard) {
        if (!player.hasPermission(ADMIN_CONTRACT_PERMISSION)) return
        val active = board.active ?: return
        val state = if (active.completed) "ready" else "complete"
        inventory.setItem(
            slot("admin-complete"),
            items.item(
                settings().gui.item("contract-admin-complete", GuiItemSpec("COMMAND_BLOCK", 0)),
                locale().render("gui.contracts.admin.$state.name", player),
                locale().renderLines("gui.contracts.admin.$state.lore", player),
            ),
        )
    }

    private fun renderLoading(player: Player, inventory: Inventory) {
        items.fill(inventory)
        inventory.setItem(
            slot("status"),
            items.item(
                settings().gui.item("loading", settings().gui.contracts),
                locale().render("gui.contracts.loading.name", player),
                locale().renderLines("gui.contracts.loading.lore", player),
            ),
        )
        renderControls(player, inventory)
    }

    private fun renderRunning(player: Player, inventory: Inventory) {
        inventory.setItem(
            slot("status"),
            items.item(
                settings().gui.item("running", GuiItemSpec("CLOCK", 0)),
                locale().render("gui.contracts.running.name", player),
                locale().renderLines("gui.contracts.running.lore", player),
            ),
        )
    }

    private fun renderError(player: Player, inventory: Inventory) {
        items.fill(inventory)
        inventory.setItem(
            slot("status"),
            items.item(
                settings().gui.item("error", GuiItemSpec("RED_STAINED_GLASS_PANE", 0)),
                locale().render("gui.contracts.error.name", player),
                locale().renderLines("gui.contracts.error.lore", player),
            ),
        )
        renderControls(player, inventory)
    }

    private fun renderControls(player: Player, inventory: Inventory) {
        inventory.setItem(slot("back"), items.item(settings().gui.back, locale().render("gui.common.back.name", player), locale().renderLines("gui.common.back.lore", player)))
        inventory.setItem(
            slot("refresh"),
            items.item(
                settings().gui.item("refresh", GuiItemSpec("CLOCK", 0)),
                locale().render("gui.common.refresh.name", player),
                locale().renderLines("gui.common.refresh.lore", player),
            ),
        )
    }

    companion object {
        val MENU = ArcRanksMenuLayouts.CONTRACTS
        const val ADMIN_CONTRACT_PERMISSION = "arcranks.admin.contract"
    }

    private fun slot(id: String): Int = layouts.slot(MENU, id)

    private fun region(id: String): List<Int> = layouts.region(MENU, id)

    private fun stampSlots(claimed: Int): List<Int> {
        val slots = region("stamps")
        return when (claimed.coerceIn(0..3)) {
            0 -> emptyList()
            1 -> listOf(slots[1])
            2 -> listOf(slots.first(), slots.last())
            else -> slots
        }
    }
}

private class ContractMenuHolder(
    override val playerId: UUID,
    override val configGeneration: Long,
) : ArcRanksInventoryHolder {
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
