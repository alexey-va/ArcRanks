package ru.ruscrafting.ranks.gui

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import ru.ruscrafting.ranks.analytics.PlayerSignal
import ru.ruscrafting.ranks.analytics.ProductDimension
import ru.ruscrafting.ranks.analytics.ProductEvent
import ru.ruscrafting.ranks.analytics.ProductTelemetry
import ru.ruscrafting.ranks.admin.AdminProgressAdvanceResult
import ru.ruscrafting.ranks.admin.AdminProgressService
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.config.GuiItemSpec
import ru.ruscrafting.ranks.config.PromotionMode
import ru.ruscrafting.ranks.domain.GoalState
import ru.ruscrafting.ranks.domain.MasteryLevel
import ru.ruscrafting.ranks.domain.NextStep
import ru.ruscrafting.ranks.domain.RankCatalog
import ru.ruscrafting.ranks.domain.RankDefinition
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
    private val adminProgress: AdminProgressService,
    private val tasks: LifecycleTaskScope,
    private val telemetry: ProductTelemetry? = null,
    private val openDailyQuests: (Player) -> Unit = {},
    private val openContracts: (Player) -> Unit = {},
    private val openPerks: (Player) -> Unit = {},
    private val openWeeklyKit: (Player) -> Unit = {},
    private val openAnalytics: (Player) -> Unit = {},
    private val layouts: ArcRanksMenuLayouts,
    private val configGeneration: () -> Long = { 0L },
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
        val holder = RankPassportHolder(player.uniqueId, configGeneration(), view)
        val inventory = layouts.create(holder, menu(view), locale().render(view.titleKey(), player))
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
        if (holder.actionPending) return
        if (!holder.ready) {
            if (holder.view == RankMenuView.PATHS && event.rawSlot == slot(holder.view, "back")) {
                openView(player, RankMenuView.OVERVIEW, holder.snapshot, recordOpen = false)
            } else if (event.rawSlot == slot(holder.view, statusElement(holder.view))) {
                refresh(player, holder)
            }
            return
        }
        when (holder.view) {
            RankMenuView.OVERVIEW -> when (event.rawSlot) {
                slot(holder.view, "profile") -> refresh(player, holder)
                slot(holder.view, "promotion") -> promote(player, holder)
                slot(holder.view, "daily-quests") -> openDailyQuests(player)
                slot(holder.view, "contracts") -> openContracts(player)
                slot(holder.view, "paths") -> openView(player, RankMenuView.PATHS, holder.snapshot, recordOpen = false)
                slot(holder.view, "perks") -> openPerks(player)
                slot(holder.view, "weekly-kit") -> openWeeklyKit(player)
                slot(holder.view, "admin-advance") -> advanceAdminStep(player, holder)
                slot(holder.view, "admin-analytics") -> if (player.hasPermission(ADMIN_ANALYTICS_PERMISSION)) openAnalytics(player)
            }
            RankMenuView.PATHS -> when (event.rawSlot) {
                slot(holder.view, "back") -> openView(player, RankMenuView.OVERVIEW, holder.snapshot, recordOpen = false)
                in region(holder.view, "paths") -> {
                    val configured = region(holder.view, "paths")
                    val path = SpecializationPath.entries.getOrNull(configured.indexOf(event.rawSlot)) ?: return
                    val snapshot = holder.snapshot ?: return
                    if (!snapshot.availability.isAvailable(path) || snapshot.profile.selectedFocus == path) return
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

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        (event.inventory.holder as? RankPassportHolder)?.invalidateFeedback()
    }

    private fun refresh(player: Player, holder: RankPassportHolder) {
        holder.invalidateFeedback()
        holder.actionPending = false
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
        holder.actionPending = true
        val token = tasks.token()
        players.selectFocus(player.uniqueId, path).whenCompleteSync(tasks, token) { snapshot, failure ->
            if (failure != null || snapshot == null) {
                player.sendMessage(locale().render("commands.storage-unavailable", player))
            } else {
                player.sendMessage(
                    locale().render(
                        "commands.focus.selected",
                        player,
                        mapOf("path" to locale().render(path.nameKey(), player)),
                    ),
                )
            }
            if (player.openInventory.topInventory === holder.menuInventory) {
                holder.actionPending = false
                if (failure == null && snapshot != null) {
                    holder.snapshot = snapshot
                    render(player, holder, snapshot)
                }
            }
        }
    }

    private fun promote(player: Player, holder: RankPassportHolder) {
        if (!player.hasPermission("arcranks.rankup")) {
            player.sendMessage(locale().render("commands.no-permission", player))
            return
        }
        if (settings().promotionMode == PromotionMode.SHADOW) {
            player.sendMessage(locale().render("commands.shadow-mode", player))
            return
        }
        val snapshot = holder.snapshot ?: return
        if (snapshot.evaluation?.eligibility != RankEligibility.READY) {
            renderPromotionBlockedFeedback(player, holder, snapshot)
            return
        }
        holder.invalidateFeedback()
        holder.actionPending = true
        holder.ready = false
        renderPromotionRunning(player, holder.menuInventory)
        val token = tasks.token()
        promotions.promote(player.uniqueId).whenCompleteSync(tasks, token) { result, failure ->
            if (failure != null || result == null) {
                player.sendMessage(locale().render("commands.storage-unavailable", player))
                holder.actionPending = false
                if (player.isOnline && player.openInventory.topInventory === holder.menuInventory) refresh(player, holder)
            } else if (result is PromotionResult.NotEligible) {
                holder.actionPending = false
                holder.ready = true
                if (player.openInventory.topInventory === holder.menuInventory) {
                    holder.snapshot?.let { renderPromotionBlockedFeedback(player, holder, it) }
                }
            } else {
                sendPromotionResult(player, result)
                holder.actionPending = false
                if (player.isOnline && player.openInventory.topInventory === holder.menuInventory) refresh(player, holder)
            }
        }
    }

    private fun advanceAdminStep(player: Player, holder: RankPassportHolder) {
        if (!player.hasPermission(ADMIN_GRANT_PERMISSION)) return
        val evaluation = holder.snapshot?.evaluation ?: return
        holder.actionPending = true
        val token = tasks.token()
        adminProgress.advance(player.uniqueId, evaluation).whenCompleteSync(tasks, token) { result, failure ->
            if (!player.isOnline) return@whenCompleteSync
            val key = when {
                failure != null || result == null -> "commands.storage-unavailable"
                result is AdminProgressAdvanceResult.Applied -> "commands.admin.advance-applied"
                result == AdminProgressAdvanceResult.Duplicate -> "commands.admin.advance-duplicate"
                result == AdminProgressAdvanceResult.TopRank -> "commands.admin.advance-top"
                else -> "commands.admin.advance-ready"
            }
            val values = buildMap {
                put("player", locale().text(player.name))
                if (result is AdminProgressAdvanceResult.Applied) put("amount", locale().text(result.amount))
            }
            player.sendMessage(locale().render(key, player, values))
            if (player.openInventory.topInventory === holder.menuInventory) {
                holder.actionPending = false
                refresh(player, holder)
            }
        }
    }

    private fun renderPromotionBlockedFeedback(
        player: Player,
        holder: RankPassportHolder,
        snapshot: RankPlayerSnapshot,
    ) {
        val evaluation = snapshot.evaluation ?: return
        val rank = evaluation.nextRank?.let { locale().render(it.displayNameKey, player) } ?: Component.empty()
        val values = mapOf(
            "rank" to rank,
            "reason" to recommendation(player, snapshot),
        )
        val feedback = holder.beginFeedback()
        holder.menuInventory.setItem(
            slot(RankMenuView.OVERVIEW, "promotion"),
            item(
                settings().gui.item("error", ERROR_ITEM),
                locale().render("gui.promotion.feedback.blocked.name", player, values),
                locale().renderLines("gui.promotion.feedback.blocked.lore", player, values),
            ),
        )
        val feedbackTicks = settings().gui.promotionBlockedTicks
        tasks.runLater(feedbackTicks) {
            if (!holder.consumeFeedback(feedback)) return@runLater
            if (!player.isOnline || player.openInventory.topInventory !== holder.menuInventory) return@runLater
            holder.snapshot?.let { renderPromotion(player, holder.menuInventory, it) }
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
        holder.invalidateFeedback()
        val inventory = holder.menuInventory
        items.fill(inventory)
        inventory.setItem(
            slot(holder.view, statusElement(holder.view)),
            item(
                settings().gui.item("loading", settings().gui.rankCurrent),
                locale().render("gui.state.loading.name", player),
                locale().renderLines("gui.state.loading.lore", player),
            ),
        )
        if (holder.view == RankMenuView.PATHS) renderPathBack(player, inventory)
    }

    private fun renderError(player: Player, holder: RankPassportHolder) {
        holder.invalidateFeedback()
        val inventory = holder.menuInventory
        items.fill(inventory)
        inventory.setItem(
            slot(holder.view, statusElement(holder.view)),
            item(
                settings().gui.item("error", ERROR_ITEM),
                locale().render("gui.state.error.name", player),
                locale().renderLines("gui.state.error.lore", player),
            ),
        )
        if (holder.view == RankMenuView.PATHS) renderPathBack(player, inventory)
    }

    private fun render(player: Player, holder: RankPassportHolder, snapshot: RankPlayerSnapshot) {
        holder.invalidateFeedback()
        val inventory = holder.menuInventory
        items.fill(inventory)
        val exact = snapshot.rankState as? RankState.Exact
        val evaluation = snapshot.evaluation
        if (exact == null || evaluation == null) {
            holder.ready = false
            val state = when (snapshot.rankState) {
                RankState.Missing -> "missing"
                is RankState.Conflict -> "conflict"
                else -> "unknown"
            }
            inventory.setItem(
                slot(holder.view, statusElement(holder.view)),
                item(
                    settings().gui.item("error", ERROR_ITEM),
                    locale().render("gui.state.rank-$state.name", player),
                    locale().renderLines("gui.state.rank-$state.lore", player),
                ),
            )
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
        renderProfile(player, inventory, snapshot, current, RankMenuView.OVERVIEW)
        catalog().ranks.forEachIndexed { index, rank ->
            val (state, spec) = when {
                rank.order < current.order -> "completed" to settings().gui.rankCompleted
                rank.order == current.order -> "current" to settings().gui.rankCurrent
                rank.order == current.order + 1 -> "next" to settings().gui.rankNext
                else -> "locked" to settings().gui.rankLocked
            }
            val values = mapOf("rank" to locale().render(rank.displayNameKey, player))
            val lore = locale().renderLines("gui.rank.$state.lore", player, values) +
                renderBenefits(player, rank)
            inventory.setItem(region(RankMenuView.OVERVIEW, "ranks")[index], item(spec, locale().render("gui.rank.$state.name", player, values), lore))
        }
        renderPromotion(player, inventory, snapshot)
        renderNavigation(player, inventory, snapshot)
        renderAdminControls(player, inventory, snapshot)
    }

    private fun renderPaths(player: Player, inventory: Inventory, snapshot: RankPlayerSnapshot) {
        val exact = snapshot.rankState as RankState.Exact
        renderProfile(player, inventory, snapshot, catalog().require(exact.rankId), RankMenuView.PATHS)
        val evaluation = checkNotNull(snapshot.evaluation)
        SpecializationPath.entries.forEachIndexed { index, path ->
            val goal = evaluation.goals.firstOrNull { it.path == path }
            val state = when {
                !snapshot.availability.isAvailable(path) -> "unavailable"
                snapshot.profile.selectedFocus == path -> "selected"
                goal?.state == GoalState.COMPLETE -> "complete"
                else -> "available"
            }
            val sources = locale().renderLines(path.sourcesKey(), player)
            val values = mapOf(
                "path" to locale().render(path.nameKey(), player),
                "summary" to locale().render(path.summaryKey(), player),
                "source-1" to sources.getOrElse(0) { Component.empty() },
                "source-2" to sources.getOrElse(1) { Component.empty() },
                "source-3" to sources.getOrElse(2) { Component.empty() },
                "value" to locale().text(path.progressValue(snapshot.profile.progress)),
                "goal" to locale().text(goal?.required ?: path.progressValue(snapshot.profile.progress)),
                "mastery" to locale().render(snapshot.mastery.getValue(path).localeKey(), player),
            )
            inventory.setItem(
                region(RankMenuView.PATHS, "paths")[index],
                item(
                    settings().gui.item("path-${path.name.lowercase()}", PATH_ITEMS.getValue(path)),
                    locale().render("gui.path.$state.name", player, values),
                    locale().renderLines("gui.path.$state.lore", player, values),
                ),
            )
        }
        inventory.setItem(
            slot(RankMenuView.PATHS, "guide"),
            item(
                settings().gui.item("passport-guide", GuiItemSpec("MAP", 0)),
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
        rank: RankDefinition,
        view: RankMenuView,
    ) {
        val focus = snapshot.profile.selectedFocus
        val values = mapOf(
            "rank" to locale().render(rank.displayNameKey, player),
            "focus" to locale().render(focus.nameKey(), player),
            "focus-description" to locale().render(focus.summaryKey(), player),
        )
        inventory.setItem(
            slot(view, "profile"),
            items.playerHead(
                settings().gui.item("passport-profile", GuiItemSpec("PLAYER_HEAD", 0)),
                player,
                locale().render("gui.profile.name", player, values),
                locale().renderLines("gui.profile.lore", player, values) +
                    renderBenefits(player, rank) +
                    locale().renderLines("gui.profile.action", player),
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
        val benefits = evaluation.nextRank?.let { renderBenefits(player, it) }.orEmpty()
        val values = mapOf(
            "rank" to nextName,
            "reason" to reason,
        )
        val dimension = when (val next = evaluation.recommendation) {
            is NextStep.ActiveMinutes -> "active"
            is NextStep.PathGoal -> "path:${next.path.name.lowercase()}"
            null -> if (evaluation.eligibility == RankEligibility.TOP_RANK) "top" else "ready"
        }
        telemetry?.record(ProductEvent.RECOMMENDATION_SHOWN, ProductDimension(dimension))
        val lore = locale().renderLines("gui.promotion.$state.lore", player, values) +
            if (state == "top") emptyList() else benefits + locale().renderLines("gui.promotion.$state.action", player)
        inventory.setItem(
            slot(RankMenuView.OVERVIEW, "promotion"),
            item(settings().gui.promotion, locale().render("gui.promotion.$state.name", player, values), lore),
        )
    }

    private fun renderPromotionRunning(player: Player, inventory: Inventory) {
        inventory.setItem(
            slot(RankMenuView.OVERVIEW, "promotion"),
            item(
                settings().gui.item("running", settings().gui.promotion),
                locale().render("gui.promotion.running.name", player),
                locale().renderLines("gui.promotion.running.lore", player),
            ),
        )
    }

    private fun renderBenefits(player: Player, rank: RankDefinition): List<Component> {
        val sectionByStart = rank.benefitSections.associateBy { it.startIndex }
        return buildList {
            rank.benefitKeys.forEachIndexed { index, benefitKey ->
                sectionByStart[index]?.let { section ->
                    if (index > 0) add(locale().render("gui.rank.sections.spacer", player))
                    add(locale().render(section.titleKey, player))
                }
                add(locale().render(benefitKey, player))
            }
        }
    }

    private fun renderNavigation(player: Player, inventory: Inventory, snapshot: RankPlayerSnapshot) {
        inventory.setItem(slot(RankMenuView.OVERVIEW, "daily-quests"), item(
            GuiItemSpec("WRITABLE_BOOK", 0), locale().render("daily.entry.name", player),
            locale().renderLines("daily.entry.lore", player),
        ))
        inventory.setItem(
            slot(RankMenuView.OVERVIEW, "contracts"),
            item(settings().gui.contracts, locale().render("gui.passport.contracts.name", player), locale().renderLines("gui.passport.contracts.lore", player)),
        )
        inventory.setItem(
            slot(RankMenuView.OVERVIEW, "paths"),
            item(
                settings().gui.item("passport-paths", PATHS_ITEM),
                locale().render("gui.passport.paths.name", player),
                pathNavigationLore(player, snapshot),
            ),
        )
        inventory.setItem(
            slot(RankMenuView.OVERVIEW, "perks"),
            item(settings().gui.perks, locale().render("gui.passport.perks.name", player), locale().renderLines("gui.passport.perks.lore", player)),
        )
        inventory.setItem(
            slot(RankMenuView.OVERVIEW, "weekly-kit"),
            item(
                settings().gui.item("passport-weekly-kit", GuiItemSpec("CHEST", 0)),
                locale().render("gui.passport.weekly-kit.name", player),
                locale().renderLines("gui.passport.weekly-kit.lore", player),
            ),
        )
    }

    private fun pathNavigationLore(player: Player, snapshot: RankPlayerSnapshot): List<Component> {
        val evaluation = checkNotNull(snapshot.evaluation)
        val nextRank = evaluation.nextRank
            ?: return locale().renderLines("gui.passport.paths.top-lore", player)
        return locale().renderLines(
            "gui.passport.paths.lore",
            player,
            mapOf(
                "next-rank" to locale().render(nextRank.displayNameKey, player),
                "required-paths" to locale().text(evaluation.requiredChoices),
                "completed-paths" to locale().text(evaluation.completedChoices),
            ),
        )
    }

    private fun renderAdminControls(player: Player, inventory: Inventory, snapshot: RankPlayerSnapshot) {
        if (player.hasPermission(ADMIN_GRANT_PERMISSION)) {
            inventory.setItem(
                slot(RankMenuView.OVERVIEW, "admin-advance"),
                item(
                    settings().gui.item("passport-admin-advance", GuiItemSpec("COMMAND_BLOCK", 0)),
                    locale().render("gui.passport.admin.advance.name", player),
                    locale().renderLines(
                        "gui.passport.admin.advance.lore",
                        player,
                        mapOf("step" to recommendation(player, snapshot)),
                    ),
                ),
            )
        }
        if (player.hasPermission(ADMIN_ANALYTICS_PERMISSION)) {
            inventory.setItem(
                slot(RankMenuView.OVERVIEW, "admin-analytics"),
                item(
                    settings().gui.item("passport-admin-analytics", GuiItemSpec("SPYGLASS", 0)),
                    locale().render("gui.passport.admin.analytics.name", player),
                    locale().renderLines("gui.passport.admin.analytics.lore", player),
                ),
            )
        }
    }

    private fun renderPathBack(player: Player, inventory: Inventory) {
        inventory.setItem(
            slot(RankMenuView.PATHS, "back"),
            item(settings().gui.back, locale().render("gui.common.back.name", player), locale().renderLines("gui.common.back.lore", player)),
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
                mapOf("remaining" to locale().renderDurationMinutes(next.remaining, player)),
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
        const val ADMIN_GRANT_PERMISSION = "arcranks.admin.grant"
        const val ADMIN_ANALYTICS_PERMISSION = "arcranks.admin.analytics"
        val PATHS_ITEM = GuiItemSpec("COMPASS", 0)
        val ERROR_ITEM = GuiItemSpec("RED_STAINED_GLASS_PANE", 0)
        val PATH_ITEMS = mapOf(
            SpecializationPath.FARMING to GuiItemSpec("WHEAT", 0),
            SpecializationPath.INDUSTRY to GuiItemSpec("BLAST_FURNACE", 0),
            SpecializationPath.TRADE to GuiItemSpec("EMERALD", 0),
            SpecializationPath.EXPLORATION to GuiItemSpec("COMPASS", 0),
            SpecializationPath.BUILDING to GuiItemSpec("BRICKS", 0),
            SpecializationPath.COMMUNITY to GuiItemSpec("CAMPFIRE", 0),
        )
    }

    private fun menu(view: RankMenuView) = when (view) {
        RankMenuView.OVERVIEW -> ArcRanksMenuLayouts.PASSPORT
        RankMenuView.PATHS -> ArcRanksMenuLayouts.PATHS
    }

    private fun slot(view: RankMenuView, id: String): Int = layouts.slot(menu(view), id)

    private fun region(view: RankMenuView, id: String): List<Int> = layouts.region(menu(view), id)

    private fun statusElement(view: RankMenuView): String = when (view) {
        RankMenuView.OVERVIEW -> "promotion"
        RankMenuView.PATHS -> "guide"
    }
}

private enum class RankMenuView {
    OVERVIEW,
    PATHS,
}

private class RankPassportHolder(
    override val playerId: UUID,
    override val configGeneration: Long,
    val view: RankMenuView,
) : ArcRanksInventoryHolder {
    lateinit var menuInventory: Inventory
    var snapshot: RankPlayerSnapshot? = null
    var ready: Boolean = false
    var actionPending: Boolean = false
    private var feedbackGeneration: Long = 0

    fun beginFeedback(): Long = ++feedbackGeneration

    fun invalidateFeedback() {
        feedbackGeneration++
    }

    fun consumeFeedback(expected: Long): Boolean {
        if (feedbackGeneration != expected) return false
        feedbackGeneration++
        return true
    }

    override fun getInventory(): Inventory = menuInventory
}

private fun RankMenuView.titleKey(): String = when (this) {
    RankMenuView.OVERVIEW -> "gui.title"
    RankMenuView.PATHS -> "gui.paths.title"
}

private fun SpecializationPath.nameKey(): String = "paths.${name.lowercase()}.name"

private fun SpecializationPath.summaryKey(): String = "paths.${name.lowercase()}.summary"

private fun SpecializationPath.sourcesKey(): String = "paths.${name.lowercase()}.sources"

private fun MasteryLevel.localeKey(): String = "mastery.${name.lowercase()}"
