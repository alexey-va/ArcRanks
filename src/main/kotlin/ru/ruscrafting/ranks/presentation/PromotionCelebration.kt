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
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Firework
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
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
    private val legacy = LegacyComponentSerializer.legacySection()

    fun celebrate(playerId: UUID, rankId: RankId) {
        tasks.runSync {
            val player = server.getPlayer(playerId) ?: return@runSync
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
        val ids = sceneIds()
        ids.forEachIndexed { index, id ->
            tasks.runLater(index * PREVIEW_INTERVAL_TICKS) {
                if (player.isOnline) preview(player, id)
            }
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
        val origin = player.location.clone()
        val viewers = nearbyViewers(player, origin, settings)
        val entities = spawnDisplay(origin, settings, values, snapshot, viewers).toMutableList()
        var tick = 0
        var animation: ScheduledTask? = null
        val scheduled = tasks.runTimer(0, PARTICLE_PERIOD_TICKS) {
            val task = animation ?: return@runTimer
            val current = active[player.uniqueId]
            if (current == null || current.task !== task || configuration().generation != snapshot.generation ||
                !player.isOnline || player.world !== origin.world ||
                player.location.distanceSquared(origin) > MAX_ORIGIN_DRIFT_SQUARED
            ) {
                if (current?.task === task) cancel(player.uniqueId) else task.cancel()
                return@runTimer
            }
            if (tick > settings.durationTicks) {
                cancel(player.uniqueId)
                return@runTimer
            }
            particleFrame(origin, viewers, settings, tick)
            maybeFirework(origin, settings, tick, entities)
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
        viewers: List<Player>,
        settings: CelebrationSceneSettings,
        tick: Int,
    ) {
        val points = CelebrationGeometry.frame(
            settings.recipe,
            tick,
            settings.durationTicks,
            settings.particleCount,
            settings.radius,
            settings.height,
        )
        points.forEachIndexed { index, point ->
            val color = if (index % 2 == 0) settings.primaryColor else settings.secondaryColor
            val dust = Particle.DustOptions(color.bukkit(), settings.particleSize)
            viewers.filter(Player::isOnline).forEach { viewer ->
                viewer.spawnParticle(
                    Particle.DUST,
                    origin.x + point.x,
                    origin.y + point.y,
                    origin.z + point.z,
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

    private fun spawnDisplay(
        origin: Location,
        settings: CelebrationSceneSettings,
        values: Map<String, Component>,
        snapshot: ArcRanksConfigSnapshot,
        viewers: List<Player>,
    ): List<Entity> {
        if (settings.display.type == CelebrationDisplayType.NONE) return emptyList()
        val location = origin.clone().add(0.0, settings.display.yOffset, 0.0)
        val display = when (settings.display.type) {
            CelebrationDisplayType.TEXT -> location.world.spawn(location, TextDisplay::class.java) { entity ->
                entity.text(snapshot.locale.render(settings.display.textKey, viewers.firstOrNull(), values))
                entity.billboard = Display.Billboard.CENTER
                entity.isShadowed = true
                entity.backgroundColor = Color.fromARGB(96, 0, 0, 0)
            }
            CelebrationDisplayType.ITEM -> location.world.spawn(location, ItemDisplay::class.java) { entity ->
                val item = ItemStack.of(checkNotNull(Material.matchMaterial(settings.display.material)))
                if (settings.display.customModelData > 0) item.editMeta { it.setCustomModelData(settings.display.customModelData) }
                entity.setItemStack(item)
                entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                entity.billboard = Display.Billboard.CENTER
            }
            CelebrationDisplayType.NONE -> return emptyList()
        }
        display.apply {
            addScoreboardTag(VISUAL_TAG)
            isPersistent = false
            isInvulnerable = true
            setGravity(false)
            viewRange = (settings.sharedRadiusBlocks / 64.0).toFloat().coerceIn(0.1f, 1.0f)
            brightness = Display.Brightness(15, 15)
            transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(settings.display.scale), Quaternionf())
        }
        val allowed = viewers.mapTo(hashSetOf()) { it.uniqueId }
        server.onlinePlayers.filter { it.uniqueId !in allowed }.forEach { it.hideEntity(plugin, display) }
        tasks.runLater(settings.display.ttlTicks.toLong()) { if (display.isValid) display.remove() }
        return listOf(display)
    }

    private fun maybeFirework(
        origin: Location,
        settings: CelebrationSceneSettings,
        tick: Int,
        entities: MutableList<Entity>,
    ) {
        if (settings.fireworkCount == 0) return
        val interval = (settings.durationTicks / settings.fireworkCount).coerceAtLeast(1)
        if (tick % interval != 0 || tick / interval >= settings.fireworkCount) return
        val index = tick / interval
        val direction = if (index % 2 == 0) -1.0 else 1.0
        val firework = origin.world.spawn(origin.clone().add(direction * 0.65, 0.4, 0.0), Firework::class.java) { entity ->
            entity.addScoreboardTag(VISUAL_TAG)
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
    fun onQuit(event: PlayerQuitEvent) = cancel(event.player.uniqueId)

    @EventHandler(ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) = cancel(event.player.uniqueId)

    private fun cancel(playerId: UUID) {
        val session = active.remove(playerId) ?: return
        session.task.cancel()
        session.entities.filter(Entity::isValid).forEach(Entity::remove)
    }

    override fun close() {
        active.keys.toList().forEach(::cancel)
    }

    private data class ActiveCelebration(val task: ScheduledTask, val entities: MutableList<Entity>)

    private companion object {
        const val VISUAL_TAG = "arcranks_visual"
        const val PARTICLE_PERIOD_TICKS = 2L
        const val PREVIEW_INTERVAL_TICKS = 70L
        const val MAX_ORIGIN_DRIFT_SQUARED = 64.0
    }
}

private fun CelebrationColor.bukkit(): Color = Color.fromRGB(red, green, blue)
