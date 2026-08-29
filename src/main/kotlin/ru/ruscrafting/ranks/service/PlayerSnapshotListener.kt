package ru.ruscrafting.ranks.service

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import ru.arc.logging.ArcLogging
import ru.ruscrafting.ranks.analytics.PlayerSignal
import ru.ruscrafting.ranks.analytics.ProductDimension
import ru.ruscrafting.ranks.analytics.ProductEvent
import ru.ruscrafting.ranks.analytics.ProductTelemetry
import ru.ruscrafting.ranks.rankstate.RankState

class PlayerSnapshotListener(
    private val players: RankPlayerService,
    private val cache: RankSnapshotCache,
    private val telemetry: ProductTelemetry?,
) : Listener {
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        players.load(event.player.uniqueId).thenAccept { snapshot ->
            val rank = (snapshot.rankState as? RankState.Exact)?.rankId
            telemetry?.record(
                ProductEvent.PLAYER_SEEN,
                ProductDimension(rank?.let { "rank:${it.value}" } ?: "rank:unknown"),
            )
            telemetry?.recordPlayer(event.player.uniqueId, PlayerSignal.SEEN, rank)
        }.exceptionally { failure ->
            ArcLogging.warn("Could not warm ArcRanks snapshot for {}: {}", event.player.uniqueId, failure.javaClass.simpleName)
            null
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        cache.remove(event.player.uniqueId)
    }
}
