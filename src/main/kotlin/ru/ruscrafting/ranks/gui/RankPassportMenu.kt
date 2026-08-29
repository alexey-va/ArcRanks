package ru.ruscrafting.ranks.gui

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import ru.ruscrafting.ranks.analytics.PlayerSignal
import ru.ruscrafting.ranks.analytics.ProductDimension
import ru.ruscrafting.ranks.analytics.ProductEvent
import ru.ruscrafting.ranks.analytics.ProductTelemetry
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.config.GuiItemSpec
import ru.ruscrafting.ranks.config.PromotionMode
import ru.ruscrafting.ranks.domain.GoalState
import ru.ruscrafting.ranks.domain.MasteryLevel
import ru.ruscrafting.ranks.domain.NextStep
import ru.ruscrafting.ranks.domain.RankCatalog
import ru.ruscrafting.ranks.domain.RankEligibility
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.promotion.PromotionResult
import ru.ruscrafting.ranks.promotion.PromotionService
import ru.ruscrafting.ranks.rankstate.RankState
import ru.ruscrafting.ranks.service.RankPlayerService
import ru.ruscrafting.ranks.service.RankPlayerSnapshot
import ru.ruscrafting.ranks.text.RankLocale
import java.util.UUID

class RankPassportMenu(
    private val settings: () -> ArcRanksSettings,
    private val catalog: () -> RankCatalog,
    private val locale: () -> RankLocale,
    private val players: RankPlayerService,
    private val promotions: PromotionService,
    private val tasks: LifecycleTaskScope,
    private val telemetry: ProductTelemetry? = null,
    private val openContracts: (Player) -> Unit = {},
    private val openPerks: (Player) -> Unit = {},
) : Listener {
    private val items = RankMenuItemFactory { settings().gui.background }

    fun open(player: Player) {
        openView(player, RankMenuView.OVERVIEW, snapshot = null, recordOpen = true)
    }

    private fun openView(
        player: Player,
        view: RankMenuView,
        snapshot: RankPlayerSnapshot?,
        recordOpen: Boolean,
    ) {
        val holder = RankPassportHolder(player.uniqueId, view)
        val inventory = Bukkit.createInventory(holder, view.inventorySize(), locale().render(view.titleKey(), player))
        holder.menuInventory = inventory
        if (snapshot == null) {
            renderLoading(player, holder)
        } else {
            holder.snapshot = snapshot
            render(player, holder, snapshot)
        }
        player.openInventory(inventory)
        if (recordOpen) {
            telemetry?.record(ProductEvent.PASSPORT_OPEN, ProductDimension("entry:menu"))
            telemetry?.recordPlayer(player.uniqueId, PlayerSignal.PASSPORT_OPENED)
        }
        if (snapshot == null) refresh(player, holder)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? RankPassportHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory !== event.view.topInventory) return
        val player = event.whoClicked as? Player ?: return
        if (holder.playerId != player.uniqueId) return
        if (!holder.ready) {
            if (holder.view == RankMenuView.PATHS && event.rawSlot == PATH_BACK_SLOT) {
                openView(player, RankMenuView.OVERVIEW, holder.snapshot, recordOpen = false)
            } else if (event.rawSlot == holder.view.statusSlot()) {
                refresh(player, holder)
            }
            return
        }
        when (holder.view) {
            RankMenuView.OVERVIEW -> when (event.rawSlot) {
                PROFILE_SLOT -> refresh(player, holder)
                PROMOTION_SLOT -> promote(player, holder)
                CONTRACTS_SLOT -> openContracts(player)
                PATHS_SLOT -> openView(player, RankMenuView.PATHS, holder.snapshot, recordOpen = false)
                PERKS_SLOT -> openPerks(player)
            }
            RankMenuView.PATHS -> when (event.rawSlot) {
                PROFILE_SLOT -> refresh(player, holder)
                PATH_BACK_SLOT -> openView(player, RankMenuView.OVERVIEW, holder.snapshot, recordOpen = false)
                in PATH_SLOTS -> {
                    val path = SpecializationPath.entries.getOrNull(PATH_SLOTS.indexOf(event.rawSlot)) ?: return
                    selectFocus(player, holder, path)
                }
            }
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is RankPassportHolder && event.rawSlots.any { it < event.view.topInventory.size }) {
            event.isCancelled = true
        }
    }

    private fun refresh(player: Player, holder: RankPassportHolder) {
        holder.ready = false
        renderLoading(player, holder)
        val token = tasks.token()
        players.load(player.uniqueId).whenCompleteSync(tasks, token) { snapshot, failure ->
            if (!player.isOnline || player.openInventory.topInventory !== holder.menuInventory) return@whenCompleteSync
            if (failure != null || snapshot == null) renderError(player, holder)
            else {
                holder.snapshot = snapshot
                render(player, holder, snapshot)
            }
        }
    }

    private fun selectFocus(player: Player, holder: RankPassportHolder, path: SpecializationPath) {
        val token = tasks.token()
        players.selectFocus(player.uniqueId, path).whenCompleteSync(tasks, token) { snapshot, failure ->
            if (failure != null || snapshot == null) {
                player.sendMessage(locale().render("commands.storage-unavailable", player))
                return@whenCompleteSync
            }
            holder.snapshot = snapshot
            if (player.openInventory.topInventory === holder.menuInventory) render(player, holder, snapshot)
            player.sendMessage(
                locale().render(
                    "commands.focus.selected",
                    player,
                    mapOf("path" to locale().render(path.nameKey(), player)),
                ),
            )
        }
    }

    private fun promote(player: Player, holder: RankPassportHolder) {
        if (settings().promotionMode == PromotionMode.SHADOW) {
            player.sendMessage(locale().render("commands.shadow-mode", player))
            return
        }
        renderPromotionRunning(player, holder.menuInventory)
        val token = tasks.token()
        promotions.promote(player.uniqueId).whenCompleteSync(tasks, token) { result, failure ->
            if (failure != null || result == null) {
                player.sendMessage(locale().render("commands.storage-unavailable", player))
            } else {
                sendPromotionResult(player, result)
            }
            if (player.isOnline && player.openInventory.topInventory === holder.menuInventory) refresh(player, holder)
        }
    }

    private fun sendPromotionResult(player: Player, result: PromotionResult) {
        val path = when (result) {
            is PromotionResult.Promoted, is PromotionResult.Recovered -> "commands.promotion.success"
            is PromotionResult.NotEligible -> "commands.promotion.not-eligible"
            is PromotionResult.RankStateProblem -> when (result.state) {
                RankState.Missing -> "commands.rank-state.missing"
                is RankState.Conflict -> "commands.rank-state.conflict"
                is RankState.Unknown -> "commands.rank-state.unknown"
                is RankState.Exact -> "commands.promotion.retryable"
            }
            PromotionResult.TopRank -> "commands.why.top"
            PromotionResult.Busy -> "commands.promotion.busy"
            PromotionResult.Retryable -> "commands.promotion.retryable"
        }
        val rankId = when (result) {
            is PromotionResult.Promoted -> result.rankId
            is PromotionResult.Recovered -> result.rankId
            else -> null
        }
        val values = rankId?.let { mapOf("rank" to locale().render(catalog().require(it).displayNameKey, player)) }.orEmpty()
        player.sendMessage(locale().render(path, player, values))
    }

    private fun renderLoading(player: Player, holder: RankPassportHolder) {
        val inventory = holder.menuInventory
        items.fill(inventory)
        inventory.setItem(
            holder.view.statusSlot(),
            item(settings().gui.rankCurrent, locale().render("gui.state.loading.name", player), locale().renderLines("gui.state.loading.lore", player)),
        )
        if (holder.view == RankMenuView.PATHS) renderPathBack(player, inventory)
    }

    private fun renderError(player: Player, holder: RankPassportHolder) {
        val inventory = holder.menuInventory
        items.fill(inventory)
        inventory.setItem(
            holder.view.statusSlot(),
            item(GuiItemSpec("BARRIER", 0), locale().render("gui.state.error.name", player), locale().renderLines("gui.state.error.lore", player)),
        )
        if (holder.view == RankMenuView.PATHS) renderPathBack(player, inventory)
    }

    private fun render(player: Player, holder: RankPassportHolder, snapshot: RankPlayerSnapshot) {
        val inventory = holder.menuInventory
        items.fill(inventory)
        val exact = snapshot.rankState as? RankState.Exact
        val evaluation = snapshot.evaluation
        if (exact == null || evaluation == null) {
            holder.ready = false
            val key = when (snapshot.rankState) {
                RankState.Missing -> "commands.rank-state.missing"
                is RankState.Conflict -> "commands.rank-state.conflict"
                else -> "commands.rank-state.unknown"
            }
            inventory.setItem(holder.view.statusSlot(), item(GuiItemSpec("BARRIER", 0), locale().render("gui.state.error.name", player), listOf(locale().render(key, player))))
            if (holder.view == RankMenuView.PATHS) renderPathBack(player, inventory)
            return
        }
        when (holder.view) {
            RankMenuView.OVERVIEW -> renderOverview(player, inventory, snapshot, exact)
            RankMenuView.PATHS -> renderPaths(player, inventory, snapshot)
        }
        holder.ready = true
    }

    private fun renderOverview(
        player: Player,
        inventory: Inventory,
        snapshot: RankPlayerSnapshot,
        exact: RankState.Exact,
    ) {
        val current = catalog().require(exact.rankId)
        renderProfile(player, inventory, snapshot, current.displayNameKey)
        catalog().ranks.forEachIndexed { index, rank ->
            val (state, spec) = when {
                rank.order < current.order -> "completed" to settings().gui.rankCompleted
                rank.order == current.order -> "current" to settings().gui.rankCurrent
                rank.order == current.order + 1 -> "next" to settings().gui.rankNext
                else -> "locked" to settings().gui.background
            }
            val values = mapOf("rank" to locale().render(rank.displayNameKey, player))
            inventory.setItem(RANK_SLOTS[index], item(spec, locale().render("gui.rank.$state.name", player, values), locale().renderLines("gui.rank.$state.lore", player, values)))
        }
        renderPromotion(player, inventory, snapshot)
        renderNavigation(player, inventory)
    }

    private fun renderPaths(player: Player, inventory: Inventory, snapshot: RankPlayerSnapshot) {
        val exact = snapshot.rankState as RankState.Exact
        renderProfile(player, inventory, snapshot, catalog().require(exact.rankId).displayNameKey)
        val evaluation = checkNotNull(snapshot.evaluation)
        SpecializationPath.entries.forEachIndexed { index, path ->
            val goal = evaluation.goals.firstOrNull { it.path == path }
            val state = when {
                !snapshot.availability.isAvailable(path) -> "unavailable"
                snapshot.profile.selectedFocus == path -> "selected"
                goal?.state == GoalState.COMPLETE -> "complete"
                else -> "available"
            }
            val values = mapOf(
                "path" to locale().render(path.nameKey(), player),
                "summary" to locale().render(path.summaryKey(), player),
                "value" to locale().text(snapshot.profile.progress.value(path.metric)),
                "goal" to locale().text(goal?.required ?: snapshot.profile.progress.value(path.metric)),
                "mastery" to locale().render(snapshot.mastery.getValue(path).localeKey(), player),
            )
            inventory.setItem(
                PATH_SLOTS[index],
                item(PATH_ITEMS.getValue(path), locale().render("gui.path.$state.name", player, values), locale().renderLines("gui.path.$state.lore", player, values)),
            )
        }
        inventory.setItem(
            PATH_GUIDE_SLOT,
            item(
                GuiItemSpec("MAP", 0),
                locale().render("gui.passport.guide.name", player),
                locale().renderLines("gui.passport.guide.lore", player),
            ),
        )
        renderPathBack(player, inventory)
    }

    private fun renderProfile(
        player: Player,
        inventory: Inventory,
        snapshot: RankPlayerSnapshot,
        displayNameKey: String,
    ) {
        inventory.setItem(
            PROFILE_SLOT,
            item(
                settings().gui.rankCurrent,
                locale().render("gui.profile.name", player, mapOf("rank" to locale().render(displayNameKey, player))),
                locale().renderLines("gui.profile.lore", player, mapOf("focus" to locale().render(snapshot.profile.selectedFocus.nameKey(), player))),
            ),
        )
    }

    private fun renderPromotion(player: Player, inventory: Inventory, snapshot: RankPlayerSnapshot) {
        val evaluation = snapshot.evaluation ?: return
        val state = when (evaluation.eligibility) {
            RankEligibility.READY -> "ready"
            RankEligibility.TOP_RANK -> "top"
            else -> "blocked"
        }
        val nextName = evaluation.nextRank?.let { locale().render(it.displayNameKey, player) } ?: Component.empty()
        val reason = recommendation(player, snapshot)
        val benefits = evaluation.nextRank?.benefitKeys.orEmpty().map { locale().render(it, player) }
        val values = mapOf(
            "rank" to nextName,
            "reason" to reason,
            "benefit-one" to benefits.getOrElse(0) { Component.empty() },
            "benefit-two" to benefits.getOrElse(1) { Component.empty() },
        )
        val dimension = when (val next = evaluation.recommendation) {
            is NextStep.ActiveMinutes -> "active"
            is NextStep.PathGoal -> "path:${next.path.name.lowercase()}"
            null -> if (evaluation.eligibility == RankEligibility.TOP_RANK) "top" else "ready"
        }
        telemetry?.record(ProductEvent.RECOMMENDATION_SHOWN, ProductDimension(dimension))
        inventory.setItem(PROMOTION_SLOT, item(settings().gui.promotion, locale().render("gui.promotion.$state.name", player, values), locale().renderLines("gui.promotion.$state.lore", player, values)))
    }

    private fun renderPromotionRunning(player: Player, inventory: Inventory) {
        inventory.setItem(PROMOTION_SLOT, item(settings().gui.promotion, locale().render("gui.promotion.running.name", player), locale().renderLines("gui.promotion.running.lore", player)))
    }

    private fun renderNavigation(player: Player, inventory: Inventory) {
        inventory.setItem(
            CONTRACTS_SLOT,
            item(settings().gui.contracts, locale().render("gui.passport.contracts.name", player), locale().renderLines("gui.passport.contracts.lore", player)),
        )
        inventory.setItem(
            PATHS_SLOT,
            item(PATHS_ITEM, locale().render("gui.passport.paths.name", player), locale().renderLines("gui.passport.paths.lore", player)),
        )
        inventory.setItem(
            PERKS_SLOT,
            item(settings().gui.perks, locale().render("gui.passport.perks.name", player), locale().renderLines("gui.passport.perks.lore", player)),
        )
    }

    private fun renderPathBack(player: Player, inventory: Inventory) {
        inventory.setItem(
            PATH_BACK_SLOT,
            item(GuiItemSpec("ARROW", 0), locale().render("gui.common.back.name", player), locale().renderLines("gui.common.back.lore", player)),
        )
    }

    private fun item(spec: GuiItemSpec, name: Component, lore: List<Component>) = items.item(spec, name, lore)

    private fun readyRankValues(player: Player, snapshot: RankPlayerSnapshot): Map<String, Component> =
        snapshot.evaluation?.nextRank?.let { mapOf("rank" to locale().render(it.displayNameKey, player)) }.orEmpty()

    private fun recommendation(player: Player, snapshot: RankPlayerSnapshot): Component =
        when (val next = snapshot.evaluation?.recommendation) {
            is NextStep.ActiveMinutes -> locale().render(
                "gui.recommendation.active",
                player,
                mapOf("remaining" to locale().text(next.remaining)),
            )
            is NextStep.PathGoal -> locale().render(
                "gui.recommendation.path",
                player,
                mapOf(
                    "path" to locale().render(next.path.nameKey(), player),
                    "remaining" to locale().text(next.remaining),
                ),
            )
            null -> locale().render(
                if (snapshot.evaluation?.eligibility == RankEligibility.TOP_RANK) {
                    "gui.recommendation.top"
                } else {
                    "gui.recommendation.ready"
                },
                player,
                readyRankValues(player, snapshot),
            )
        }

    companion object {
        const val INVENTORY_SIZE = 36
        const val PATH_INVENTORY_SIZE = 27
        const val PROFILE_SLOT = 4
        val RANK_SLOTS = (9..17).toList()
        const val CONTRACTS_SLOT = 21
        const val PATHS_SLOT = 22
        const val PERKS_SLOT = 23
        const val PROMOTION_SLOT = 31
        val PATH_SLOTS = listOf(10, 11, 12, 14, 15, 16)
        const val PATH_GUIDE_SLOT = 13
        const val PATH_BACK_SLOT = 22
        val PATHS_ITEM = GuiItemSpec("COMPASS", 0)
        val PATH_ITEMS = mapOf(
            SpecializationPath.FARMING to GuiItemSpec("WHEAT", 0),
            SpecializationPath.INDUSTRY to GuiItemSpec("BLAST_FURNACE", 0),
            SpecializationPath.TRADE to GuiItemSpec("EMERALD", 0),
            SpecializationPath.EXPLORATION to GuiItemSpec("COMPASS", 0),
            SpecializationPath.BUILDING to GuiItemSpec("BRICKS", 0),
            SpecializationPath.COMMUNITY to GuiItemSpec("CAMPFIRE", 0),
        )
    }
}

private enum class RankMenuView {
    OVERVIEW,
    PATHS,
}

private class RankPassportHolder(val playerId: UUID, val view: RankMenuView) : InventoryHolder {
    lateinit var menuInventory: Inventory
    var snapshot: RankPlayerSnapshot? = null
    var ready: Boolean = false

    override fun getInventory(): Inventory = menuInventory
}

private fun RankMenuView.inventorySize(): Int = when (this) {
    RankMenuView.OVERVIEW -> RankPassportMenu.INVENTORY_SIZE
    RankMenuView.PATHS -> RankPassportMenu.PATH_INVENTORY_SIZE
}

private fun RankMenuView.titleKey(): String = when (this) {
    RankMenuView.OVERVIEW -> "gui.title"
    RankMenuView.PATHS -> "gui.paths.title"
}

private fun RankMenuView.statusSlot(): Int = when (this) {
    RankMenuView.OVERVIEW -> RankPassportMenu.PROMOTION_SLOT
    RankMenuView.PATHS -> RankPassportMenu.PATH_GUIDE_SLOT
}

private fun SpecializationPath.nameKey(): String = "paths.${name.lowercase()}.name"

private fun SpecializationPath.summaryKey(): String = "paths.${name.lowercase()}.summary"

private fun MasteryLevel.localeKey(): String = "mastery.${name.lowercase()}"
