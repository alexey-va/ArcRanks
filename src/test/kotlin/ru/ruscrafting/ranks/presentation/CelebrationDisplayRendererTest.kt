package ru.ruscrafting.ranks.presentation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.testing.failOnUnsupportedMockBukkitOperation
import kotlin.math.abs
import java.util.function.Consumer

class CelebrationDisplayRendererTest : StringSpec({
    "bounded display cast follows its owner, centers beams and expires together" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                // MockBukkit supplies item/block registries, but its EntityMock lacks viewer visibility.
                val world = recordingDisplayWorld()
                val origin = Location(world, 10.0, 70.0, 10.0, 90f, 35f)
                val settings = CelebrationSceneSettings(
                    recipe = CelebrationRecipe.RIBBON,
                    durationTicks = 60,
                    display = CelebrationDisplaySettings(type = CelebrationDisplayType.ITEM, count = 8, ttlTicks = 40),
                )
                val renderer = CelebrationDisplayRenderer(settings, origin.yaw)
                renderer.spawn(origin, Component.empty())
                renderer.entities.size shouldBe 24
                renderer.entities.forEach { display ->
                    verify(exactly = 1) { display.isPersistent = false }
                    verify(exactly = 1) { display.isVisibleByDefault = false }
                    verify(exactly = 1) { display.setGravity(false) }
                }

                val moved = origin.clone().add(3.0, 1.0, -2.0).apply { yaw = -90f; pitch = -30f }
                renderer.render(moved, 20)
                val blocks = renderer.entities.filterIsInstance<BlockDisplay>()
                val poses = CelebrationChoreography.accents(settings, 20)
                blocks.forEachIndexed { index, display ->
                    display.location.x shouldBe moved.x
                    display.location.yaw shouldBe 0f
                    val transform = display.transformation
                    val center = Vector3f(0.5f).mul(transform.scale)
                    transform.leftRotation.transform(center).add(transform.translation)
                    // Initial yaw=90 maps local (x,y,z) to (-z,y,x), despite the owner's new facing.
                    val expected = poses[index].position
                    (abs(center.x + expected.z) < 1.0e-5) shouldBe true
                    (abs(center.y - expected.y) < 1.0e-5) shouldBe true
                    (abs(center.z - expected.x) < 1.0e-5) shouldBe true
                }
                renderer.entities.filterIsInstance<ItemDisplay>().all {
                    it.transformation.translation == Vector3f()
                } shouldBe true
                renderer.render(moved, 40)
                renderer.entities.none { it.isValid } shouldBe true
            }
        }
    }
})

private fun recordingDisplayWorld(): World {
    val world = mockk<World>()
    every { world.spawn(any<Location>(), ItemDisplay::class.java, any<Consumer<ItemDisplay>>()) } answers {
        recordingDisplay(mockk<ItemDisplay>(relaxed = true), firstArg()).also { thirdArg<Consumer<ItemDisplay>>().accept(it) }
    }
    every { world.spawn(any<Location>(), BlockDisplay::class.java, any<Consumer<BlockDisplay>>()) } answers {
        recordingDisplay(mockk<BlockDisplay>(relaxed = true), firstArg()).also { thirdArg<Consumer<BlockDisplay>>().accept(it) }
    }
    return world
}

private fun <T : Display> recordingDisplay(display: T, origin: Location): T {
    var location = origin.clone()
    var transform = Transformation(Vector3f(), Quaternionf(), Vector3f(1f), Quaternionf())
    var valid = true
    every { display.location } answers { location.clone() }
    every { display.teleport(any<Location>()) } answers { location = firstArg<Location>().clone(); true }
    every { display.transformation = any() } answers { transform = firstArg() }
    every { display.transformation } answers { transform }
    every { display.isValid } answers { valid }
    every { display.remove() } answers { valid = false }
    return display
}
