package ru.ruscrafting.ranks.gui

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
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
) : Listener {
    fun open(player: Player) {
        val holder = RankPassportHolder(player.uniqueId)
        val inventory = Bukkit.createInventory(holder, INVENTORY_SIZE, locale().render("gui.title", player))
        holder.menuInventory = inventory
        renderLoading(player, inventory)
        player.openInventory(inventory)
        refresh(player, holder)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? RankPassportHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory !== event.view.topInventory) return
        val player = event.whoClicked as? Player ?: return
        if (holder.playerId != player.uniqueId) return
        when (event.rawSlot) {
            CLOSE_SLOT -> player.closeInventory()
            REFRESH_SLOT -> refresh(player, holder)
            PROMOTION_SLOT -> promote(player, holder)
            in PATH_SLOTS -> {
                val path = SpecializationPath.entries.getOrNull(PATH_SLOTS.indexOf(event.rawSlot)) ?: return
                selectFocus(player, holder, path)
            }
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is RankPassportHolder && event.rawSlots.any { it < INVENTORY_SIZE }) {
            event.isCancelled = true
        }
    }

    private fun refresh(player: Player, holder: RankPassportHolder) {
        renderLoading(player, holder.menuInventory)
        val token = tasks.token()
        players.load(player.uniqueId).whenCompleteSync(tasks, token) { snapshot, failure ->
            if (!player.isOnline || player.openInventory.topInventory !== holder.menuInventory) return@whenCompleteSync
            if (failure != null || snapshot == null) renderError(player, holder.menuInventory)
            else {
                holder.snapshot = snapshot
                render(player, holder.menuInventory, snapshot)
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
            if (player.openInventory.topInventory === holder.menuInventory) render(player, holder.menuInventory, snapshot)
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

    private fun renderLoading(player: Player, inventory: Inventory) {
        fill(inventory)
        inventory.setItem(
            PROFILE_SLOT,
            item(settings().gui.rankCurrent, locale().render("gui.state.loading.name", player), locale().renderLines("gui.state.loading.lore", player)),
        )
        renderControls(player, inventory)
    }

    private fun renderError(player: Player, inventory: Inventory) {
        fill(inventory)
        inventory.setItem(
            PROFILE_SLOT,
            item(GuiItemSpec("BARRIER", 0), locale().render("gui.state.error.name", player), locale().renderLines("gui.state.error.lore", player)),
        )
        renderControls(player, inventory)
    }

    private fun render(player: Player, inventory: Inventory, snapshot: RankPlayerSnapshot) {
        fill(inventory)
        val exact = snapshot.rankState as? RankState.Exact
        val evaluation = snapshot.evaluation
        if (exact == null || evaluation == null) {
            val key = when (snapshot.rankState) {
                RankState.Missing -> "commands.rank-state.missing"
                is RankState.Conflict -> "commands.rank-state.conflict"
                else -> "commands.rank-state.unknown"
            }
            inventory.setItem(PROFILE_SLOT, item(GuiItemSpec("BARRIER", 0), locale().render("gui.state.error.name", player), listOf(locale().render(key, player))))
            renderControls(player, inventory)
            return
        }
        val current = catalog().require(exact.rankId)
        inventory.setItem(
            PROFILE_SLOT,
            item(
                settings().gui.rankCurrent,
                locale().render("gui.profile.name", player, mapOf("rank" to locale().render(current.displayNameKey, player))),
                locale().renderLines(
                    "gui.profile.lore",
                    player,
                    mapOf("focus" to locale().render(snapshot.profile.selectedFocus.nameKey(), player)),
                ),
            ),
        )
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
            inventory.setItem(PATH_SLOTS[index], item(settings().gui.path, locale().render("gui.path.$state.name", player, values), locale().renderLines("gui.path.$state.lore", player, values)))
        }
        renderRecommendation(player, inventory, snapshot)
        renderBenefits(player, inventory, snapshot)
        renderPromotion(player, inventory, snapshot)
        renderControls(player, inventory)
    }

    private fun renderRecommendation(player: Player, inventory: Inventory, snapshot: RankPlayerSnapshot) {
        val recommendation = recommendation(player, snapshot)
        val values = mapOf("recommendation" to recommendation)
        inventory.setItem(RECOMMENDATION_SLOT, item(GuiItemSpec("COMPASS", 0), locale().render("gui.recommendation.name", player), locale().renderLines("gui.recommendation.lore", player, values)))
    }

    private fun renderBenefits(player: Player, inventory: Inventory, snapshot: RankPlayerSnapshot) {
        val rank = snapshot.evaluation?.nextRank ?: snapshot.evaluation?.currentRank ?: return
        val benefits = rank.benefitKeys.map { locale().render(it, player) }
        val values = mapOf(
            "benefit-one" to benefits.getOrElse(0) { Component.empty() },
            "benefit-two" to benefits.getOrElse(1) { Component.empty() },
        )
        inventory.setItem(BENEFITS_SLOT, item(GuiItemSpec("HONEYCOMB", 0), locale().render("gui.benefits.name", player), locale().renderLines("gui.benefits.lore", player, values)))
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
        val values = mapOf("rank" to nextName, "reason" to reason)
        inventory.setItem(PROMOTION_SLOT, item(settings().gui.promotion, locale().render("gui.promotion.$state.name", player, values), locale().renderLines("gui.promotion.$state.lore", player, values)))
    }

    private fun renderPromotionRunning(player: Player, inventory: Inventory) {
        inventory.setItem(PROMOTION_SLOT, item(settings().gui.promotion, locale().render("gui.promotion.running.name", player), locale().renderLines("gui.promotion.running.lore", player)))
    }

    private fun renderControls(player: Player, inventory: Inventory) {
        inventory.setItem(REFRESH_SLOT, item(GuiItemSpec("CLOCK", 0), locale().render("gui.common.refresh.name", player), locale().renderLines("gui.common.refresh.lore", player)))
        inventory.setItem(CLOSE_SLOT, item(GuiItemSpec("BARRIER", 0), locale().render("gui.common.close.name", player), locale().renderLines("gui.common.close.lore", player)))
    }

    private fun fill(inventory: Inventory) {
        val filler = item(settings().gui.background, Component.empty(), emptyList())
        repeat(INVENTORY_SIZE) { inventory.setItem(it, filler) }
    }

    @Suppress("DEPRECATION")
    private fun item(spec: GuiItemSpec, name: Component, lore: List<Component>): ItemStack {
        val material = Material.matchMaterial(spec.material) ?: error("Unknown GUI material ${spec.material}")
        return ItemStack(material).apply {
            itemMeta = itemMeta.apply {
                displayName(name)
                lore(lore)
                if (spec.customModelData > 0) setCustomModelData(spec.customModelData)
            }
        }
    }

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
        const val INVENTORY_SIZE = 54
        const val PROFILE_SLOT = 4
        val RANK_SLOTS = (9..17).toList()
        val PATH_SLOTS = (28..33).toList()
        const val RECOMMENDATION_SLOT = 39
        const val BENEFITS_SLOT = 41
        const val REFRESH_SLOT = 45
        const val PROMOTION_SLOT = 49
        const val CLOSE_SLOT = 53
    }
}

private class RankPassportHolder(val playerId: UUID) : InventoryHolder {
    lateinit var menuInventory: Inventory
    var snapshot: RankPlayerSnapshot? = null

    override fun getInventory(): Inventory = menuInventory
}

private fun SpecializationPath.nameKey(): String = "paths.${name.lowercase()}.name"

private fun SpecializationPath.summaryKey(): String = "paths.${name.lowercase()}.summary"

private fun MasteryLevel.localeKey(): String = "mastery.${name.lowercase()}"
