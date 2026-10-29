package ru.ruscrafting.ranks.presentation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Server
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.config.ArcRanksConfigSnapshot
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.config.CelebrationColor
import ru.ruscrafting.ranks.text.RankLocale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class PromotionCelebrationPreviewTest : StringSpec({
    "preview all waits for each full scene duration before its gap" {
        val fixture = fixture(catalog("first" to 8, "second" to 30, "third" to 49))

        fixture.celebration.previewAll(fixture.player) shouldBe 3
        fixture.scheduler.executeImmediate()
        fixture.events shouldBe listOf("preview:first")

        fixture.scheduler.tick(21)
        fixture.events shouldBe listOf("preview:first")
        fixture.scheduler.tick(1)
        fixture.events shouldBe listOf("preview:first", "preview:second")

        fixture.scheduler.tick(43)
        fixture.events shouldBe listOf("preview:first", "preview:second")
        fixture.scheduler.tick(1)
        fixture.events shouldBe listOf("preview:first", "preview:second", "preview:third")
        fixture.celebration.close()
    }

    "teleport cancels every pending preview callback" {
        val fixture = fixture(catalog("first" to 8, "second" to 30))
        fixture.celebration.previewAll(fixture.player)
        val event = mockk<PlayerTeleportEvent>(relaxed = true)
        every { event.player } returns fixture.player

        fixture.celebration.onTeleport(event)
        fixture.scheduler.executeImmediate()
        fixture.scheduler.tick(100)

        fixture.events shouldBe emptyList()
        fixture.celebration.close()
    }

    "quit cancels pending preview callbacks" {
        val fixture = fixture(catalog("first" to 8, "second" to 30))
        fixture.celebration.previewAll(fixture.player)
        val event = mockk<PlayerQuitEvent>(relaxed = true)
        every { event.player } returns fixture.player

        fixture.celebration.onQuit(event)
        fixture.scheduler.executeImmediate()
        fixture.scheduler.tick(100)

        fixture.events shouldBe emptyList()
        fixture.celebration.close()
    }

    "closing the celebration cancels pending preview callbacks" {
        val fixture = fixture(catalog("first" to 8, "second" to 30))
        fixture.celebration.previewAll(fixture.player)

        fixture.celebration.close()
        fixture.scheduler.executeImmediate()
        fixture.scheduler.tick(100)

        fixture.events shouldBe emptyList()
    }

    "a repeated preview all replaces the older queue" {
        val fixture = fixture(catalog("first" to 8, "second" to 30))

        fixture.celebration.previewAll(fixture.player)
        fixture.celebration.previewAll(fixture.player)
        fixture.scheduler.executeImmediate()
        fixture.events shouldBe listOf("preview:first")

        fixture.scheduler.tick(22)
        fixture.events shouldBe listOf("preview:first", "preview:second")
        fixture.celebration.close()
    }

    "a stale configuration generation stops the remaining preview queue" {
        val fixture = fixture(catalog("first" to 8, "second" to 30))

        fixture.celebration.previewAll(fixture.player)
        fixture.scheduler.executeImmediate()
        fixture.events shouldBe listOf("preview:first")

        fixture.replaceSnapshot(2)
        fixture.scheduler.tick(22)

        fixture.events shouldBe listOf("preview:first")
        fixture.celebration.close()
    }

    "active casts retire their displays on replacement, teleport, death, quit, reload and close" {
        MockBukkitTestRuntime.open().use {
            mockkConstructor(CelebrationDisplayRenderer::class)
            every { anyConstructed<CelebrationDisplayRenderer>().spawn(any(), any(), any(), any()) } returns Unit
            every { anyConstructed<CelebrationDisplayRenderer>().render(any(), any(), any()) } returns Unit
            every { anyConstructed<CelebrationDisplayRenderer>().close() } returns Unit
            val fixture = fixture(catalog("first" to 60).copy(enabled = true))
            try {
                fun start() {
                    fixture.celebration.preview(fixture.player, "first") shouldBe true
                    fixture.scheduler.executeImmediate()
                }
                start()
                start()
                verify(exactly = 1) { anyConstructed<CelebrationDisplayRenderer>().close() }

                val teleport = mockk<PlayerTeleportEvent> { every { player } returns fixture.player }
                fixture.celebration.onTeleport(teleport)
                verify(exactly = 2) { anyConstructed<CelebrationDisplayRenderer>().close() }

                start()
                val death = mockk<PlayerDeathEvent> { every { entity } returns fixture.player }
                fixture.celebration.onDeath(death)
                verify(exactly = 3) { anyConstructed<CelebrationDisplayRenderer>().close() }

                start()
                val quit = mockk<PlayerQuitEvent> { every { player } returns fixture.player }
                fixture.celebration.onQuit(quit)
                verify(exactly = 4) { anyConstructed<CelebrationDisplayRenderer>().close() }

                start()
                fixture.replaceSnapshot(2)
                fixture.scheduler.tick(2)
                verify(exactly = 5) { anyConstructed<CelebrationDisplayRenderer>().close() }

                start()
                fixture.celebration.close()
                fixture.scheduler.tick(100)
                verify(exactly = 6) { anyConstructed<CelebrationDisplayRenderer>().close() }
            } finally {
                fixture.celebration.close()
                unmockkConstructor(CelebrationDisplayRenderer::class)
            }
        }
    }
})

private class PreviewFixture(
    val celebration: PromotionCelebration,
    val scheduler: TestTaskScheduler,
    val player: Player,
    val events: MutableList<String>,
    private val replace: (Long) -> Unit,
) {
    fun replaceSnapshot(generation: Long) = replace(generation)
}

private fun fixture(catalog: CelebrationCatalog): PreviewFixture {
    val scheduler = TestTaskScheduler()
    val tasks = LifecycleTaskScope(scheduler)
    val player = mockk<Player>(relaxed = true)
    val world = mockk<World>()
    every { player.uniqueId } returns UUID.randomUUID()
    every { player.isOnline } returns true
    every { player.isDead } returns false
    every { player.world } returns world
    every { player.location } returns Location(world, 0.0, 70.0, 0.0)
    val plugin = mockk<Plugin>(relaxed = true)
    val server = mockk<Server>(relaxed = true)
    every { server.onlinePlayers } returns listOf(player)
    val events = mutableListOf<String>()
    val snapshot = AtomicReference(snapshot(catalog, 1))
    val celebration = PromotionCelebration(plugin, server, snapshot::get, tasks, mockk(relaxed = true)) { _, kind, scene ->
        events += "$kind:$scene"
    }
    return PreviewFixture(celebration, scheduler, player, events) { generation ->
        snapshot.set(snapshot(catalog, generation))
    }
}

private fun snapshot(catalog: CelebrationCatalog, generation: Long): ArcRanksConfigSnapshot {
    val settings = mockk<ArcRanksSettings>()
    every { settings.celebration } returns catalog
    val locale = mockk<RankLocale>()
    every { locale.render(any(), any(), any()) } returns Component.empty()
    every { locale.text(any()) } returns Component.empty()
    val snapshot = mockk<ArcRanksConfigSnapshot>()
    every { snapshot.generation } returns generation
    every { snapshot.settings } returns settings
    every { snapshot.locale } returns locale
    return snapshot
}

private fun catalog(vararg scenes: Pair<String, Int>): CelebrationCatalog {
    val sceneSettings = scenes.associate { (id, duration) ->
        id to CelebrationSceneSettings(
            recipe = CelebrationRecipe.BURST,
            durationTicks = duration,
            primaryColor = CelebrationColor(85, 217, 139),
            secondaryColor = CelebrationColor(255, 240, 216),
        )
    }
    val first = scenes.first().first
    return CelebrationCatalog(
        enabled = false,
        scenes = sceneSettings,
        routes = CelebrationRoutes(
            questDefault = first,
            questRare = first,
            questAdvanced = first,
            questById = emptyMap(),
            rankDefault = first,
            rankById = emptyMap(),
        ),
    )
}
