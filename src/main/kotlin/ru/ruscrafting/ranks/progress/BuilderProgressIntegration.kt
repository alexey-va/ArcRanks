package ru.ruscrafting.ranks.progress

import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.ruscrafting.ranks.api.RankProgressApi
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.storage.ExternalProgressResult
import java.time.Instant
import java.util.UUID
import java.util.logging.Level

/** Optional public ArcBuilder event bridge; no dependency on its private runtime. */
class BuilderProgressIntegration(
    private val plugin: Plugin,
    private val api: RankProgressApi,
    private val settings: () -> ArcRanksSettings,
    private val gate: BuildingProgressGate,
    private val tasks: LifecycleTaskScope,
    private val onProgressChanged: (UUID) -> Unit,
) {
    fun install(): Boolean {
        val provider = plugin.server.pluginManager.getPlugin("ArcBuilder")?.takeIf(Plugin::isEnabled) ?: return false
        val eventClass = runCatching {
            Class.forName("ru.ruscrafting.builder.api.BuilderOperationCommittedEvent", false, provider.javaClass.classLoader)
                .asSubclass(Event::class.java)
        }.getOrElse {
            plugin.logger.warning("ArcBuilder committed-operation API is unavailable; tool progress is disabled")
            return false
        }
        plugin.server.pluginManager.registerEvent(eventClass, object : Listener {}, EventPriority.MONITOR,
            EventExecutor { _, event ->
                runCatching { record(event) }.onFailure {
                    plugin.logger.log(Level.WARNING, "Could not read ArcBuilder committed-operation progress", it)
                }
            }, plugin, true)
        return true
    }

    private fun record(event: Event) {
        val collection = settings().collection
        if (!collection.builderToolsEnabled) return
        val operation = decodeBuilderProgress(event)
        val player = plugin.server.getPlayer(operation.playerId) ?: return
        if (player.world.uid != operation.worldId || !collection.allows(player.gameMode.name, player.world.name)) return
        val now = Instant.now()
        val accepted = operation.placements.filter { position ->
            collection.blockPlace.allows(position.material) && gate.credit(operation.worldId,
                position.x, position.y, position.z, now, collection.buildingRepeatWindowSeconds)
        }
        val delta = minOf(accepted.size.toLong(), collection.builderToolsMaximumProgress)
        if (delta == 0L) return
        api.record("arcbuilder", operation.operationId, operation.playerId, ProgressMetric.BLOCKS_PLACED, delta)
            .whenComplete { result, failure ->
                if (failure != null) {
                    plugin.logger.log(Level.WARNING, "Could not record ArcBuilder construction progress", failure)
                    if (plugin.isEnabled) tasks.runSync {
                        accepted.forEach { gate.release(operation.worldId, it.x, it.y, it.z, now) }
                    }
                }
                else if (result == ExternalProgressResult.APPLIED) onProgressChanged(operation.playerId)
            }
    }
}

internal data class BuilderProgressPlacement(val x: Int, val y: Int, val z: Int, val material: String)
internal data class BuilderProgressOperation(
    val operationId: String, val playerId: UUID, val worldId: UUID, val placements: List<BuilderProgressPlacement>,
)

internal fun decodeBuilderProgress(event: Any): BuilderProgressOperation {
    fun Any.value(method: String): Any = javaClass.getMethod(method).invoke(this)
    val operationId = event.value("getOperationId") as String
    require(operationId.length in 1..128)
    val placements = event.value("getPlacements") as List<*>
    require(placements.size <= 10_000)
    return BuilderProgressOperation(operationId, event.value("getPlayerId") as UUID,
        event.value("getWorldId") as UUID, placements.map { raw ->
            val placement = requireNotNull(raw)
            BuilderProgressPlacement(placement.value("getX") as Int, placement.value("getY") as Int,
                placement.value("getZ") as Int,
                (placement.value("getMaterial") as String).substringAfter(':').uppercase(java.util.Locale.ROOT))
        })
}
