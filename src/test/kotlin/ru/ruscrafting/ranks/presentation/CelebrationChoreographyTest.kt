package ru.ruscrafting.ranks.presentation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

private val CHOREOGRAPHY_DURATIONS = listOf(8, 30, 49, 76, 200)
private const val POSITION_BOUND = 16.0
private const val SCALE_BOUND = 16.0
private const val EPSILON = 1.0e-8

class CelebrationChoreographyTest : StringSpec({
    "every recipe stays finite and bounded throughout supported scene durations" {
        CelebrationRecipe.entries.forEach { recipe ->
            CHOREOGRAPHY_DURATIONS.forEach { duration ->
                val settings = sceneSettings(recipe, duration)
                (0..duration).forEach { tick ->
                    val items = CelebrationChoreography.items(settings, tick)
                    val accents = CelebrationChoreography.accents(settings, tick)
                    val particles = CelebrationChoreography.particles(settings, tick)

                    items.size shouldBe 8
                    accents.size shouldBe CelebrationChoreography.ACCENT_COUNT
                    particles.size shouldBe settings.particleCount
                    items.forEach(::assertBoundedPose)
                    accents.forEach(::assertBoundedPose)
                    particles.forEach(::assertBoundedPoint)
                }
            }
        }
    }

    "item scale envelope is closed at assembly and expiry, including the shorter display TTL" {
        CelebrationRecipe.entries.forEach { recipe ->
            CHOREOGRAPHY_DURATIONS.forEach { duration ->
                val settings = sceneSettings(recipe, duration)
                CelebrationChoreography.items(settings, 0).all(::isZeroScale) shouldBe true
                CelebrationChoreography.items(settings, duration).all(::isZeroScale) shouldBe true
                CelebrationChoreography.items(settings, duration / 2).any { it.scale.x > EPSILON } shouldBe true
            }
        }

        val shorterTtl = sceneSettings(CelebrationRecipe.ASCENSION, duration = 200, ttlTicks = 30)
        CelebrationChoreography.items(shorterTtl, 30).all(::isZeroScale) shouldBe true
        CelebrationChoreography.items(shorterTtl, 31).all(::isZeroScale) shouldBe true
    }

    "ribbon wings mirror, crown separates its ring from rising prongs, and orbit uses two planes" {
        val ribbon = CelebrationChoreography.accents(sceneSettings(CelebrationRecipe.RIBBON, 76), 38)
        (0 until 8).forEach { index ->
            val left = ribbon[index].position
            val right = ribbon[index + 8].position
            (left.x * right.x < 0.0) shouldBe true
            (abs(abs(left.x) - abs(right.x)) < EPSILON) shouldBe true
        }

        val crown = CelebrationChoreography.accents(sceneSettings(CelebrationRecipe.CROWN, 76), 38)
        crown.take(8).all { abs(it.direction.y) < EPSILON } shouldBe true
        crown.drop(8).all { it.direction.y > EPSILON } shouldBe true

        val orbit = CelebrationChoreography.accents(sceneSettings(CelebrationRecipe.ORBIT, 76), 38)
        val innerPlane = orbit.take(8).map(::planeSlope)
        val outerPlane = orbit.drop(8).map(::planeSlope)
        innerPlane.all { it < -EPSILON } shouldBe true
        outerPlane.all { it > EPSILON } shouldBe true
    }

    "cue and firework schedules preserve their requested count after two-tick quantization" {
        CHOREOGRAPHY_DURATIONS.forEach { duration ->
            val cues = CelebrationChoreography.cueTicks(duration)
            cues.size shouldBe cues.distinct().size
            cues.all { it in 0..duration } shouldBe true
            cues.all { it % CelebrationChoreography.FRAME_TICKS == 0 } shouldBe true

            val fireworks = CelebrationChoreography.fireworkTicks(duration, count = 4)
            fireworks.size shouldBe 4
            fireworks.all { it in 0..duration } shouldBe true
            fireworks.all { it % CelebrationChoreography.FRAME_TICKS == 0 } shouldBe true
        }

        val shortDuration = CelebrationChoreography.fireworkTicks(duration = 8, count = 4)
        (shortDuration.distinct().size < shortDuration.size) shouldBe true
        CelebrationChoreography.fireworkTicks(duration = 8, count = 0) shouldBe emptyList()
    }
})

private fun sceneSettings(
    recipe: CelebrationRecipe,
    duration: Int,
    ttlTicks: Int = duration,
): CelebrationSceneSettings = CelebrationSceneSettings(
    recipe = recipe,
    durationTicks = duration,
    radius = 1.8,
    height = 3.5,
    display = CelebrationDisplaySettings(
        type = CelebrationDisplayType.ITEM,
        pattern = CelebrationDisplayPattern.CONSTELLATION,
        count = 8,
        ttlTicks = ttlTicks,
    ),
)

private fun assertBoundedPose(pose: CelebrationPose) {
    val position = pose.position
    val scale = pose.scale
    assertBoundedPoint(position)
    listOf(scale.x, scale.y, scale.z).all { it.isFinite() } shouldBe true
    listOf(scale.x, scale.y, scale.z).all { it in 0.0..SCALE_BOUND } shouldBe true
}

private fun assertBoundedPoint(point: CelebrationPoint) {
    listOf(point.x, point.y, point.z).all { it.isFinite() } shouldBe true
    listOf(point.x, point.y, point.z).all { abs(it) <= POSITION_BOUND } shouldBe true
}

private fun isZeroScale(pose: CelebrationPose): Boolean =
    abs(pose.scale.x) < EPSILON && abs(pose.scale.y) < EPSILON && abs(pose.scale.z) < EPSILON

private fun planeSlope(pose: CelebrationPose): Double {
    (abs(pose.direction.z) > EPSILON) shouldBe true
    return pose.direction.y / pose.direction.z
}
