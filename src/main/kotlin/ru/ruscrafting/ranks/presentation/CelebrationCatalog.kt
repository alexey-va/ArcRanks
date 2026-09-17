package ru.ruscrafting.ranks.presentation

import ru.arc.config.Config
import ru.ruscrafting.ranks.config.CelebrationColor
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class CelebrationRecipe {
    BURST,
    HELIX,
    CROWN,
    STARFALL,
    ORBIT,
    ASCENSION,
    RIBBON,
    FIREWORK_FINALE,
}

enum class CelebrationDisplayType { NONE, TEXT, ITEM }

/** Topologies are intentionally different, rather than the same orbit with a new speed. */
enum class CelebrationDisplayPattern {
    HERO,
    RING,
    HELIX,
    CROWN,
    STACK,
    SHARDS,
    CONSTELLATION,
    BADGE,
}

data class CelebrationToastSettings(
    val key: String = "",
    val material: String = "NETHER_STAR",
    val customModelData: Int = 0,
) {
    val enabled: Boolean get() = key.isNotBlank()

    init {
        require(material.matches(Regex("[A-Z0-9_]{1,80}"))) { "Unsafe celebration toast material: $material" }
        require(customModelData in 0..10_000_000) { "Celebration toast custom model data is outside safe bounds" }
    }
}

data class CelebrationDisplaySettings(
    val type: CelebrationDisplayType = CelebrationDisplayType.NONE,
    val pattern: CelebrationDisplayPattern = CelebrationDisplayPattern.HERO,
    val count: Int = 1,
    val followPlayer: Boolean = true,
    val spinDegreesPerTick: Float = 0.0f,
    val glow: Boolean = true,
    val textKey: String = "",
    val material: String = "NETHER_STAR",
    val customModelData: Int = 0,
    val yOffset: Double = 2.4,
    val scale: Float = 0.8f,
    val ttlTicks: Int = 40,
) {
    init {
        require(count in 1..8) { "Celebration display count must be between 1 and 8" }
        require(type != CelebrationDisplayType.TEXT || count == 1) {
            "Text celebration displays support exactly one display entity"
        }
        require(spinDegreesPerTick.isFinite() && spinDegreesPerTick in -45.0f..45.0f) {
            "Celebration display spin must be between -45 and 45 degrees per tick"
        }
        require(material.matches(Regex("[A-Z0-9_]{1,80}"))) { "Unsafe celebration display material: $material" }
        require(customModelData in 0..10_000_000) { "Celebration display custom model data is outside safe bounds" }
        require(yOffset.isFinite() && yOffset in 0.0..8.0) { "Celebration display y-offset is outside safe bounds" }
        require(scale.isFinite() && scale in 0.1f..4.0f) { "Celebration display scale is outside safe bounds" }
        require(ttlTicks in 1..200) { "Celebration display ttl must be between 1 and 200 ticks" }
        require(type != CelebrationDisplayType.TEXT || textKey.isNotBlank()) {
            "Text celebration displays require a locale key"
        }
    }
}

data class CelebrationSceneSettings(
    val recipe: CelebrationRecipe,
    val durationTicks: Int = 48,
    val particleCount: Int = 16,
    val radius: Double = 1.5,
    val height: Double = 2.4,
    val primaryColor: CelebrationColor = CelebrationColor(217, 134, 79),
    val secondaryColor: CelebrationColor = CelebrationColor(255, 225, 154),
    val particleSize: Float = 1.0f,
    val sharedRadiusBlocks: Double = 24.0,
    val maximumViewers: Int = 16,
    val titleKey: String = "",
    val subtitleKey: String = "",
    val actionBarKey: String = "",
    val toast: CelebrationToastSettings = CelebrationToastSettings(),
    val soundType: String = "ENTITY_PLAYER_LEVELUP",
    val soundCategory: String = "PLAYERS",
    val soundVolume: Float = 1.0f,
    val soundPitch: Float = 1.0f,
    val display: CelebrationDisplaySettings = CelebrationDisplaySettings(),
    val fireworkCount: Int = 0,
    val fireworkType: String = "BALL",
    val fireworkTrail: Boolean = true,
    val fireworkFlicker: Boolean = false,
    val networkBroadcast: Boolean = false,
) {
    init {
        require(durationTicks in 8..200) { "Celebration duration must be between 8 and 200 ticks" }
        require(particleCount in 1..64) { "Celebration particle count must be between 1 and 64 per frame" }
        require(radius.isFinite() && radius in 0.2..4.0) { "Celebration radius must be between 0.2 and 4 blocks" }
        require(height.isFinite() && height in 0.2..6.0) { "Celebration height must be between 0.2 and 6 blocks" }
        require(particleSize.isFinite() && particleSize in 0.1f..4.0f) { "Celebration particle size is outside safe bounds" }
        require(sharedRadiusBlocks.isFinite() && sharedRadiusBlocks in 4.0..64.0) {
            "Celebration shared radius must be between 4 and 64 blocks"
        }
        require(maximumViewers in 1..64) { "Celebration maximum viewers must be between 1 and 64" }
        require(soundType.matches(Regex("[A-Z0-9_]{1,100}"))) { "Unsafe celebration sound: $soundType" }
        require(soundCategory.matches(Regex("[A-Z_]{1,40}"))) { "Unsafe celebration sound category: $soundCategory" }
        require(soundVolume.isFinite() && soundVolume in 0.0f..10.0f) { "Celebration sound volume is outside safe bounds" }
        require(soundPitch.isFinite() && soundPitch in 0.5f..2.0f) { "Celebration sound pitch is outside safe bounds" }
        require(fireworkCount in 0..4) { "Celebration firework count must be between 0 and 4" }
        require(fireworkType in setOf("BALL", "BALL_LARGE", "BURST", "CREEPER", "STAR")) {
            "Unsupported celebration firework type: $fireworkType"
        }
    }
}

data class CelebrationRoutes(
    val questDefault: String,
    val questRare: String,
    val questAdvanced: String,
    val questById: Map<String, String>,
    val rankDefault: String,
    val rankById: Map<String, String>,
)

data class CelebrationQuestContext(
    val questId: String,
    val rare: Boolean,
    val advanced: Boolean,
)

data class ResolvedCelebrationScene(val id: String, val settings: CelebrationSceneSettings) {
    val recipe: CelebrationRecipe get() = settings.recipe
}

data class CelebrationCatalog(
    val enabled: Boolean,
    val scenes: Map<String, CelebrationSceneSettings>,
    val routes: CelebrationRoutes,
) {
    init {
        require(scenes.size in 1..32) { "Celebration catalog must contain between 1 and 32 scenes" }
        require(scenes.keys.all { it.matches(Regex("[a-z0-9_-]{1,40}")) }) { "Unsafe celebration scene id" }
        val referenced = buildSet {
            add(routes.questDefault)
            add(routes.questRare)
            add(routes.questAdvanced)
            add(routes.rankDefault)
            addAll(routes.questById.values)
            addAll(routes.rankById.values)
        }
        require(referenced.all(scenes::containsKey)) { "Celebration routes reference unknown scenes: ${referenced - scenes.keys}" }
    }

    fun sceneIds(): List<String> = scenes.keys.sorted()

    fun scene(id: String): ResolvedCelebrationScene =
        ResolvedCelebrationScene(id, requireNotNull(scenes[id]) { "Unknown celebration scene: $id" })

    fun forRank(rankId: String): ResolvedCelebrationScene = scene(routes.rankById[rankId] ?: routes.rankDefault)

    fun forQuest(context: CelebrationQuestContext): ResolvedCelebrationScene = scene(
        routes.questById[context.questId]
            ?: routes.questAdvanced.takeIf { context.advanced }
            ?: routes.questRare.takeIf { context.rare }
            ?: routes.questDefault,
    )

    companion object {
        fun load(config: Config): CelebrationCatalog {
            val root = "celebration"
            val scenes = config.keys("$root.scenes").associateWith { id ->
                val path = "$root.scenes.$id"
                val displayType = enumValue<CelebrationDisplayType>(config, "$path.display.type")
                CelebrationSceneSettings(
                    recipe = enumValue(config, "$path.recipe"),
                    durationTicks = config.int("$path.duration-ticks"),
                    particleCount = config.int("$path.particle-count"),
                    radius = config.double("$path.radius"),
                    height = config.double("$path.height"),
                    primaryColor = config.color("$path.colors.primary"),
                    secondaryColor = config.color("$path.colors.secondary"),
                    particleSize = config.double("$path.particle-size").toFloat(),
                    sharedRadiusBlocks = config.double("$path.shared.radius-blocks"),
                    maximumViewers = config.int("$path.shared.maximum-viewers"),
                    titleKey = config.string("$path.personal.title-key").trim(),
                    subtitleKey = config.string("$path.personal.subtitle-key").trim(),
                    actionBarKey = config.string("$path.personal.action-bar-key").trim(),
                    toast = CelebrationToastSettings(
                        key = config.string("$path.personal.toast.key").trim(),
                        material = config.string("$path.personal.toast.material").trim().uppercase(Locale.ROOT),
                        customModelData = config.int("$path.personal.toast.custom-model-data"),
                    ),
                    soundType = config.string("$path.personal.sound.type").trim().uppercase(Locale.ROOT),
                    soundCategory = config.string("$path.personal.sound.category").trim().uppercase(Locale.ROOT),
                    soundVolume = config.double("$path.personal.sound.volume").toFloat(),
                    soundPitch = config.double("$path.personal.sound.pitch").toFloat(),
                    display = CelebrationDisplaySettings(
                        type = displayType,
                        pattern = enumValue(config, "$path.display.pattern"),
                        count = config.int("$path.display.count"),
                        followPlayer = config.boolean("$path.display.follow-player"),
                        spinDegreesPerTick = config.double("$path.display.spin-degrees-per-tick").toFloat(),
                        glow = config.boolean("$path.display.glow"),
                        textKey = config.string("$path.display.text-key").trim(),
                        material = config.string("$path.display.material").trim().uppercase(Locale.ROOT),
                        customModelData = config.int("$path.display.custom-model-data"),
                        yOffset = config.double("$path.display.y-offset"),
                        scale = config.double("$path.display.scale").toFloat(),
                        ttlTicks = config.int("$path.display.ttl-ticks"),
                    ),
                    fireworkCount = config.int("$path.fireworks.count"),
                    fireworkType = config.string("$path.fireworks.type").trim().uppercase(Locale.ROOT),
                    fireworkTrail = config.boolean("$path.fireworks.trail"),
                    fireworkFlicker = config.boolean("$path.fireworks.flicker"),
                    networkBroadcast = config.boolean("$path.network-broadcast"),
                )
            }
            return CelebrationCatalog(
                enabled = config.boolean("$root.enabled"),
                scenes = scenes,
                routes = CelebrationRoutes(
                    questDefault = config.string("$root.routes.quest.default").trim(),
                    questRare = config.string("$root.routes.quest.rare").trim(),
                    questAdvanced = config.string("$root.routes.quest.advanced").trim(),
                    questById = config.keys("$root.routes.quest.by-id").associateWith {
                        config.string("$root.routes.quest.by-id.$it").trim()
                    },
                    rankDefault = config.string("$root.routes.rank.default").trim(),
                    rankById = config.keys("$root.routes.rank.by-id").associateWith {
                        config.string("$root.routes.rank.by-id.$it").trim()
                    },
                ),
            )
        }
    }
}

data class CelebrationPoint(val x: Double, val y: Double, val z: Double)

object CelebrationGeometry {
    fun frame(
        recipe: CelebrationRecipe,
        tick: Int,
        durationTicks: Int,
        count: Int,
        radius: Double,
        height: Double,
    ): List<CelebrationPoint> {
        val progress = tick.coerceIn(0, durationTicks).toDouble() / durationTicks.coerceAtLeast(1)
        return List(count) { index ->
            val unit = index.toDouble() / count.coerceAtLeast(1)
            val angle = unit * PI * 2.0
            when (recipe) {
                CelebrationRecipe.BURST -> {
                    val latitude = (unit - 0.5) * PI
                    val spread = radius * (0.35 + progress)
                    CelebrationPoint(cos(angle * 3.0) * cos(latitude) * spread, 1.0 + sin(latitude) * spread, sin(angle * 3.0) * cos(latitude) * spread)
                }
                CelebrationRecipe.HELIX -> CelebrationPoint(
                    cos(angle * 2.0 + tick * 0.24) * radius,
                    unit * height,
                    sin(angle * 2.0 + tick * 0.24) * radius,
                )
                CelebrationRecipe.CROWN -> {
                    val crownY = height * (0.55 + 0.3 * if (index % 2 == 0) 1.0 else 0.0)
                    CelebrationPoint(cos(angle) * radius, crownY + sin(tick * 0.18) * 0.12, sin(angle) * radius)
                }
                CelebrationRecipe.STARFALL -> {
                    val shifted = (unit + progress * 0.75) % 1.0
                    CelebrationPoint(cos(angle * 2.7) * radius, height * (1.0 - shifted), sin(angle * 1.7) * radius)
                }
                CelebrationRecipe.ORBIT -> {
                    val band = index % 3
                    val tilt = (band - 1) * 0.55
                    CelebrationPoint(cos(angle + tick * 0.14) * radius, 1.15 + sin(angle + tick * 0.14) * tilt, sin(angle + tick * 0.14) * radius)
                }
                CelebrationRecipe.ASCENSION -> {
                    val rising = (unit + progress) % 1.0
                    CelebrationPoint(cos(angle * 3.0 + tick * 0.18) * radius * (1.0 - rising * 0.45), rising * height, sin(angle * 3.0 + tick * 0.18) * radius * (1.0 - rising * 0.45))
                }
                CelebrationRecipe.RIBBON -> {
                    val x = (unit * 2.0 - 1.0) * radius
                    CelebrationPoint(x, height * 0.55 + sin(angle + tick * 0.2) * 0.45, sin(angle * 2.0 + tick * 0.14) * radius * 0.45)
                }
                CelebrationRecipe.FIREWORK_FINALE -> {
                    val shell = radius * (0.45 + 0.55 * sin(progress * PI))
                    CelebrationPoint(cos(angle * 2.0) * shell, height * 0.6 + sin(angle * 3.0) * shell, sin(angle * 2.0) * shell)
                }
            }
        }
    }

    fun displayFrame(
        pattern: CelebrationDisplayPattern,
        tick: Int,
        durationTicks: Int,
        count: Int,
        radius: Double,
        height: Double,
    ): List<CelebrationPoint> {
        val safeCount = count.coerceIn(1, 8)
        val progress = tick.coerceIn(0, durationTicks).toDouble() / durationTicks.coerceAtLeast(1)
        val pulse = sin(progress * PI)
        return List(safeCount) { index ->
            val unit = if (safeCount == 1) 0.5 else index.toDouble() / (safeCount - 1)
            val ringUnit = index.toDouble() / safeCount
            val angle = ringUnit * PI * 2.0
            when (pattern) {
                CelebrationDisplayPattern.HERO -> CelebrationPoint(
                    0.0,
                    height * 0.55 + pulse * 0.20,
                    0.0,
                )
                CelebrationDisplayPattern.RING -> CelebrationPoint(
                    cos(angle + tick * 0.10) * radius * 0.82,
                    height * 0.52 + sin(angle * 2.0 + tick * 0.13) * 0.12 + pulse * 0.10,
                    sin(angle + tick * 0.10) * radius * 0.82,
                )
                CelebrationDisplayPattern.HELIX -> CelebrationPoint(
                    cos(angle + tick * 0.14) * radius * 0.70,
                    height * (0.18 + unit * 0.70) + sin(angle + tick * 0.10) * 0.10,
                    sin(angle + tick * 0.14) * radius * 0.70,
                )
                CelebrationDisplayPattern.CROWN -> CelebrationPoint(
                    cos(angle) * radius * 0.86,
                    height * (0.68 + if (index % 2 == 0) 0.20 else 0.0) + sin(tick * 0.14) * 0.08,
                    sin(angle) * radius * 0.86,
                )
                CelebrationDisplayPattern.STACK -> CelebrationPoint(
                    sin(tick * 0.11 + index * 0.9) * radius * 0.16,
                    height * (0.18 + unit * 0.68),
                    cos(tick * 0.09 + index * 0.8) * radius * 0.16,
                )
                CelebrationDisplayPattern.SHARDS -> {
                    val spread = radius * (0.16 + progress * 0.94)
                    CelebrationPoint(
                        cos(angle * 1.7) * spread,
                        height * 0.48 + sin(angle * 2.0 + tick * 0.08) * 0.24 - progress * 0.12,
                        sin(angle * 1.7) * spread,
                    )
                }
                CelebrationDisplayPattern.CONSTELLATION -> {
                    val starRadius = radius * if (index % 2 == 0) 0.88 else 0.52
                    CelebrationPoint(
                        cos(angle + tick * 0.04) * starRadius,
                        height * 0.58 + sin(angle * 2.0 + tick * 0.12) * 0.18 + pulse * 0.08,
                        sin(angle + tick * 0.04) * starRadius,
                    )
                }
                CelebrationDisplayPattern.BADGE -> CelebrationPoint(
                    0.0,
                    height * 0.62 + pulse * 0.12,
                    0.0,
                )
            }
        }
    }
}

private inline fun <reified T : Enum<T>> enumValue(config: Config, path: String): T =
    enumValueOf(config.string(path).trim().uppercase(Locale.ROOT))

private fun Config.color(path: String): CelebrationColor = CelebrationColor(
    red = int("$path.red"),
    green = int("$path.green"),
    blue = int("$path.blue"),
)
