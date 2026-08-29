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
import ru.ruscrafting.ranks.analytics.AnalyticsService
import ru.ruscrafting.ranks.analytics.AnalyticsSummary
import ru.ruscrafting.ranks.analytics.ProductDimension
import ru.ruscrafting.ranks.analytics.ProductEvent
import ru.ruscrafting.ranks.analytics.ProductTelemetry
import ru.ruscrafting.ranks.analytics.TelemetryHealthSnapshot
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.config.GuiItemSpec
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.text.RankLocale
import java.util.Locale
import java.util.UUID

class AnalyticsMenu(
    private val settings: () -> ArcRanksSettings,
    private val locale: () -> RankLocale,
    private val analytics: AnalyticsService,
    private val health: () -> TelemetryHealthSnapshot,
    private val tasks: LifecycleTaskScope,
    private val telemetry: ProductTelemetry?,
    private val back: (Player) -> Unit,
) : Listener {
    private val items = RankMenuItemFactory { settings().gui.background }

    fun open(player: Player, days: Int = 7) {
        if (!player.hasPermission("arcranks.admin.analytics")) {
            player.sendMessage(locale().render("commands.no-permission", player))
            return
        }
        val holder = AnalyticsMenuHolder(player.uniqueId, days)
        val inventory = Bukkit.createInventory(holder, INVENTORY_SIZE, locale().render("gui.analytics.title", player))
        holder.menuInventory = inventory
        renderLoading(player, inventory)
        player.openInventory(inventory)
        telemetry?.record(ProductEvent.ANALYTICS_BOARD_OPEN, ProductDimension("window:$days"))
        refresh(player, holder)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? AnalyticsMenuHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory !== event.view.topInventory) return
        val player = event.whoClicked as? Player ?: return
        if (holder.playerId != player.uniqueId) return
        when (event.rawSlot) {
            BACK_SLOT -> back(player)
            REFRESH_SLOT -> refresh(player, holder)
            CLOSE_SLOT -> player.closeInventory()
            in WINDOW_SLOTS -> {
                holder.days = WINDOWS[WINDOW_SLOTS.indexOf(event.rawSlot)]
                refresh(player, holder)
            }
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is AnalyticsMenuHolder && event.rawSlots.any { it < INVENTORY_SIZE }) {
            event.isCancelled = true
        }
    }

    private fun refresh(player: Player, holder: AnalyticsMenuHolder) {
        holder.generation++
        val generation = holder.generation
        renderLoading(player, holder.menuInventory)
        analytics.summary(holder.days).whenCompleteSync(tasks) { summary, failure ->
            if (!holder.current(player, generation)) return@whenCompleteSync
            if (failure != null || summary == null) renderError(player, holder.menuInventory)
            else render(player, holder.menuInventory, summary, health())
        }
    }

    private fun render(
        player: Player,
        inventory: Inventory,
        summary: AnalyticsSummary,
        health: TelemetryHealthSnapshot,
    ) {
        items.fill(inventory)
        WINDOWS.forEachIndexed { index, days ->
            val state = if (days == summary.days) "selected" else "available"
            inventory.setItem(
                WINDOW_SLOTS[index],
                items.item(GuiItemSpec("CLOCK", 0), locale().render("gui.analytics.window.$state.name", player, mapOf("days" to locale().text(days))), locale().renderLines("gui.analytics.window.$state.lore", player)),
            )
        }
        val common = mapOf("days" to locale().text(summary.days))
        inventory.setItem(
            OVERVIEW_SLOT,
            card(player, "overview", "MAP", common + mapOf(
                "players" to locale().text(summary.uniqueSeenPlayers),
                "passport" to locale().text(summary.passportPlayers),
                "rate" to locale().text(summary.passportReach.percent()),
            )),
        )
        inventory.setItem(
            CONTRACTS_SLOT,
            card(player, "contracts", "WRITABLE_BOOK", common + mapOf(
                "accepted" to locale().text(summary.contractAcceptedPlayers),
                "completed" to locale().text(summary.contractCompletedPlayers),
                "reach" to locale().text(summary.contractAcceptReach.percent()),
                "rate" to locale().text(summary.contractCompletionRate.percent()),
            )),
        )
        inventory.setItem(
            PERKS_SLOT,
            card(player, "perks", "ENCHANTED_BOOK", common + mapOf(
                "selected" to locale().text(summary.perkSelectedPlayers),
                "rate" to locale().text(summary.perkReach.percent()),
            )),
        )
        inventory.setItem(
            PROMOTIONS_SLOT,
            card(player, "promotions", "NETHER_STAR", common + mapOf(
                "attempts" to locale().text(summary.promotionAttempts),
                "successes" to locale().text(summary.promotionSuccesses),
                "rate" to locale().text(summary.promotionSuccessRate.percent()),
            )),
        )
        inventory.setItem(
            RECOMMENDATION_SLOT,
            card(player, "recommendations", "COMPASS", common + mapOf(
                "top" to recommendation(player, summary.topRecommendation),
            )),
        )
        val lastFlushAge = if (health.lastSuccessfulFlushEpochMillis == 0L) {
            "—"
        } else {
            ((System.currentTimeMillis() - health.lastSuccessfulFlushEpochMillis).coerceAtLeast(0) / 1_000).toString()
        }
        inventory.setItem(
            HEALTH_SLOT,
            card(player, "health", "REDSTONE_TORCH", mapOf(
                "keys" to locale().text(health.pendingMetricKeys),
                "players" to locale().text(health.pendingPlayers),
                "dropped" to locale().text(health.droppedMetricKeys + health.droppedPlayers),
                "failures" to locale().text(health.flushFailures),
                "age" to locale().text(lastFlushAge),
            )),
        )
        val state = if (summary.uniqueSeenPlayers == 0L) "empty" else "populated"
        inventory.setItem(STATUS_SLOT, items.item(settings().gui.analytics, locale().render("gui.analytics.$state.name", player, common), locale().renderLines("gui.analytics.$state.lore", player, common)))
        renderControls(player, inventory)
    }

    private fun card(player: Player, key: String, material: String, values: Map<String, net.kyori.adventure.text.Component>) =
        items.item(GuiItemSpec(material, 0), locale().render("gui.analytics.cards.$key.name", player, values), locale().renderLines("gui.analytics.cards.$key.lore", player, values))

    private fun recommendation(player: Player, value: String?): net.kyori.adventure.text.Component {
        val path = value?.takeIf { it.startsWith("path:") }?.removePrefix("path:")
            ?.let { id -> SpecializationPath.entries.firstOrNull { it.name.equals(id, ignoreCase = true) } }
        if (path != null) return locale().render("paths.${path.name.lowercase()}.name", player)
        return when (value) {
            "active", "ready", "top" -> locale().render("gui.analytics.recommendation.$value", player)
            else -> locale().text("—")
        }
    }

    private fun renderLoading(player: Player, inventory: Inventory) {
        items.fill(inventory)
        inventory.setItem(STATUS_SLOT, items.item(settings().gui.analytics, locale().render("gui.analytics.loading.name", player), locale().renderLines("gui.analytics.loading.lore", player)))
        renderControls(player, inventory)
    }

    private fun renderError(player: Player, inventory: Inventory) {
        items.fill(inventory)
        inventory.setItem(STATUS_SLOT, items.item(GuiItemSpec("BARRIER", 0), locale().render("gui.analytics.error.name", player), locale().renderLines("gui.analytics.error.lore", player)))
        renderControls(player, inventory)
    }

    private fun renderControls(player: Player, inventory: Inventory) {
        inventory.setItem(BACK_SLOT, items.item(GuiItemSpec("ARROW", 0), locale().render("gui.common.back.name", player), locale().renderLines("gui.common.back.lore", player)))
        inventory.setItem(REFRESH_SLOT, items.item(GuiItemSpec("CLOCK", 0), locale().render("gui.common.refresh.name", player), locale().renderLines("gui.common.refresh.lore", player)))
        inventory.setItem(CLOSE_SLOT, items.item(GuiItemSpec("BARRIER", 0), locale().render("gui.common.close.name", player), locale().renderLines("gui.common.close.lore", player)))
    }

    companion object {
        const val INVENTORY_SIZE = 54
        val WINDOWS = listOf(7, 14, 30)
        val WINDOW_SLOTS = listOf(10, 11, 12)
        const val STATUS_SLOT = 4
        const val OVERVIEW_SLOT = 20
        const val CONTRACTS_SLOT = 22
        const val PERKS_SLOT = 24
        const val PROMOTIONS_SLOT = 30
        const val RECOMMENDATION_SLOT = 32
        const val HEALTH_SLOT = 40
        const val BACK_SLOT = 45
        const val REFRESH_SLOT = 49
        const val CLOSE_SLOT = 53
    }
}

private class AnalyticsMenuHolder(val playerId: UUID, var days: Int) : InventoryHolder {
    lateinit var menuInventory: Inventory
    var generation = 0L

    fun current(player: Player, expectedGeneration: Long): Boolean =
        player.isOnline && generation == expectedGeneration && player.openInventory.topInventory === menuInventory

    override fun getInventory(): Inventory = menuInventory
}

private fun Double.percent(): String = String.format(Locale.US, "%.1f%%", this * 100.0)
