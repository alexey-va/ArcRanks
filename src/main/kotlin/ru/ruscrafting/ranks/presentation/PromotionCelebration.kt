package ru.ruscrafting.ranks.presentation

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.title.Title
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Particle
import org.bukkit.Server
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Firework
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import ru.arc.core.LifecycleTaskScope
import ru.ruscrafting.ranks.config.ArcRanksConfigSnapshot
import ru.ruscrafting.ranks.config.CelebrationColor
import ru.ruscrafting.ranks.config.CelebrationFireworkSettings
import ru.ruscrafting.ranks.config.CelebrationParticleSettings
import ru.ruscrafting.ranks.domain.RankId
import java.time.Duration
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class PromotionCelebrationProfile(
    val particleCount: Int,
    val fireworkCount: Int,
    val networkBroadcast: Boolean,
) {
    companion object {
        fun forOrder(order: Int): PromotionCelebrationProfile = when (order) {
            in 1..3 -> PromotionCelebrationProfile(12, 0, false)
            in 4..6 -> PromotionCelebrationProfile(24, 1, false)
            in 7..9 -> PromotionCelebrationProfile(36, 2, true)
            else -> throw IllegalArgumentException("Rank order is outside the catalog: $order")
        }
    }
}

class PromotionCelebration(
    private val server: Server,
    private val configuration: () -> ArcRanksConfigSnapshot,
    private val tasks: LifecycleTaskScope,
    private val onPresented: (UUID, RankId) -> Unit = { _, _ -> },
) : Listener {
    fun celebrate(playerId: UUID, rankId: RankId) {
        tasks.runSync {
            val player = server.getPlayer(playerId) ?: return@runSync
            val snapshot = configuration()
            val settings = snapshot.settings.celebration
            val rank = snapshot.ranks.catalog.require(rankId)
            val profile = settings.profile(rank.order)
            val rankName = snapshot.locale.render(rank.displayNameKey, player)
            if (settings.enabled) {
                if (settings.title.enabled) {
                    player.showTitle(
                        Title.title(
                            snapshot.locale.render("celebration.title", player, mapOf("rank" to rankName)),
                            snapshot.locale.render("celebration.subtitle", player),
                            Title.Times.times(
                                Duration.ofMillis(settings.title.fadeInMillis),
                                Duration.ofMillis(settings.title.stayMillis),
                                Duration.ofMillis(settings.title.fadeOutMillis),
                            ),
                        ),
                    )
                }
                if (settings.sound.enabled) {
                    player.playSound(
                        player.location,
                        Sound.valueOf(settings.sound.type),
                        SoundCategory.valueOf(settings.sound.category),
                        settings.sound.volume,
                        settings.sound.pitch,
                    )
                }
                if (settings.particles.enabled && profile.particleCount > 0) {
                    particleRing(
                        player.location.add(0.0, settings.particles.originYOffset, 0.0),
                        profile.particleCount,
                        settings.particles,
                    )
                }
                if (settings.fireworks.enabled) repeat(profile.fireworkCount) { index ->
                    tasks.runLater(index * settings.fireworks.delayTicks) {
                        if (configuration().settings.celebration != settings) return@runLater
                        val current = server.getPlayer(playerId) ?: return@runLater
                        visualFirework(
                            current.location.add(0.0, settings.fireworks.originYOffset, 0.0),
                            index,
                            settings.fireworks,
                        )
                    }
                }
                if (profile.networkBroadcast) broadcast(player.name, rankName, player, snapshot)
            }
            onPresented(playerId, rankId)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onFireworkDamage(event: EntityDamageByEntityEvent) {
        val firework = event.damager as? Firework ?: return
        if (VISUAL_TAG in firework.scoreboardTags) event.isCancelled = true
    }

    private fun particleRing(
        origin: org.bukkit.Location,
        count: Int,
        settings: CelebrationParticleSettings,
    ) {
        val world = origin.world ?: return
        val dust = Particle.DustOptions(settings.color.bukkit(), settings.size)
        repeat(count) { index ->
            val angle = 2.0 * PI * index / count
            world.spawnParticle(
                Particle.DUST,
                origin.x + cos(angle) * settings.radius,
                origin.y + (index % 3) * settings.verticalStep,
                origin.z + sin(angle) * settings.radius,
                1,
                0.0,
                0.0,
                0.0,
                0.0,
                dust,
            )
        }
    }

    private fun visualFirework(
        origin: org.bukkit.Location,
        index: Int,
        settings: CelebrationFireworkSettings,
    ) {
        val world = origin.world ?: return
        val direction = if (index % 2 == 0) -1.0 else 1.0
        world.spawn(origin.add(direction * settings.horizontalOffset, 0.0, 0.0), Firework::class.java) { firework ->
            firework.addScoreboardTag(VISUAL_TAG)
            firework.fireworkMeta = firework.fireworkMeta.apply {
                power = 0
                addEffect(
                    FireworkEffect.builder()
                        .with(FireworkEffect.Type.valueOf(settings.type))
                        .withColor(settings.colors.map(CelebrationColor::bukkit))
                        .apply {
                            if (settings.fadeColors.isNotEmpty()) withFade(settings.fadeColors.map(CelebrationColor::bukkit))
                        }
                        .trail(settings.trail)
                        .flicker(settings.flicker)
                        .build()
                )
            }
        }
    }

    private fun broadcast(
        playerName: String,
        rankName: net.kyori.adventure.text.Component,
        audience: org.bukkit.entity.Player,
        snapshot: ArcRanksConfigSnapshot,
    ) {
        val message = snapshot.locale.render(
            "celebration.broadcast",
            audience,
            mapOf("player" to snapshot.locale.text(playerName), "rank" to rankName),
        )
        val plain = PlainTextComponentSerializer.plainText().serialize(message).replace('\n', ' ').replace('\r', ' ')
        val dispatched = server.dispatchCommand(server.consoleSender, "x -servers:all cmi broadcast &6✦ &e$plain")
        if (!dispatched) server.broadcast(message)
    }

    private companion object {
        const val VISUAL_TAG = "arcranks_visual"
    }
}

private fun CelebrationColor.bukkit(): Color = Color.fromRGB(red, green, blue)
