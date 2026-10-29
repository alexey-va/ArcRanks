package ru.ruscrafting.ranks.presentation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CelebrationDisplayRendererTest : StringSpec({
    "mounted cast retains its handles and feet-relative geometry while the owner moves and crouches" {
        MockBukkitTestRuntime.open().use {
            val packets = RecordingCelebrationDisplays()
            val origin = Location(mockk<World>(), 10.0, 70.0, 10.0, 90f, 35f)
            val owner = viewer(origin)
            val observer = viewer(origin.clone().add(4.0, 0.0, 0.0).apply { yaw = -25f; pitch = -30f })
            val settings = itemScene()
            val renderer = CelebrationDisplayRenderer(packets.displays, settings, origin.yaw)
            renderer.spawn(origin, Component.empty(), owner, listOf(owner, observer))
            val handles = renderer.handles
            // Each client still sees eight items and sixteen shared beams.
            handles.size shouldBe 32
            handles.forEach { display ->
                verify(exactly = 1) { display.attachTo(owner) }
                verify(exactly = 1) { display.isVisibleByDefault = false }
                verify(exactly = 1) { display.billboard = Display.Billboard.FIXED }
                verify(exactly = 1) { display.teleportDuration = 0 }
            }

            val moved = origin.clone().add(3.0, 1.0, -2.0).apply { yaw = -90f; pitch = -30f }
            every { owner.location } returns moved
            every { owner.eyeLocation } returns moved.clone().add(0.0, 1.62, 0.0)
            renderer.render(moved, 20, listOf(owner, observer))
            renderer.handles shouldBe handles
            assertFeetRelativePoses(renderer, settings, 20, 1.8f)
            handles.forEach { display -> verify(exactly = 0) { display.teleport(any()) } }

            every { owner.height } returns 1.5
            renderer.render(moved, 20, listOf(owner, observer))
            assertFeetRelativePoses(renderer, settings, 20, 1.5f)
            renderer.handles shouldBe handles
            val items = handles.filterIsInstance<PacketItemDisplay>()
            items.take(8).forEach { display ->
                verify(exactly = 1) { display.showTo(owner) }
                verify(exactly = 0) { display.showTo(observer) }
            }
            items.drop(8).forEach { display ->
                verify(exactly = 1) { display.showTo(observer) }
                verify(exactly = 0) { display.showTo(owner) }
            }
            renderer.close()
        }
    }

    "text faces each viewer without rotating its mounted trajectory" {
        MockBukkitTestRuntime.open().use {
            val packets = RecordingCelebrationDisplays()
            val origin = Location(mockk<World>(), 10.0, 70.0, 10.0, 90f, 0f)
            val owner = viewer(origin)
            val observer = viewer(origin.clone().apply { yaw = -45f; pitch = 60f })
            val settings = itemScene().copy(display = CelebrationDisplaySettings(
                type = CelebrationDisplayType.TEXT, textKey = "celebration.rank.display", ttlTicks = 40,
            ))
            val renderer = CelebrationDisplayRenderer(packets.displays, settings, origin.yaw)
            renderer.spawn(origin, Component.text("Rank"), owner, listOf(owner, observer))
            renderer.render(origin, 20, listOf(owner, observer))
            val texts = renderer.handles.filterIsInstance<PacketTextDisplay>()
            texts[0].transformation.translation shouldBe texts[1].transformation.translation
            listOf(owner, observer).zip(texts).forEach { (viewer, display) ->
                val eye = viewer.eyeLocation
                val expected = Quaternionf().rotationYXZ(
                    Math.toRadians(180.0 - eye.yaw).toFloat(), Math.toRadians(-eye.pitch.toDouble()).toFloat(), 0f,
                ).transform(Vector3f(0f, 0f, 1f))
                val normal = display.transformation.leftRotation.transform(Vector3f(0f, 0f, 1f))
                (normal.distance(expected) < 1.0e-5) shouldBe true
            }
            renderer.close()
        }
    }

    "stationary cast bounds its audience, joins at the current pose and removes departed or expired handles" {
        MockBukkitTestRuntime.open().use {
            val packets = RecordingCelebrationDisplays()
            val origin = Location(mockk<World>(), 10.0, 70.0, 10.0, 90f, 0f)
            val owner = viewer(origin)
            val observer = viewer(origin.clone().add(4.0, 0.0, 0.0))
            val settings = itemScene().let { it.copy(maximumViewers = 1, display = it.display.copy(followPlayer = false)) }
            val renderer = CelebrationDisplayRenderer(packets.displays, settings, origin.yaw)
            renderer.spawn(origin, Component.empty(), owner, listOf(owner, observer))
            val original = renderer.handles
            original.size shouldBe 24
            original.forEach { display -> verify(exactly = 0) { display.attachTo(any<Player>()) } }

            renderer.render(origin, 20, listOf(observer))
            original.filterIsInstance<PacketItemDisplay>().none { it.isValid } shouldBe true
            renderer.handles.filterIsInstance<PacketBlockDisplay>() shouldBe original.filterIsInstance<PacketBlockDisplay>()
            val ownerId = owner.uniqueId
            renderer.handles.filterIsInstance<PacketBlockDisplay>().forEach { display ->
                verify(exactly = 1) { display.hideFrom(ownerId) }
                verify(exactly = 1) { display.showTo(observer) }
            }
            assertFeetRelativePoses(renderer, settings, 20, 0f)
            renderer.render(origin, 40, listOf(observer))
            renderer.close()
            renderer.handles shouldBe emptyList()
            packets.created.all { !it.isValid } shouldBe true
            packets.created.forEach { display -> verify(exactly = 1) { display.remove() } }
        }
    }

    "partial spawn failure removes the handles already allocated" {
        MockBukkitTestRuntime.open().use {
            val packets = RecordingCelebrationDisplays()
            val origin = Location(mockk<World>(), 10.0, 70.0, 10.0)
            val owner = viewer(origin)
            var items = 0
            every { packets.displays.spawnItem(any(), any()) } answers {
                if (++items == 2) error("isolated display creation failure")
                packets.record(mockk<PacketItemDisplay>(relaxed = true))
            }
            val renderer = CelebrationDisplayRenderer(packets.displays, itemScene(), origin.yaw)
            shouldThrow<IllegalStateException> { renderer.spawn(origin, Component.empty(), owner, listOf(owner)) }
            packets.created.size shouldBe CelebrationChoreography.ACCENT_COUNT + 1
            packets.created.all { !it.isValid } shouldBe true
            renderer.close()
            packets.created.forEach { display -> verify(exactly = 1) { display.remove() } }
        }
    }
})

private fun itemScene() = CelebrationSceneSettings(
    recipe = CelebrationRecipe.RIBBON,
    durationTicks = 60,
    display = CelebrationDisplaySettings(type = CelebrationDisplayType.ITEM, count = 8, ttlTicks = 40),
)

private fun viewer(at: Location): Player = mockk<Player>(relaxed = true) {
    every { uniqueId } returns UUID.randomUUID()
    every { location } answers { at.clone() }
    every { eyeLocation } answers { at.clone().add(0.0, 1.62, 0.0) }
    every { height } returns 1.8
}

private fun assertFeetRelativePoses(renderer: CelebrationDisplayRenderer, settings: CelebrationSceneSettings, tick: Int, height: Float) {
    fun expected(pose: CelebrationPose) = Vector3f(-pose.position.z.toFloat(), pose.position.y.toFloat(), pose.position.x.toFloat())
    renderer.handles.filterIsInstance<PacketBlockDisplay>().forEachIndexed { index, display ->
        val transform = display.transformation
        val center = transform.leftRotation.transform(Vector3f(0.5f).mul(transform.scale))
            .add(transform.translation).add(0f, height, 0f)
        (center.distance(expected(CelebrationChoreography.accents(settings, tick)[index])) < 1.0e-5) shouldBe true
    }
    renderer.handles.filter { it !is PacketBlockDisplay }.forEachIndexed { index, display ->
        val center = Vector3f(display.transformation.translation).add(0f, height, 0f)
        (center.distance(expected(CelebrationChoreography.items(settings, tick)[index % settings.display.count])) < 1.0e-5) shouldBe true
    }
}

private class RecordingCelebrationDisplays {
    val displays = mockk<PaperPacketDisplays>()
    val created = mutableListOf<PacketDisplay>()

    init {
        every { displays.spawnBlock(any(), any()) } answers { record(mockk<PacketBlockDisplay>(relaxed = true)) }
        every { displays.spawnItem(any(), any()) } answers { record(mockk<PacketItemDisplay>(relaxed = true)) }
        every { displays.spawnText(any(), any()) } answers { record(mockk<PacketTextDisplay>(relaxed = true)) }
    }

    fun <T : PacketDisplay> record(display: T): T {
        var transform = Transformation(Vector3f(), Quaternionf(), Vector3f(1f), Quaternionf())
        var valid = true
        every { display.transformation = any() } answers { transform = firstArg() }
        every { display.transformation } answers { transform }
        every { display.isValid } answers { valid }
        every { display.remove() } answers { valid = false }
        created += display
        return display
    }
}
