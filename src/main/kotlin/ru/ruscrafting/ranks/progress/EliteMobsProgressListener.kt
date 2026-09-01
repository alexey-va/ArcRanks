package ru.ruscrafting.ranks.progress

import com.magmaguy.elitemobs.api.DungeonCompleteEvent
import com.magmaguy.elitemobs.api.DungeonStartEvent
import com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import ru.ruscrafting.ranks.api.RankProgressApi
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.storage.ExternalProgressResult
import java.util.UUID
import java.util.WeakHashMap
import java.util.logging.Level
import java.util.logging.Logger

class EliteMobsProgressListener(
    private val api: RankProgressApi,
    private val settings: () -> ArcRanksSettings,
    private val logger: Logger,
    private val onProgressChanged: (UUID) -> Unit = {},
) : Listener {
    private val runs = WeakHashMap<DungeonInstance, DungeonRun>()

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDungeonStart(event: DungeonStartEvent) {
        val source = settings().collection.dungeonCompletion
        if (!source.enabled) return
        val participants = eligibleParticipants(event.dungeonInstance)
        if (participants.isNotEmpty()) runs[event.dungeonInstance] = DungeonRun(UUID.randomUUID(), participants)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDungeonComplete(event: DungeonCompleteEvent) {
        val source = settings().collection.dungeonCompletion
        if (!source.enabled) return
        val run = runs[event.dungeonInstance] ?: return
        val completed = completedDungeonParticipants(run.startParticipants, eligibleParticipants(event.dungeonInstance))
        completed.forEach { playerId ->
            api.record(
                source = DUNGEON_SOURCE,
                eventId = "${run.id.toString().replace("-", "")}:${playerId.toString().replace("-", "")}",
                playerId = playerId,
                metric = ProgressMetric.TRAVEL_BLOCKS,
                delta = source.amount,
            ).whenComplete { result, failure ->
                if (failure != null) logger.log(Level.WARNING, "Could not record EliteMobs dungeon progress", failure)
                else if (result == ExternalProgressResult.APPLIED) onProgressChanged(playerId)
            }
        }
    }

    private fun eligibleParticipants(instance: DungeonInstance): Set<UUID> {
        val collection = settings().collection
        return instance.participants.asSequence()
            .filter { collection.allows(it.gameMode.name, it.world.name) }
            .mapTo(linkedSetOf()) { it.uniqueId }
    }

    private data class DungeonRun(val id: UUID, val startParticipants: Set<UUID>)

    private companion object {
        const val DUNGEON_SOURCE = "elitemobs_dungeon"
    }
}

internal fun completedDungeonParticipants(start: Set<UUID>, finish: Set<UUID>): Set<UUID> = start.intersect(finish)
