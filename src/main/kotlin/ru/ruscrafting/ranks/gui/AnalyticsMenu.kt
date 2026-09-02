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
    private val layouts: ArcRanksMenuLayouts,
    private val configGeneration: () -> Long = { 0L },
) : Listener {
    private val items = RankMenuItemFactory { settings().gui.background }

    fun open(player: Player, days: Int = settings().analytics.defaultWindow) {
        if (!player.hasPermission("arcranks.admin.analytics")) {
            player.sendMessage(locale().render("commands.no-permission", player))
            return
        }
        val holder = AnalyticsMenuHolder(player.uniqueId, configGeneration(), days)
        val inventory = layouts.create(holder, MENU, locale().render("gui.analytics.title", player))
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
            slot("back") -> back(player)
            slot("refresh") -> refresh(player, holder)
            in region("windows") -> {
                val days = settings().analytics.windows[region("windows").indexOf(event.rawSlot)]
                if (days == holder.days) return
                holder.days = days
                refresh(player, holder)
            }
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is AnalyticsMenuHolder && event.rawSlots.any { it < event.view.topInventory.size }) {
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
        settings().analytics.windows.forEachIndexed { index, days ->
            val state = if (days == summary.days) "selected" else "available"
            inventory.setItem(
                region("windows")[index],
                items.item(
                    settings().gui.item("analytics-window", GuiItemSpec("CLOCK", 0)),
                    locale().render("gui.analytics.window.$state.name", player, mapOf("days" to locale().text(days))),
                    locale().renderLines("gui.analytics.window.$state.lore", player),
                ),
            )
        }
        val common = mapOf("days" to locale().text(summary.days))
        inventory.setItem(
            slot("overview"),
            card(player, "overview", common + mapOf(
                "players" to locale().text(summary.uniqueSeenPlayers),
                "passport" to locale().text(summary.passportPlayers),
                "rate" to locale().text(summary.passportReach.percent()),
            )),
        )
        inventory.setItem(
            slot("contracts"),
            card(player, "contracts", common + mapOf(
                "accepted" to locale().text(summary.contractAcceptedPlayers),
                "completed" to locale().text(summary.contractCompletedPlayers),
                "reach" to locale().text(summary.contractAcceptReach.percent()),
                "rate" to locale().text(summary.contractCompletionRate.percent()),
            )),
        )
        inventory.setItem(
            slot("perks"),
            card(player, "perks", common + mapOf(
                "selected" to locale().text(summary.perkSelectedPlayers),
                "rate" to locale().text(summary.perkReach.percent()),
            )),
        )
        inventory.setItem(
            slot("promotions"),
            card(player, "promotions", common + mapOf(
                "attempts" to locale().text(summary.promotionAttempts),
                "successes" to locale().text(summary.promotionSuccesses),
                "rate" to locale().text(summary.promotionSuccessRate.percent()),
            )),
        )
        inventory.setItem(
            slot("recommendations"),
            card(player, "recommendations", common + mapOf(
                "top" to recommendation(player, summary.topRecommendation),
            )),
        )
        val lastFlushAge = if (health.lastSuccessfulFlushEpochMillis == 0L) {
            "—"
        } else {
            ((System.currentTimeMillis() - health.lastSuccessfulFlushEpochMillis).coerceAtLeast(0) / 1_000).toString()
        }
        inventory.setItem(
            slot("health"),
            card(player, "health", mapOf(
                "keys" to locale().text(health.pendingMetricKeys),
                "players" to locale().text(health.pendingPlayers),
                "dropped" to locale().text(health.droppedMetricKeys + health.droppedPlayers),
                "failures" to locale().text(health.flushFailures),
                "age" to locale().text(lastFlushAge),
            )),
        )
        val state = if (summary.uniqueSeenPlayers == 0L) "empty" else "populated"
        inventory.setItem(slot("status"), items.item(settings().gui.analytics, locale().render("gui.analytics.$state.name", player, common), locale().renderLines("gui.analytics.$state.lore", player, common)))
        renderControls(player, inventory)
    }

    private fun card(
        player: Player,
        key: String,
        values: Map<String, net.kyori.adventure.text.Component>,
    ) = items.item(
        settings().gui.item("analytics-$key", CARD_ITEM_FALLBACKS.getValue(key)),
        locale().render("gui.analytics.cards.$key.name", player, values),
        locale().renderLines("gui.analytics.cards.$key.lore", player, values),
    )

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
        inventory.setItem(
            slot("status"),
            items.item(
                settings().gui.item("loading", settings().gui.analytics),
                locale().render("gui.analytics.loading.name", player),
                locale().renderLines("gui.analytics.loading.lore", player),
            ),
        )
        renderControls(player, inventory)
    }

    private fun renderError(player: Player, inventory: Inventory) {
        items.fill(inventory)
        inventory.setItem(
            slot("status"),
            items.item(
                settings().gui.item("error", GuiItemSpec("RED_STAINED_GLASS_PANE", 0)),
                locale().render("gui.analytics.error.name", player),
                locale().renderLines("gui.analytics.error.lore", player),
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
        val MENU = ArcRanksMenuLayouts.ANALYTICS
        private val CARD_ITEM_FALLBACKS = mapOf(
            "overview" to GuiItemSpec("MAP", 0),
            "contracts" to GuiItemSpec("WRITABLE_BOOK", 0),
            "perks" to GuiItemSpec("ENCHANTED_BOOK", 0),
            "promotions" to GuiItemSpec("NETHER_STAR", 0),
            "recommendations" to GuiItemSpec("COMPASS", 0),
            "health" to GuiItemSpec("REDSTONE_TORCH", 0),
        )
    }

    private fun slot(element: String): Int = layouts.slot(MENU, element)
    private fun region(id: String): List<Int> = layouts.region(MENU, id)

}

private class AnalyticsMenuHolder(
    override val playerId: UUID,
    override val configGeneration: Long,
    var days: Int,
) : ArcRanksInventoryHolder {
    lateinit var menuInventory: Inventory
    var generation = 0L

    fun current(player: Player, expectedGeneration: Long): Boolean =
        player.isOnline && generation == expectedGeneration && player.openInventory.topInventory === menuInventory

    override fun getInventory(): Inventory = menuInventory
}

private fun Double.percent(): String = String.format(Locale.US, "%.1f%%", this * 100.0)
