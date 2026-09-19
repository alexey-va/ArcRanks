package ru.ruscrafting.ranks.presentation

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.title.Title
import net.Zrips.CMILib.Advancements.CMIAdvancement
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Server
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Entity
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.ScheduledTask
import ru.ruscrafting.ranks.config.ArcRanksConfigSnapshot
import ru.ruscrafting.ranks.config.CelebrationColor
import ru.ruscrafting.ranks.domain.RankId
import ru.ruscrafting.ranks.reward.QuestRewardSummary
import java.time.Duration
import java.util.UUID

class PromotionCelebration(
    private val plugin: Plugin,
    private val server: Server,
    private val configuration: () -> ArcRanksConfigSnapshot,
    private val tasks: LifecycleTaskScope,
    private val onPresented: (UUID, String, String) -> Unit = { _, _, _ -> },
) : Listener, AutoCloseable {
    private val active = mutableMapOf<UUID, ActiveCelebration>()
    private val previews = mutableMapOf<UUID, MutableList<ScheduledTask>>()
    private val legacy = LegacyComponentSerializer.legacySection()

    fun celebrate(playerId: UUID, rankId: RankId) {
        tasks.runSync {
            val player = server.getPlayer(playerId) ?: return@runSync
            cancelPreviews(playerId)
            val snapshot = configuration()
            val rank = snapshot.ranks.catalog.require(rankId)
            val scene = snapshot.settings.celebration.forRank(rankId.value)
            val values = mapOf(
                "rank" to snapshot.locale.render(rank.displayNameKey, player),
                "quest" to snapshot.locale.text("—"),
                "scene" to snapshot.locale.text(scene.id),
            )
            present(player, scene, values, snapshot)
            if (snapshot.settings.celebration.enabled && scene.settings.networkBroadcast) {
                broadcast(player.name, values.getValue("rank"), player, snapshot)
            }
            onPresented(player.uniqueId, "rank", scene.id)
        }
    }

    fun celebrateQuest(player: Player, summary: QuestRewardSummary) {
        tasks.runSync {
            if (!player.isOnline) return@runSync
            cancelPreviews(player.uniqueId)
            val snapshot = configuration()
            val scene = snapshot.settings.celebration.forQuest(
                CelebrationQuestContext(summary.questId, summary.rare, summary.advanced),
            )
            val values = mapOf(
                "rank" to snapshot.locale.text("—"),
                "quest" to snapshot.locale.render("daily.${summary.textId}.name", player),
                "scene" to snapshot.locale.text(scene.id),
            )
            present(player, scene, values, snapshot)
            onPresented(player.uniqueId, "quest", scene.id)
        }
    }

    fun sceneIds(): List<String> = configuration().settings.celebration.sceneIds()

    fun preview(player: Player, sceneId: String): Boolean {
        cancelPreviews(player.uniqueId)
        return previewScene(player, sceneId)
    }

    private fun previewScene(player: Player, sceneId: String): Boolean {
        val snapshot = configuration()
        val scene = runCatching { snapshot.settings.celebration.scene(sceneId) }.getOrNull() ?: return false
        val values = mapOf(
            "rank" to snapshot.locale.render("celebration.preview-value", player),
            "quest" to snapshot.locale.render("celebration.preview-value", player),
            "scene" to snapshot.locale.text(scene.id),
        )
        present(player, scene, values, snapshot)
        onPresented(player.uniqueId, "preview", scene.id)
        return true
    }

    fun previewAll(player: Player): Int {
        cancelPreviews(player.uniqueId)
        val snapshot = configuration()
        val catalog = snapshot.settings.celebration
        val ids = catalog.sceneIds()
        val queue = mutableListOf<ScheduledTask>()
        previews[player.uniqueId] = queue
        var delay = 0L
        ids.forEachIndexed { index, id ->
            val scheduled = tasks.runLater(delay) {
                if (previews[player.uniqueId] !== queue) return@runLater
                if (!player.isOnline || configuration().generation != snapshot.generation) {
                    cancelPreviews(player.uniqueId)
                    return@runLater
                }
                previewScene(player, id)
                if (index == ids.lastIndex) previews.remove(player.uniqueId)
            } ?: return@forEachIndexed
            queue += scheduled
            delay += catalog.scene(id).settings.durationTicks + PREVIEW_GAP_TICKS
        }
        return ids.size
    }

    private fun present(
        player: Player,
        scene: ResolvedCelebrationScene,
        values: Map<String, Component>,
        snapshot: ArcRanksConfigSnapshot,
    ) {
        if (!snapshot.settings.celebration.enabled) return
        cancel(player.uniqueId)
        val settings = scene.settings
        personalEffects(player, settings, values, snapshot)
        val initialOrigin = player.location.clone()
        val initialViewers = nearbyViewers(player, initialOrigin, settings)
        val visibleViewers = initialViewers.mapTo(hashSetOf()) { it.uniqueId }
        val renderer = CelebrationDisplayRenderer(settings, initialOrigin.yaw)
        val text = if (settings.display.type == CelebrationDisplayType.TEXT) {
            snapshot.locale.render(settings.display.textKey, player, values)
        } else Component.empty()
        renderer.spawn(initialOrigin, text)
        val entities = renderer.entities.toMutableList<Entity>()
        initialViewers.forEach { viewer -> entities.forEach { viewer.showEntity(plugin, it) } }
        var tick = 0
        var animation: ScheduledTask? = null
        val scheduled = tasks.runTimer(0, PARTICLE_PERIOD_TICKS) {
            val task = animation ?: return@runTimer
            val current = active[player.uniqueId]
            if (current == null || current.task !== task || configuration().generation != snapshot.generation ||
                !player.isOnline || player.world !== initialOrigin.world
            ) {
                if (current?.task === task) cancel(player.uniqueId) else task.cancel()
                return@runTimer
            }
            if (tick > settings.durationTicks) {
                cancel(player.uniqueId)
                return@runTimer
            }
            val origin = if (settings.display.followPlayer) player.location.clone() else initialOrigin.clone()
            val viewers = reconcileDisplayViewers(player, origin, settings, entities, visibleViewers)
            try {
                renderer.render(origin, tick)
                particleFrame(origin, initialOrigin.yaw, viewers, settings, tick)
                phaseSound(player, settings, tick)
                maybeFirework(origin, settings, tick, entities, viewers)
            } catch (failure: Exception) {
                cancel(player.uniqueId)
                plugin.logger.log(java.util.logging.Level.WARNING, "Celebration failed: scene=${scene.id}, player=${player.uniqueId}, tick=$tick", failure)
                return@runTimer
            }
            tick += PARTICLE_PERIOD_TICKS.toInt()
        }
        if (scheduled == null) {
            entities.filter(Entity::isValid).forEach(Entity::remove)
            return
        }
        animation = scheduled
        active[player.uniqueId] = ActiveCelebration(scheduled, entities)
    }

    private fun personalEffects(
        player: Player,
        settings: CelebrationSceneSettings,
        values: Map<String, Component>,
        snapshot: ArcRanksConfigSnapshot,
    ) {
        if (settings.titleKey.isNotBlank()) {
            val subtitle = if (settings.subtitleKey.isBlank()) Component.empty()
            else snapshot.locale.render(settings.subtitleKey, player, values)
            player.showTitle(
                Title.title(
                    snapshot.locale.render(settings.titleKey, player, values),
                    subtitle,
                    Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(1_900), Duration.ofMillis(450)),
                ),
            )
        }
        if (settings.actionBarKey.isNotBlank()) {
            player.sendActionBar(snapshot.locale.render(settings.actionBarKey, player, values))
        }
        player.playSound(
            player.location,
            Sound.valueOf(settings.soundType),
            SoundCategory.valueOf(settings.soundCategory),
            settings.soundVolume,
            settings.soundPitch,
        )
        if (settings.toast.enabled && server.pluginManager.isPluginEnabled("CMILib")) {
            val toast = settings.toast
            val item = ItemStack.of(checkNotNull(Material.matchMaterial(toast.material)))
            if (toast.customModelData > 0) item.editMeta { it.setCustomModelData(toast.customModelData) }
            CMIAdvancement()
                .setAnnounce(false)
                .setToast(true)
                .setHidden(false)
                .setItem(item)
                .setTitle(legacy.serialize(snapshot.locale.render(toast.key, player, values)))
                .setDescription("")
                .show(player)
        }
    }

    private fun nearbyViewers(
        owner: Player,
        origin: Location,
        settings: CelebrationSceneSettings,
    ): List<Player> = buildList {
        add(owner)
        server.onlinePlayers.asSequence()
            .filter { it.uniqueId != owner.uniqueId && it.world === origin.world }
            .filter { it.location.distanceSquared(origin) <= settings.sharedRadiusBlocks * settings.sharedRadiusBlocks }
            .sortedBy { it.location.distanceSquared(origin) }
            .take(settings.maximumViewers - 1)
            .forEach(::add)
    }

    private fun particleFrame(
        origin: Location,
        initialYaw: Float,
        viewers: List<Player>,
        settings: CelebrationSceneSettings,
        tick: Int,
    ) {
        val intensity = CelebrationChoreography.envelope(CelebrationChoreography.progress(tick, settings.durationTicks))
        if (intensity <= 0.0) return
        val points = CelebrationChoreography.particles(settings, tick)
        val angle = Math.toRadians(initialYaw.toDouble())
        val cos = kotlin.math.cos(angle)
        val sin = kotlin.math.sin(angle)
        val onlineViewers = viewers.filter(Player::isOnline)
        points.forEachIndexed { index, point ->
            val color = if (index % 2 == 0) settings.primaryColor else settings.secondaryColor
            val dust = Particle.DustTransition(color.bukkit(), settings.secondaryColor.bukkit(), (settings.particleSize * intensity).toFloat().coerceAtLeast(0.1f))
            onlineViewers.forEach { viewer ->
                viewer.spawnParticle(
                    Particle.DUST_COLOR_TRANSITION,
                    origin.x + point.x * cos - point.z * sin,
                    origin.y + point.y,
                    origin.z + point.x * sin + point.z * cos,
                    1,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    dust,
                )
            }
        }
    }

    private fun reconcileDisplayViewers(
        owner: Player,
        origin: Location,
        settings: CelebrationSceneSettings,
        entities: List<Entity>,
        visibleViewers: MutableSet<UUID>,
    ): List<Player> {
        val viewers = nearbyViewers(owner, origin, settings)
        val allowed = viewers.mapTo(hashSetOf()) { it.uniqueId }
        if (allowed != visibleViewers) {
            server.onlinePlayers
                .filter { it.uniqueId in visibleViewers && it.uniqueId !in allowed }
                .forEach { viewer ->
                    entities.filter(Entity::isValid).forEach { entity -> viewer.hideEntity(plugin, entity) }
                }
            viewers
                .filter { it.uniqueId !in visibleViewers }
                .forEach { viewer ->
                    entities.filter(Entity::isValid).forEach { entity -> viewer.showEntity(plugin, entity) }
                }
            visibleViewers.clear()
            visibleViewers.addAll(allowed)
        }
        return viewers
    }

    private fun phaseSound(player: Player, settings: CelebrationSceneSettings, tick: Int) {
        val cue = CelebrationChoreography.cueTicks(settings.durationTicks).indexOf(tick)
        if (cue < 0 || settings.soundVolume == 0f) return
        val sound = when (cue) {
            0 -> Sound.BLOCK_AMETHYST_BLOCK_RESONATE
            1 -> Sound.BLOCK_BEACON_ACTIVATE
            else -> Sound.BLOCK_AMETHYST_BLOCK_CHIME
        }
        val pitch = (settings.soundPitch * when (cue) { 0 -> 0.8f; 1 -> 1.0f; else -> 1.35f }).coerceIn(0.5f, 2.0f)
        player.playSound(player.location, sound, SoundCategory.valueOf(settings.soundCategory), settings.soundVolume * 0.55f, pitch)
    }

    private fun maybeFirework(
        origin: Location,
        settings: CelebrationSceneSettings,
        tick: Int,
        entities: MutableList<Entity>,
        viewers: List<Player>,
    ) {
        CelebrationChoreography.fireworkTicks(settings.durationTicks, settings.fireworkCount)
            .forEachIndexed { index, launchTick ->
                if (tick == launchTick) spawnFirework(origin, settings, index, entities, viewers)
            }
    }

    private fun spawnFirework(
        origin: Location,
        settings: CelebrationSceneSettings,
        index: Int,
        entities: MutableList<Entity>,
        viewers: List<Player>,
    ) {
        val angle = index * Math.PI * 2.0 / settings.fireworkCount.coerceAtLeast(1)
        val launch = origin.clone().add(
            kotlin.math.cos(angle) * settings.radius * 0.65,
            settings.display.yOffset + 0.25,
            kotlin.math.sin(angle) * settings.radius * 0.65,
        )
        val firework = origin.world.spawn(launch, Firework::class.java) { entity ->
            entity.addScoreboardTag(VISUAL_TAG)
            entity.isPersistent = false
            entity.isVisibleByDefault = false
            entity.fireworkMeta = entity.fireworkMeta.apply {
                power = 0
                addEffect(
                    FireworkEffect.builder()
                        .with(FireworkEffect.Type.valueOf(settings.fireworkType))
                        .withColor(settings.primaryColor.bukkit(), settings.secondaryColor.bukkit())
                        .trail(settings.fireworkTrail)
                        .flicker(settings.fireworkFlicker)
                        .build(),
                )
            }
        }
        entities += firework
        viewers.forEach { it.showEntity(plugin, firework) }
        tasks.runLater(2) { if (firework.isValid) firework.detonate() }
    }

    private fun broadcast(playerName: String, rankName: Component, audience: Player, snapshot: ArcRanksConfigSnapshot) {
        val message = snapshot.locale.render(
            "celebration.broadcast",
            audience,
            mapOf("player" to snapshot.locale.text(playerName), "rank" to rankName),
        )
        val plain = PlainTextComponentSerializer.plainText().serialize(message).replace('\n', ' ').replace('\r', ' ')
        val dispatched = server.dispatchCommand(server.consoleSender, "x -servers:all cmi broadcast &6✦ &e$plain")
        if (!dispatched) server.broadcast(message)
    }

    @EventHandler(ignoreCancelled = true)
    fun onFireworkDamage(event: EntityDamageByEntityEvent) {
        val firework = event.damager as? Firework ?: return
        if (VISUAL_TAG in firework.scoreboardTags) event.isCancelled = true
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        cancelPreviews(event.player.uniqueId)
        cancel(event.player.uniqueId)
    }

    @EventHandler(ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        cancelPreviews(event.player.uniqueId)
        cancel(event.player.uniqueId)
    }

    private fun cancelPreviews(playerId: UUID) {
        previews.remove(playerId)?.forEach(ScheduledTask::cancel)
    }

    private fun cancel(playerId: UUID) {
        val session = active.remove(playerId) ?: return
        session.task.cancel()
        session.entities.filter(Entity::isValid).forEach(Entity::remove)
    }

    override fun close() {
        previews.keys.toList().forEach(::cancelPreviews)
        active.keys.toList().forEach(::cancel)
    }

    private data class ActiveCelebration(val task: ScheduledTask, val entities: MutableList<Entity>)

    companion object {
        internal const val VISUAL_TAG = "arcranks_visual"
        private const val PARTICLE_PERIOD_TICKS = 2L
        private const val PREVIEW_GAP_TICKS = 14L
    }
}

private fun CelebrationColor.bukkit(): Color = Color.fromRGB(red, green, blue)
