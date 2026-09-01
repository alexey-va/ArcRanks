package ru.ruscrafting.ranks.progress

import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import ru.ruscrafting.ranks.api.RankProgressApi
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.storage.ExternalProgressResult
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import java.util.logging.Level

class AuctionProgressIntegration(
    private val plugin: Plugin,
    private val api: RankProgressApi,
    private val settings: () -> ArcRanksSettings,
    private val onProgressChanged: (UUID) -> Unit = {},
) {
    fun install(): Boolean {
        val provider = AUCTION_PLUGIN_NAMES.firstNotNullOfOrNull { name ->
            plugin.server.pluginManager.getPlugin(name)?.takeIf(Plugin::isEnabled)
        } ?: return false
        val eventClass = runCatching {
            Class.forName(AUCTION_EVENT_CLASS, false, provider.javaClass.classLoader).asSubclass(Event::class.java)
        }.getOrElse { failure ->
            plugin.logger.log(Level.WARNING, "Could not load the zAuctionHouse completed-sale event", failure)
            return false
        }
        plugin.server.pluginManager.registerEvent(
            eventClass,
            object : Listener {},
            EventPriority.MONITOR,
            EventExecutor { _, event -> onEvent(event) },
            plugin,
            true,
        )
        return true
    }

    private fun onEvent(event: Event) {
        val source = settings().collection.auctionDeal
        if (!source.enabled) return
        val sale = runCatching { decodeAuctionSale(event, source.maximumProgressPerDeal) }
            .onFailure { plugin.logger.log(Level.WARNING, "Could not decode a completed zAuctionHouse sale", it) }
            .getOrNull() ?: return
        linkedMapOf("buyer" to sale.buyer, "seller" to sale.seller).forEach { (role, playerId) ->
            api.record(
                source = AUCTION_SOURCE,
                eventId = "${sale.listingId}:$role",
                playerId = playerId,
                metric = ProgressMetric.TRADE_ACTIONS,
                delta = sale.progress,
            ).whenComplete { result, failure ->
                if (failure != null) plugin.logger.log(Level.WARNING, "Could not record zAuctionHouse sale progress", failure)
                else if (result == ExternalProgressResult.APPLIED) onProgressChanged(playerId)
            }
        }
    }

    private companion object {
        val AUCTION_PLUGIN_NAMES = listOf("zAuctionHouse", "zAuctionHouseV3")
        const val AUCTION_EVENT_CLASS =
            "fr.maxlego08.zauctionhouse.api.event.events.remove.AuctionRemoveListedItemEvent"
        const val AUCTION_SOURCE = "zauctionhouse_sale"
    }
}

internal data class AuctionSaleProgress(
    val listingId: Long,
    val seller: UUID,
    val buyer: UUID,
    val progress: Long,
)

internal fun decodeAuctionSale(event: Any, maximumProgress: Long): AuctionSaleProgress? {
    require(maximumProgress > 0) { "Maximum auction progress must be positive" }
    val item = event.invokeNoArg("getItem") ?: return null
    if (item.invokeNoArg("getStatus")?.toString() != "PURCHASED") return null
    val listingId = (item.invokeNoArg("getId") as? Number)?.toLong() ?: return null
    val seller = item.invokeNoArg("getSellerUniqueId") as? UUID ?: return null
    val buyer = item.invokeNoArg("getBuyerUniqueId") as? UUID ?: return null
    if (seller == buyer) return null
    val price = item.invokeNoArg("getPrice") as? BigDecimal ?: return null
    val progress = price.max(BigDecimal.ZERO)
        .min(BigDecimal.valueOf(maximumProgress))
        .setScale(0, RoundingMode.FLOOR)
        .longValueExact()
    if (progress <= 0) return null
    return AuctionSaleProgress(listingId, seller, buyer, progress)
}

private fun Any.invokeNoArg(name: String): Any? = javaClass.getMethod(name).invoke(this)
