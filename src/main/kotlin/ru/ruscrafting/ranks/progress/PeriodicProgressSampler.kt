package ru.ruscrafting.ranks.progress

import net.milkbowl.vault.economy.Economy
import org.bukkit.Server
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.perk.PerkProgressModifier
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.math.floor

/** Native capture/application yield through the runtime budget; neighbor planning is immutable worker work. */
class PeriodicProgressSampler(
    private val server: Server,
    private val settings: () -> ArcRanksSettings,
    private val buffer: ProgressBuffer,
    private val modifier: PerkProgressModifier,
    private val economy: Economy?,
    private val tasks: LifecycleTaskScope,
    private val nativeWork: BudgetedTaskQueue,
    private val worker: Executor,
    private val onFailure: (Throwable) -> Unit,
) {
    private var pendingMinutes = 0L
    private var sampling = false

    fun sample() = sampleMinutes(settings().sampleTicks / TICKS_PER_MINUTE)

    fun sampleMinutes(activeMinutes: Long) {
        require(activeMinutes > 0L) { "Sampled active minutes must be positive" }
        pendingMinutes = Math.addExact(pendingMinutes, activeMinutes)
        if (sampling) return
        startSample()
    }

    private fun startSample() {
        sampling = true
        val minutes = pendingMinutes.also { pendingMinutes = 0L }
        val current = settings()
        val collection = current.collection
        val token = tasks.token()
        val players = server.onlinePlayers.toTypedArray().iterator()
        val captured = arrayListOf<RankSamplePlayer>()
        nativeWork.submit({ tasks.isCurrent(token) }) {
            if (players.hasNext()) {
                val player = players.next()
                if (player.isOnline && collection.allowsWorld(player.world.name) && ProgressEventRules.activeSample(
                        player.gameMode, player.idleDuration.seconds.coerceAtLeast(0), current.maximumIdleSeconds,
                    )) {
                    val gameplay = collection.allows(player.gameMode.name, player.world.name)
                    val location = player.location
                    val wealth = if (gameplay && collection.wealthEnabled) {
                        economy?.getBalance(player)?.takeIf { it.isFinite() && it >= 0.0 }?.let { floor(it).toLong() }
                    } else null
                    captured += RankSamplePlayer(player.uniqueId, player.world.uid, location.x, location.y, location.z, gameplay, wealth)
                }
            }
            !players.hasNext()
        }.thenCompose {
            CompletableFuture.supplyAsync({
                val nearby = if (collection.communityEnabled) communityNeighbors(captured, current.communityRadiusBlocks) else emptyMap()
                captured.map { player -> player to (nearby[player.id] ?: 0) }
            }, worker)
        }.whenCompleteSync(tasks, token) { planned, failure ->
            if (failure != null) finishSample(failure) else {
                val results = planned.orEmpty().iterator()
                nativeWork.submit({ tasks.isCurrent(token) }) {
                    if (results.hasNext()) {
                        val (player, neighbors) = results.next()
                        if (server.getPlayer(player.id)?.isOnline == true) {
                            if (collection.activeEnabled) modifier.recordCounter(player.id, ProgressMetric.ACTIVE_MINUTES, minutes, mapOf("active" to minutes))
                            if (player.gameplay && collection.communityEnabled && neighbors >= collection.communityMinimumNearbyPlayers) {
                                modifier.recordCounter(player.id, ProgressMetric.COMMUNITY_MINUTES, minutes, mapOf("community" to minutes))
                            }
                            player.wealth?.let { buffer.recordMaximum(player.id, ProgressMetric.WEALTH_PEAK, it) }
                        }
                    }
                    !results.hasNext()
                }.whenCompleteSync(tasks, token) { _, applyFailure -> finishSample(applyFailure) }
            }
        }
    }

    private fun finishSample(failure: Throwable?) {
        sampling = false
        if (failure != null) onFailure(failure)
        if (pendingMinutes > 0L) startSample()
    }

    private companion object { const val TICKS_PER_MINUTE = 1_200L }
}

internal data class RankSamplePlayer(
    val id: UUID, val world: UUID, val x: Double, val y: Double, val z: Double,
    val gameplay: Boolean, val wealth: Long?,
)

/** Only immutable values cross the worker boundary. The exact native 3D distance predicate is retained. */
internal fun communityNeighbors(players: List<RankSamplePlayer>, radius: Double): Map<UUID, Int> {
    data class Cell(val world: UUID, val x: Long, val z: Long)
    fun cell(player: RankSamplePlayer) = Cell(player.world, floor(player.x / radius).toLong(), floor(player.z / radius).toLong())
    val eligible = players.filter(RankSamplePlayer::gameplay)
    val cells = eligible.groupBy(::cell)
    val squared = radius * radius
    return eligible.associate { player ->
        val origin = cell(player)
        var count = 0
        for (x in -1L..1L) for (z in -1L..1L) {
            cells[Cell(origin.world, origin.x + x, origin.z + z)].orEmpty().forEach { other ->
                if (other.id != player.id) {
                    val dx = other.x - player.x; val dy = other.y - player.y; val dz = other.z - player.z
                    if (dx * dx + dy * dy + dz * dz <= squared) count++
                }
            }
        }
        player.id to count
    }
}
