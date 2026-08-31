package ru.ruscrafting.ranks.progress

import net.milkbowl.vault.economy.Economy
import org.bukkit.Server
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
) {
    fun sample() {
        val current = settings()
        sample(current, current.sampleTicks / TICKS_PER_MINUTE)
    }

    fun sampleMinutes(activeMinutes: Long) {
        require(activeMinutes > 0L) { "Sampled active minutes must be positive" }
        sample(settings(), activeMinutes)
    }

    private fun sample(current: ArcRanksSettings, activeMinutes: Long) {
        val collection = current.collection
        val online = server.onlinePlayers.filter { player ->
            collection.allows(player.gameMode.name, player.world.name) && ProgressEventRules.activeSample(
                player.gameMode,
                player.idleDuration.seconds.coerceAtLeast(0),
                current.maximumIdleSeconds,
            )
        }
        val radiusSquared = current.communityRadiusBlocks * current.communityRadiusBlocks
        online.forEach { player ->
            if (collection.activeEnabled) {
                modifier.recordCounter(player.uniqueId, ProgressMetric.ACTIVE_MINUTES, activeMinutes)
            }
            val nearbyPlayers = online.count { other ->
                other.uniqueId != player.uniqueId && other.world.uid == player.world.uid &&
                    other.location.distanceSquared(player.location) <= radiusSquared
            }
            if (collection.communityEnabled && nearbyPlayers >= collection.communityMinimumNearbyPlayers) {
                modifier.recordCounter(player.uniqueId, ProgressMetric.COMMUNITY_MINUTES, activeMinutes)
            }
            if (collection.wealthEnabled) {
                economy?.getBalance(player)?.takeIf { it.isFinite() && it >= 0.0 }?.let { balance ->
                    buffer.recordMaximum(player.uniqueId, ProgressMetric.WEALTH_PEAK, floor(balance).toLong())
                }
            }
        }
    }

    private companion object {
        const val TICKS_PER_MINUTE = 1_200L
    }
}
