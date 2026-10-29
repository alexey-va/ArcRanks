package ru.ruscrafting.ranks.presentation

import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.ranks.config.CelebrationColor
import java.util.UUID

/** A single bounded display cast. PromotionCelebration owns its task, visibility and cleanup. */
internal class CelebrationDisplayRenderer(
    private val displays: PaperPacketDisplays,
    private val settings: CelebrationSceneSettings,
    private val initialYaw: Float,
) : AutoCloseable {
    private val items = mutableMapOf<UUID, MutableList<PacketDisplay>>()
    private val accents = mutableListOf<PacketBlockDisplay>()
    private var carrier: Player? = null
    private var text: Component = Component.empty()
    private var closed = false
    val handles: List<PacketDisplay> get() = items.values.flatten() + accents

    fun spawn(origin: Location, text: Component, owner: Player, viewers: List<Player>) {
        if (settings.display.type == CelebrationDisplayType.NONE) return
        carrier = owner.takeIf { settings.display.followPlayer }
        this.text = text
        try {
            repeat(CelebrationChoreography.ACCENT_COUNT) { index ->
                val color = if (index < 8) settings.primaryColor else settings.secondaryColor
                val display = displays.spawnBlock(anchor(origin), glassFor(color).createBlockData())
                accents += display
                configure(display)
                display.glowColorOverride = color.bukkit()
            }
            render(origin, 0, viewers)
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    fun render(origin: Location, tick: Int, viewers: List<Player>) {
        if (closed || settings.display.type == CelebrationDisplayType.NONE) return
        if (tick >= settings.display.ttlTicks) {
            close()
            return
        }
        val audience = viewers.distinctBy(Player::getUniqueId).take(settings.maximumViewers)
        val allowed = audience.mapTo(hashSetOf()) { it.uniqueId }
        (items.keys - allowed).forEach { id ->
            items.remove(id)?.forEach(PacketDisplay::remove)
            accents.forEach { it.hideFrom(id) }
        }
        val itemPoses = CelebrationChoreography.items(settings, tick)
        val accentPoses = CelebrationChoreography.accents(settings, tick)
        val passengerHeight = carrier?.height?.toFloat() ?: 0f
        audience.forEach { viewer ->
            val viewerItems = items[viewer.uniqueId] ?: spawnItems(origin, viewer)
            val eye = viewer.eyeLocation
            // Keep the original camera-facing items without rotating their world-axis trajectory.
            // CENTER billboard would also rotate a mounted display's local translation.
            val facing = Quaternionf().rotationYXZ(
                Math.toRadians(180.0 - eye.yaw).toFloat(), Math.toRadians(-eye.pitch.toDouble()).toFloat(), 0f,
            )
            viewerItems.forEachIndexed { index, display ->
                applyPose(display, itemPoses[index], false, tick, index, passengerHeight, facing)
            }
        }
        accents.forEachIndexed { index, display ->
            applyPose(display, accentPoses[index], true, tick, index, passengerHeight)
        }
    }

    private fun spawnItems(origin: Location, viewer: Player): List<PacketDisplay> {
        val viewerItems = mutableListOf<PacketDisplay>()
        items[viewer.uniqueId] = viewerItems
        repeat(settings.display.count) {
            val display = when (settings.display.type) {
                CelebrationDisplayType.TEXT -> displays.spawnText(anchor(origin), text)
                CelebrationDisplayType.ITEM -> {
                    val item = ItemStack.of(checkNotNull(Material.matchMaterial(settings.display.material)))
                    if (settings.display.customModelData > 0) item.editMeta { it.setCustomModelData(settings.display.customModelData) }
                    displays.spawnItem(anchor(origin), item)
                }
                CelebrationDisplayType.NONE -> return@repeat
            }
            viewerItems += display
            configure(display)
            if (display is PacketTextDisplay) {
                display.isShadowed = true
                display.backgroundColor = Color.fromARGB(70, 12, 16, 28)
            } else if (display is PacketItemDisplay) {
                display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
            }
            display.showTo(viewer)
        }
        accents.forEach { it.showTo(viewer) }
        return viewerItems
    }

    private fun configure(display: PacketDisplay) {
        display.isVisibleByDefault = false
        display.billboard = Display.Billboard.FIXED
        display.viewRange = (settings.sharedRadiusBlocks / 64.0).toFloat().coerceIn(0.1f, 1.0f)
        display.brightness = Display.Brightness(15, 15)
        display.interpolationDelay = 0
        display.interpolationDuration = CelebrationChoreography.FRAME_TICKS
        display.teleportDuration = 0
        display.isGlowing = settings.display.glow
        if (settings.display.glow) display.glowColorOverride = settings.primaryColor.bukkit()
        display.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(0f), Quaternionf())
        carrier?.let(display::attachTo)
    }

    private fun anchor(origin: Location): Location = origin.clone().apply { yaw = 0f; pitch = 0f }

    private fun applyPose(
        display: PacketDisplay, pose: CelebrationPose, block: Boolean, tick: Int, index: Int,
        passengerHeight: Float, facing: Quaternionf = Quaternionf(),
    ) {
        val basis = Quaternionf().rotateY(Math.toRadians(-initialYaw.toDouble()).toFloat())
        // Native display passengers sit above the player's head; choreography remains feet-relative.
        val position = basis.transform(pose.position.vector()).sub(0f, passengerHeight, 0f)
        val scale = pose.scale.vector()
        val rotation = if (block) {
            Quaternionf(basis).mul(Quaternionf().rotationTo(Vector3f(0f, 1f, 0f), pose.direction.vector().normalize()))
        } else if (display is PacketTextDisplay) {
            Quaternionf(facing)
        } else {
            val spin = Math.toRadians(index * 360.0 / settings.display.count + tick * settings.display.spinDegreesPerTick)
            Quaternionf(facing).rotateZ(pose.roll.toFloat()).rotateY(spin.toFloat())
        }
        // BlockDisplay models occupy [0,1]^3; rotate about their center, not their corner.
        if (block) position.sub(rotation.transform(Vector3f(scale).mul(0.5f)))
        display.interpolationDelay = 0
        display.transformation = Transformation(position, rotation, scale, Quaternionf())
    }

    override fun close() {
        if (closed) return
        closed = true
        handles.forEach(PacketDisplay::remove)
        items.clear()
        accents.clear()
        carrier = null
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
