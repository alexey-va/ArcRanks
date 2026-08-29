package ru.ruscrafting.ranks.service

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import ru.arc.logging.ArcLogging

class PlayerSnapshotListener(
    private val players: RankPlayerService,
    private val cache: RankSnapshotCache,
) : Listener {
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        players.load(event.player.uniqueId).exceptionally { failure ->
            ArcLogging.warn("Could not warm ArcRanks snapshot for {}: {}", event.player.uniqueId, failure.javaClass.simpleName)
            null
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        cache.remove(event.player.uniqueId)
    }
}
