package ru.ruscrafting.ranks.presentation

import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.ruscrafting.ranks.config.CelebrationColor

/** A single bounded display cast. PromotionCelebration owns its task, visibility and cleanup. */
internal class CelebrationDisplayRenderer(
    private val settings: CelebrationSceneSettings,
    private val initialYaw: Float,
) {
    private val items = mutableListOf<Display>()
    private val accents = mutableListOf<BlockDisplay>()
    val entities: List<Display> get() = items + accents

    fun spawn(origin: Location, text: Component) {
        if (settings.display.type == CelebrationDisplayType.NONE) return
        try {
            val initialPoses = CelebrationChoreography.items(settings, 0)
            repeat(settings.display.count) { index ->
                val location = itemLocation(origin, initialPoses[index])
                val display = when (settings.display.type) {
                    CelebrationDisplayType.TEXT -> origin.world.spawn(location, TextDisplay::class.java) { entity ->
                        configure(entity)
                        entity.text(text)
                        entity.billboard = Display.Billboard.CENTER
                        entity.isShadowed = true
                        entity.backgroundColor = Color.fromARGB(70, 12, 16, 28)
                    }
                    CelebrationDisplayType.ITEM -> origin.world.spawn(location, ItemDisplay::class.java) { entity ->
                        configure(entity)
                        val item = ItemStack.of(checkNotNull(Material.matchMaterial(settings.display.material)))
                        if (settings.display.customModelData > 0) item.editMeta { it.setCustomModelData(settings.display.customModelData) }
                        entity.setItemStack(item)
                        entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                        entity.billboard = Display.Billboard.CENTER
                    }
                    CelebrationDisplayType.NONE -> return@repeat
                }
                items += display
                applyPose(display, initialPoses[index], false, 0, index)
            }
            repeat(CelebrationChoreography.ACCENT_COUNT) { index ->
                val color = if (index < 8) settings.primaryColor else settings.secondaryColor
                accents += origin.world.spawn(anchor(origin), BlockDisplay::class.java) { entity ->
                    configure(entity)
                    entity.block = glassFor(color).createBlockData()
                    entity.billboard = Display.Billboard.FIXED
                    entity.glowColorOverride = color.bukkit()
                }
            }
            render(origin, 0)
        } catch (failure: Exception) {
            entities.forEach(Display::remove)
            throw failure
        }
    }

    fun render(origin: Location, tick: Int) {
        if (tick >= settings.display.ttlTicks) {
            entities.filter(Display::isValid).forEach(Display::remove)
            return
        }
        val itemPoses = CelebrationChoreography.items(settings, tick)
        val accentPoses = CelebrationChoreography.accents(settings, tick)
        items.forEachIndexed { index, display ->
            if (display.isValid) {
                moveAnchor(display, itemLocation(origin, itemPoses[index]))
                applyPose(display, itemPoses[index], false, tick, index)
            }
        }
        accents.forEachIndexed { index, display ->
            if (display.isValid) {
                moveAnchor(display, origin)
                applyPose(display, accentPoses[index], true, tick, index)
            }
        }
    }

    private fun configure(display: Display) {
        display.addScoreboardTag(PromotionCelebration.VISUAL_TAG)
        display.isPersistent = false
        display.isInvulnerable = true
        display.setGravity(false)
        display.isVisibleByDefault = false
        display.viewRange = (settings.sharedRadiusBlocks / 64.0).toFloat().coerceIn(0.1f, 1.0f)
        display.brightness = Display.Brightness(15, 15)
        display.interpolationDelay = 0
        display.interpolationDuration = CelebrationChoreography.FRAME_TICKS
        display.teleportDuration = CelebrationChoreography.FRAME_TICKS
        display.isGlowing = settings.display.glow
        if (settings.display.glow) display.glowColorOverride = settings.primaryColor.bukkit()
        display.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(0f), Quaternionf())
    }

    private fun moveAnchor(display: Display, origin: Location) {
        val target = anchor(origin)
        if (display.location.distanceSquared(target) > 1.0e-6) display.teleport(target)
    }

    private fun anchor(origin: Location): Location = origin.clone().apply { yaw = 0f; pitch = 0f }

    private fun itemLocation(origin: Location, pose: CelebrationPose): Location {
        val position = Quaternionf().rotateY(Math.toRadians(-initialYaw.toDouble()).toFloat()).transform(pose.position.vector())
        return anchor(origin).add(position.x.toDouble(), position.y.toDouble(), position.z.toDouble())
    }

    private fun applyPose(display: Display, pose: CelebrationPose, block: Boolean, tick: Int, index: Int) {
        val basis = Quaternionf().rotateY(Math.toRadians(-initialYaw.toDouble()).toFloat())
        // Billboard rotation also affects translation. Keep item/text trajectories in world positions.
        val position = if (block) basis.transform(pose.position.vector()) else Vector3f()
        val scale = pose.scale.vector()
        val rotation = if (block) {
            Quaternionf(basis).mul(Quaternionf().rotationTo(Vector3f(0f, 1f, 0f), pose.direction.vector().normalize()))
        } else if (display is TextDisplay) {
            Quaternionf()
        } else {
            val spin = Math.toRadians(index * 360.0 / settings.display.count + tick * settings.display.spinDegreesPerTick)
            Quaternionf().rotateZ(pose.roll.toFloat()).rotateY(spin.toFloat())
        }
        // BlockDisplay models occupy [0,1]^3; rotate about their center, not their corner.
        if (block) position.sub(rotation.transform(Vector3f(scale).mul(0.5f)))
        display.interpolationDelay = 0
        display.transformation = Transformation(position, rotation, scale, Quaternionf())
    }

    private companion object {
        val GLASS_COLORS = listOf(
            Material.WHITE_STAINED_GLASS to CelebrationColor(235, 238, 245),
            Material.YELLOW_STAINED_GLASS to CelebrationColor(255, 209, 80),
            Material.ORANGE_STAINED_GLASS to CelebrationColor(220, 135, 65),
            Material.LIME_STAINED_GLASS to CelebrationColor(95, 215, 125),
            Material.LIGHT_BLUE_STAINED_GLASS to CelebrationColor(85, 195, 250),
            Material.PURPLE_STAINED_GLASS to CelebrationColor(165, 110, 240),
            Material.PINK_STAINED_GLASS to CelebrationColor(235, 125, 180),
            Material.RED_STAINED_GLASS to CelebrationColor(210, 65, 65),
        )

        fun glassFor(color: CelebrationColor): Material = GLASS_COLORS.minBy { (_, candidate) ->
            val red = color.red - candidate.red
            val green = color.green - candidate.green
            val blue = color.blue - candidate.blue
            red * red + green * green + blue * blue
        }.first
    }
}

private fun CelebrationPoint.vector(): Vector3f = Vector3f(x.toFloat(), y.toFloat(), z.toFloat())
private fun CelebrationColor.bukkit(): Color = Color.fromRGB(red, green, blue)
