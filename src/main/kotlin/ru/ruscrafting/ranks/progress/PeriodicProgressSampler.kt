package ru.ruscrafting.ranks.progress

import net.milkbowl.vault.economy.Economy
import org.bukkit.Server
import ru.arc.core.LifecycleTaskScope
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.perk.PerkProgressModifier
import kotlin.math.floor

class PeriodicProgressSampler(
    private val server: Server,
    private val settings: () -> ArcRanksSettings,
    private val buffer: ProgressBuffer,
    private val modifier: PerkProgressModifier,
    private val economy: Economy?,
    private val tasks: LifecycleTaskScope,
) {
    fun start() {
        tasks.runTimer(settings().sampleTicks, settings().sampleTicks) { sample() }
        tasks.runTimerAsync(settings().flushTicks, settings().flushTicks) { buffer.flushAll() }
    }

    fun sample() {
        val online = server.onlinePlayers.filter { player ->
            ProgressEventRules.activeSample(
                player.gameMode,
                player.idleDuration.seconds.coerceAtLeast(0),
                settings().maximumIdleSeconds,
            )
        }
        val activeMinutes = settings().sampleTicks / 1_200L
        val radiusSquared = settings().communityRadiusBlocks * settings().communityRadiusBlocks
        online.forEach { player ->
            modifier.recordCounter(player.uniqueId, ProgressMetric.ACTIVE_MINUTES, activeMinutes)
            val hasCompany = online.any { other ->
                other.uniqueId != player.uniqueId && other.world.uid == player.world.uid &&
                    other.location.distanceSquared(player.location) <= radiusSquared
            }
            if (hasCompany) modifier.recordCounter(player.uniqueId, ProgressMetric.COMMUNITY_MINUTES, activeMinutes)
            economy?.getBalance(player)?.takeIf { it.isFinite() && it >= 0.0 }?.let { balance ->
                buffer.recordMaximum(player.uniqueId, ProgressMetric.WEALTH_PEAK, floor(balance).toLong())
            }
        }
    }
}
