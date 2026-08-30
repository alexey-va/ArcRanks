package ru.ruscrafting.ranks.presentation

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.title.Title
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Particle
import org.bukkit.Server
import org.bukkit.Sound
import org.bukkit.entity.Firework
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import ru.arc.core.LifecycleTaskScope
import ru.ruscrafting.ranks.domain.RankCatalog
import ru.ruscrafting.ranks.domain.RankId
import ru.ruscrafting.ranks.text.RankLocale
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
    private val catalog: () -> RankCatalog,
    private val locale: () -> RankLocale,
    private val tasks: LifecycleTaskScope,
    private val onPresented: (UUID, RankId) -> Unit = { _, _ -> },
) : Listener {
    fun celebrate(playerId: UUID, rankId: RankId) {
        tasks.runSync {
            val player = server.getPlayer(playerId) ?: return@runSync
            val rank = catalog().require(rankId)
            val profile = PromotionCelebrationProfile.forOrder(rank.order)
            val rankName = locale().render(rank.displayNameKey, player)
            player.showTitle(
                Title.title(
                    locale().render("celebration.title", player, mapOf("rank" to rankName)),
                    locale().render("celebration.subtitle", player),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofMillis(700)),
                ),
            )
            player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.05f)
            particleRing(player.location.add(0.0, 0.25, 0.0), profile.particleCount)
            repeat(profile.fireworkCount) { index ->
                tasks.runLater(index * FIREWORK_DELAY_TICKS.toLong()) {
                    val current = server.getPlayer(playerId) ?: return@runLater
                    visualFirework(current.location.add(0.0, 0.35, 0.0), index)
                }
            }
            if (profile.networkBroadcast) broadcast(player.name, rankName, player)
            onPresented(playerId, rankId)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onFireworkDamage(event: EntityDamageByEntityEvent) {
        val firework = event.damager as? Firework ?: return
        if (VISUAL_TAG in firework.scoreboardTags) event.isCancelled = true
    }

    private fun particleRing(origin: org.bukkit.Location, count: Int) {
        val world = origin.world ?: return
        val dust = Particle.DustOptions(Color.fromRGB(217, 134, 79), 1.1f)
        repeat(count) { index ->
            val angle = 2.0 * PI * index / count
            world.spawnParticle(
                Particle.DUST,
                origin.x + cos(angle) * 1.35,
                origin.y + (index % 3) * 0.08,
                origin.z + sin(angle) * 1.35,
                1,
                0.0,
                0.0,
                0.0,
                0.0,
                dust,
            )
        }
    }

    private fun visualFirework(origin: org.bukkit.Location, index: Int) {
        val world = origin.world ?: return
        world.spawn(origin.add(if (index == 0) -0.65 else 0.65, 0.0, 0.0), Firework::class.java) { firework ->
            firework.addScoreboardTag(VISUAL_TAG)
            firework.fireworkMeta = firework.fireworkMeta.apply {
                power = 0
                addEffect(
                    FireworkEffect.builder()
                        .with(FireworkEffect.Type.BALL)
                        .withColor(Color.fromRGB(217, 134, 79), Color.fromRGB(244, 189, 106))
                        .withFade(Color.fromRGB(255, 240, 216))
                        .trail(true)
                        .build(),
                )
            }
        }
    }

    private fun broadcast(playerName: String, rankName: net.kyori.adventure.text.Component, audience: org.bukkit.entity.Player) {
        val message = locale().render(
            "celebration.broadcast",
            audience,
            mapOf("player" to locale().text(playerName), "rank" to rankName),
        )
        val plain = PlainTextComponentSerializer.plainText().serialize(message).replace('\n', ' ').replace('\r', ' ')
        val dispatched = server.dispatchCommand(server.consoleSender, "x -servers:all cmi broadcast &6✦ &e$plain")
        if (!dispatched) server.broadcast(message)
    }

    private companion object {
        const val VISUAL_TAG = "arcranks_visual"
        const val FIREWORK_DELAY_TICKS = 12
    }
}
