package ru.ruscrafting.ranks.progress

import com.magmaguy.elitemobs.api.DungeonCompleteEvent
import com.magmaguy.elitemobs.api.DungeonStartEvent
import com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance
import org.bukkit.event.EventHandler
import org.bukkit.event.entity.PlayerDeathEvent
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
    private val quests: ru.ruscrafting.ranks.api.RankQuestApi? = null,
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
        val run = runs.remove(event.dungeonInstance) ?: return
        val completed = completedDungeonParticipants(run.startParticipants, eligibleParticipants(event.dungeonInstance))
        // The pinned API stub omits CustomConfigFields; read this verified public accessor without linking that supertype.
        val dungeonId = runCatching {
            val instance = event.dungeonInstance
            val content = instance.javaClass.getMethod("getContentPackagesConfigFields").invoke(instance)
            (content.javaClass.getMethod("getDungeonConfigFolderName").invoke(content) as String)
                .lowercase(java.util.Locale.ROOT)
        }.getOrDefault("unknown")
        completed.forEach { playerId ->
            if (dungeonId.matches(Regex("[a-z0-9_.-]{1,64}"))) {
                quests?.record("elitemobs_dungeon", "${run.id}:$playerId", playerId, "dungeon.complete:$dungeonId${if (playerId !in run.deaths) ":flawless" else ""}", 1)
                    ?.exceptionally { failure -> logger.log(Level.WARNING, "Could not record dungeon quest", failure); null }
            }
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

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        val id = event.entity.uniqueId
        runs.values.filter { id in it.startParticipants }.forEach { it.deaths += id }
    }

    private data class DungeonRun(val id: UUID, val startParticipants: Set<UUID>, val deaths: MutableSet<UUID> = mutableSetOf())

    private companion object {
        const val DUNGEON_SOURCE = "elitemobs_dungeon"
    }
}

internal fun completedDungeonParticipants(start: Set<UUID>, finish: Set<UUID>): Set<UUID> = start.intersect(finish)
