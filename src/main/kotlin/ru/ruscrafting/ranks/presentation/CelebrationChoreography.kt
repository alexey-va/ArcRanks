package ru.ruscrafting.ranks.presentation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Positions are relative to the owner's feet; orientation is fixed at scene start. */
data class CelebrationPose(
    val position: CelebrationPoint,
    val scale: CelebrationPoint,
    val direction: CelebrationPoint = CelebrationPoint(0.0, 1.0, 0.0),
    val roll: Double = 0.0,
)

/** Pure choreography: no world reads, entity creation, or per-actor scheduled tasks. */
object CelebrationChoreography {
    const val FRAME_TICKS = 2
    const val ACCENT_COUNT = 16

    fun progress(tick: Int, duration: Int): Double =
        tick.toDouble().div(duration.coerceAtLeast(1)).coerceIn(0.0, 1.0)

    fun envelope(progress: Double): Double =
        smooth(progress / 0.18) * (1.0 - smooth((progress - 0.78) / 0.22))

    fun items(settings: CelebrationSceneSettings, tick: Int): List<CelebrationPose> {
        val duration = minOf(settings.durationTicks, settings.display.ttlTicks)
        val p = progress(tick, duration)
        val assembly = smooth(p / 0.32)
        val release = smooth((p - 0.72) / 0.28)
        return CelebrationGeometry.displayFrame(
            settings.display.pattern, tick, duration, settings.display.count, settings.radius, settings.height,
        ).mapIndexed { index, target ->
            val angle = index * PI * 2.0 / settings.display.count
            val start = CelebrationPoint(cos(angle) * settings.radius * 1.25, -settings.height * 0.25, sin(angle) * settings.radius * 1.25)
            val position = CelebrationPoint(
                mix(start.x, target.x, assembly) * (1.0 + release * 0.35),
                settings.display.yOffset - settings.height * 0.5 + mix(start.y, target.y, assembly) + release * 0.65,
                mix(start.z, target.z, assembly) * (1.0 + release * 0.35),
            )
            val scale = settings.display.scale * envelope(p) * (1.0 + 0.18 * sin(PI * smooth((p - 0.25) / 0.35)))
            CelebrationPose(position, CelebrationPoint(scale, scale, scale), roll = sin(angle + tick * 0.09) * 0.18)
        }
    }

    fun accents(settings: CelebrationSceneSettings, tick: Int): List<CelebrationPose> {
        val p = progress(tick, minOf(settings.durationTicks, settings.display.ttlTicks))
        val open = smooth(p / 0.38)
        val exit = smooth((p - 0.74) / 0.26)
        val visibility = envelope(p)
        val r = settings.radius
        val h = settings.height
        val center = settings.display.yOffset
        val turn = p * PI * 2.0
        return List(ACCENT_COUNT) { index ->
            val spoke = index % 8
            val outer = index >= 8
            val angle = spoke * PI / 4.0
            val width = (if (outer) 0.065 else 0.10) * visibility
            val pose = when (settings.recipe) {
                CelebrationRecipe.BURST -> {
                    // A floor seal opens into eight outward-pointing petals.
                    val radius = r * (0.25 + open * 0.65 + exit * 0.45)
                    if (!outer) ringSegment(angle + turn * 0.12, radius, 0.18 + exit * 0.3, width)
                    else rod(
                        polar(angle, radius * 0.65, center - 0.6 + open * 0.25),
                        polar(angle, radius * 1.12, center + 0.3 + exit * 0.5), width,
                    )
                }
                CelebrationRecipe.HELIX -> {
                    // Two counter-wound crystal staircases climb, then separate.
                    val level = spoke / 7.0
                    val a = level * PI * 2.0 + turn * (if (outer) -1.0 else 1.0) + if (outer) PI else 0.0
                    val radius = r * (0.3 + open * 0.5 + exit * 0.3)
                    val y = center - h * 0.48 + level * h * open + exit * 0.5
                    rod(polar(a, radius, y), polar(a + 0.42, radius, y + h * 0.16), width)
                }
                CelebrationRecipe.CROWN -> {
                    // The eight-piece band locks into place before the prongs rise.
                    val radius = r * (1.2 - open * 0.4 + exit * 0.3)
                    val a = angle + (1.0 - open) * PI * 0.5
                    val y = center + h * 0.12 + (1.0 - open) * 0.7 + exit * 0.4
                    if (!outer) ringSegment(a, radius, y, width)
                    else rod(polar(a, radius, y), polar(a, radius * 1.15, y + h * 0.32 * open), width * 1.4)
                }
                CelebrationRecipe.STARFALL -> {
                    // Staggered meteor shafts settle into a connected overhead star.
                    val fall = smooth((p - spoke * 0.017) / 0.40)
                    val radius = r * (if (spoke % 2 == 0) 0.92 else 0.43)
                    val y = center + (1.0 - fall) * h * 0.55 + exit * 0.6
                    val point = polar(angle, radius * (0.3 + open * 0.7), y)
                    if (outer) rod(point, point.copy(y = point.y + (1.0 - fall) * h * 0.3 + 0.16), width * 1.6)
                    else {
                        val nextRadius = r * (if (spoke % 2 == 0) 0.43 else 0.92)
                        rod(point, polar(angle + PI / 4.0, nextRadius * (0.3 + open * 0.7), y), width * fall)
                    }
                }
                CelebrationRecipe.ORBIT -> {
                    // Two perpendicular, precessing hoops make an armillary sphere.
                    val a = angle + turn * (if (outer) -0.7 else 0.7)
                    val radius = r * (0.2 + open * 0.8 + exit * 0.2)
                    fun point(t: Double): CelebrationPoint {
                        val tilt = if (outer) PI * 0.34 else -PI * 0.34
                        return CelebrationPoint(cos(t) * radius, center + sin(t) * radius * sin(tilt), sin(t) * radius * cos(tilt))
                    }
                    rod(point(a), point(a + PI / 4.0 * 0.82), width)
                }
                CelebrationRecipe.ASCENSION -> {
                    // A low iris feeds a rising, twisting column around the rank badge.
                    val a = angle + turn * 0.55
                    if (!outer) ringSegment(a, r * (0.3 + open * 0.65 + exit * 0.35), 0.22 + exit * h * 0.6, width)
                    else rod(
                        polar(a, r * 0.75, 0.3 + exit * h * 0.45),
                        polar(a + open * 0.7, r * (0.55 - open * 0.25), center + h * 0.35 * open), width,
                    )
                }
                CelebrationRecipe.RIBBON -> {
                    // Two articulated fans unfurl into wings behind the player.
                    val side = if (outer) -1.0 else 1.0
                    val feather = spoke / 7.0
                    val spread = smooth((p - feather * 0.08) / 0.38) * (1.0 - exit * 0.35)
                    val root = CelebrationPoint(side * 0.25, center - 0.35, -0.38)
                    val tip = CelebrationPoint(
                        side * r * (0.35 + spread * (0.5 + feather * 0.65)),
                        center + h * (0.40 - feather * 0.66) * spread + exit * 0.5,
                        -0.38 - sin(feather * PI) * r * 0.25,
                    )
                    rod(root, tip, width * (1.4 - feather * 0.6))
                }
                CelebrationRecipe.FIREWORK_FINALE -> {
                    // A segmented solar halo frames the hero; rays ignite from its rim.
                    val a = angle + turn * 0.18
                    val radius = r * (0.2 + open * 0.62 + exit * 0.35)
                    fun point(t: Double, radiusAt: Double) = CelebrationPoint(cos(t) * radiusAt, center + sin(t) * radiusAt, -0.45)
                    if (!outer) rod(point(a, radius), point(a + PI / 4.0 * 0.84, radius), width)
                    else rod(point(a, radius * 1.1), point(a, radius * (1.1 + smooth((p - 0.30) / 0.25) * 0.45)), width * 1.3)
                }
            }
            pose
        }
    }

    fun particles(settings: CelebrationSceneSettings, tick: Int): List<CelebrationPoint> {
        val ambient = CelebrationGeometry.frame(
            settings.recipe, tick, settings.durationTicks, settings.particleCount, settings.radius, settings.height,
        )
        if (settings.display.type == CelebrationDisplayType.NONE || tick >= settings.display.ttlTicks) return ambient
        val structure = accents(settings, tick)
        return ambient.mapIndexed { index, point ->
            if (index % 3 == 0) point else {
                val pose = structure[(index + tick / FRAME_TICKS) % structure.size]
                val direction = pose.direction
                val length = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
                val along = (((index * 0.37 + tick * 0.07) % 1.0) - 0.5) * pose.scale.y / length
                CelebrationPoint(
                    pose.position.x + direction.x * along,
                    pose.position.y + direction.y * along,
                    pose.position.z + direction.z * along,
                )
            }
        }
    }

    /** Quantized boundaries also work for odd durations and the two-tick renderer. */
    fun cueTicks(duration: Int): List<Int> = listOf(0.22, 0.48, 0.76).map { frameTick(it, duration) }.distinct()

    fun fireworkTicks(duration: Int, count: Int): List<Int> = List(count) { index ->
        frameTick(0.48 + index * 0.30 / (count - 1).coerceAtLeast(1), duration)
    }

    private fun frameTick(fraction: Double, duration: Int): Int =
        (fraction * duration / FRAME_TICKS).roundToInt() * FRAME_TICKS

    private fun ringSegment(angle: Double, radius: Double, y: Double, width: Double): CelebrationPose =
        rod(polar(angle, radius, y), polar(angle + PI / 4.0 * 0.84, radius, y), width)

    private fun rod(from: CelebrationPoint, to: CelebrationPoint, width: Double): CelebrationPose {
        val direction = CelebrationPoint(to.x - from.x, to.y - from.y, to.z - from.z)
        val length = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
        return CelebrationPose(
            CelebrationPoint((from.x + to.x) * 0.5, (from.y + to.y) * 0.5, (from.z + to.z) * 0.5),
            CelebrationPoint(width, length, width),
            if (length < 1.0e-8) CelebrationPoint(0.0, 1.0, 0.0) else direction,
        )
    }

    private fun polar(angle: Double, radius: Double, y: Double) = CelebrationPoint(cos(angle) * radius, y, sin(angle) * radius)
    private fun mix(from: Double, to: Double, progress: Double): Double = from + (to - from) * progress
    private fun smooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
}
