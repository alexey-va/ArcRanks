package ru.ruscrafting.ranks.config

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlSslMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

enum class PromotionMode {
    SHADOW,
    ACTIVE,
}

data class FeatureSettings(
    val contracts: Boolean,
    val perks: Boolean,
    val weeklyKits: Boolean,
)

data class GuiItemSpec(
    val material: String,
    val customModelData: Int,
) {
    init {
        require(material.matches(Regex("[A-Z0-9_]{1,80}"))) { "Unsafe GUI material: $material" }
        require(customModelData >= 0) { "GUI custom model data must not be negative" }
    }
}

data class GuiSettings(
    val background: GuiItemSpec,
    val back: GuiItemSpec,
    val rankCompleted: GuiItemSpec,
    val rankCurrent: GuiItemSpec,
    val rankNext: GuiItemSpec,
    val rankLocked: GuiItemSpec,
    val path: GuiItemSpec,
    val promotion: GuiItemSpec,
    val contracts: GuiItemSpec,
    val perks: GuiItemSpec,
    val analytics: GuiItemSpec,
    val promotionBlockedTicks: Long,
    val items: Map<String, GuiItemSpec>,
) {
    init {
        require(promotionBlockedTicks in 1L..1_200L) {
            "gui.promotion-blocked-ticks must be between 1 and 1200"
        }
        require(items.keys == REQUIRED_GUI_ITEM_KEYS) {
            val missing = (REQUIRED_GUI_ITEM_KEYS - items.keys).sorted()
            val unknown = (items.keys - REQUIRED_GUI_ITEM_KEYS).sorted()
            "gui.items must contain the exact supported key set; missing=$missing unknown=$unknown"
        }
    }

    fun item(key: String, fallback: GuiItemSpec): GuiItemSpec = items[key] ?: fallback
}

private val REQUIRED_GUI_ITEM_KEYS = setOf(
    "refresh",
    "loading",
    "running",
    "error",
    "passport-profile",
    "passport-guide",
    "passport-paths",
    "passport-weekly-kit",
    "passport-benefits",
    "passport-admin-advance",
    "passport-admin-analytics",
    "path-farming",
    "path-industry",
    "path-trade",
    "path-exploration",
    "path-building",
    "path-community",
    "contract-status",
    "contract-stamp",
    "contract-complete",
    "contract-reroll",
    "contract-disabled",
    "contract-claim-ready",
    "contract-claim-active",
    "contract-progress",
    "contract-admin-complete",
    "perk-empty-slot",
    "perk-available",
    "perk-selected",
    "perk-other",
    "perk-locked",
    "analytics-window",
    "analytics-overview",
    "analytics-contracts",
    "analytics-perks",
    "analytics-promotions",
    "analytics-recommendations",
    "analytics-health",
    "weekly-kit-contents",
    "weekly-kit-available",
    "weekly-kit-delivering",
    "weekly-kit-claimed",
    "weekly-kit-admin-reset",
    "weekly-kit-admin-disabled",
)

data class AnalyticsSettings(
    val enabled: Boolean,
    val flushTicks: Long,
    val maximumMetricKeys: Int,
    val maximumPlayers: Int,
    val summaryCacheSeconds: Long,
    val windows: List<Int>,
    val defaultWindow: Int,
) {
    init {
        require(flushTicks >= 20 && flushTicks % 20L == 0L) {
            "analytics.flush-ticks must be a positive whole-second tick interval"
        }
        require(maximumMetricKeys in 32..10_000) { "analytics.maximum-metric-keys must be between 32 and 10000" }
        require(maximumPlayers in 64..100_000) { "analytics.maximum-players must be between 64 and 100000" }
        require(summaryCacheSeconds in 5..600) { "analytics.summary-cache-seconds must be between 5 and 600" }
        require(windows.size == 3 && windows.distinct().size == 3 && windows.all { it in 1..365 }) {
            "analytics.windows must contain exactly three distinct values between 1 and 365"
        }
        require(defaultWindow in windows) { "analytics.default-window must be present in analytics.windows" }
    }
}

data class RuntimeSettings(
    val startupTimeoutSeconds: Long,
    val shutdownFlushTimeoutSeconds: Long,
    val healthReportTicks: Long,
) {
    init {
        require(startupTimeoutSeconds in 1L..120L) { "runtime.startup-timeout-seconds must be between 1 and 120" }
        require(shutdownFlushTimeoutSeconds in 1L..120L) {
            "runtime.shutdown-flush-timeout-seconds must be between 1 and 120"
        }
        require(healthReportTicks >= 20L && healthReportTicks % 20L == 0L) {
            "runtime.health-report-ticks must be a positive whole-second tick interval"
        }
    }
}

data class CounterSourceSettings(
    val enabled: Boolean,
    val amount: Long,
) {
    init {
        require(amount in 1L..100L) { "Progress source amount must be between 1 and 100" }
    }
}

data class MilestoneSourceSettings(
    val enabled: Boolean,
    val amount: Long,
) {
    init {
        require(amount in 1L..10_000_000L) { "Milestone progress amount must be between 1 and 10000000" }
    }
}

data class AuctionDealSourceSettings(
    val enabled: Boolean,
    val maximumProgressPerDeal: Long,
) {
    init {
        require(maximumProgressPerDeal in 1L..10_000_000L) {
            "Auction maximum progress per deal must be between 1 and 10000000"
        }
    }
}

data class CommunityChatSourceSettings(
    val enabled: Boolean,
    val amount: Long,
    val cooldownSeconds: Long,
    val duplicateWindowSeconds: Long,
    val minimumLettersOrDigits: Int,
) {
    init {
        require(amount in 1L..100L) { "Community chat progress amount must be between 1 and 100" }
        require(cooldownSeconds in 10L..3_600L) {
            "Community chat cooldown must be between 10 and 3600 seconds"
        }
        require(duplicateWindowSeconds in cooldownSeconds..86_400L) {
            "Community chat duplicate window must be at least the cooldown and at most 86400 seconds"
        }
        require(minimumLettersOrDigits in 1..64) {
            "Community chat minimum letters or digits must be between 1 and 64"
        }
    }
}

data class MaterialFilterSettings(
    val included: Set<String>,
    val excluded: Set<String>,
) {
    init {
        val pattern = Regex("[A-Z0-9_]{1,80}")
        require((included + excluded).all { it.matches(pattern) }) { "Progress material filter contains an unsafe name" }
        require(included.intersect(excluded).isEmpty()) { "Progress material include/exclude filters must not overlap" }
    }

    fun allows(material: String): Boolean =
        (included.isEmpty() || material in included) && material !in excluded
}

data class FilteredCounterSourceSettings(
    val source: CounterSourceSettings,
    val materials: MaterialFilterSettings,
) {
    val enabled: Boolean get() = source.enabled
    val amount: Long get() = source.amount
    fun allows(material: String): Boolean = materials.allows(material)
}

data class ProgressCollectionSettings(
    val enabled: Boolean,
    val eligibleGameModes: Set<String>,
    val includedWorlds: Set<String>,
    val excludedWorlds: Set<String>,
    val blockPlace: FilteredCounterSourceSettings,
    val matureCrop: CounterSourceSettings,
    val matureCropMaterials: Set<String>,
    val animalBreeding: CounterSourceSettings,
    val fishing: CounterSourceSettings,
    val crafting: FilteredCounterSourceSettings,
    val furnace: FilteredCounterSourceSettings,
    val enchanting: CounterSourceSettings,
    val smithing: CounterSourceSettings,
    val villagerTrade: MilestoneSourceSettings,
    val auctionDeal: AuctionDealSourceSettings,
    val travelEnabled: Boolean,
    val travelIncludeVertical: Boolean,
    val explorationAdvancement: MilestoneSourceSettings,
    val dungeonCompletion: MilestoneSourceSettings,
    val decorationPlace: CounterSourceSettings,
    val activeEnabled: Boolean,
    val communityEnabled: Boolean,
    val communityMinimumNearbyPlayers: Int,
    val sharedAdvancement: CounterSourceSettings,
    val communityChat: CommunityChatSourceSettings,
    val wealthEnabled: Boolean,
) {
    init {
        require(eligibleGameModes.isNotEmpty() && eligibleGameModes.all { it in SAFE_GAME_MODES }) {
            "progress.collection.eligible-game-modes may contain only SURVIVAL and ADVENTURE"
        }
        val worldPattern = Regex("[A-Za-z0-9_./-]{1,80}")
        require((includedWorlds + excludedWorlds).all { it.matches(worldPattern) }) {
            "progress collection world filters contain an unsafe world name"
        }
        require(includedWorlds.intersect(excludedWorlds).isEmpty()) {
            "progress collection included-worlds and excluded-worlds must not overlap"
        }
        require(matureCropMaterials.isNotEmpty() && matureCropMaterials.all { it.matches(Regex("[A-Z0-9_]{1,80}")) }) {
            "progress.collection.mature-crop.materials must contain safe material names"
        }
        require(communityMinimumNearbyPlayers in 1..32) {
            "progress.collection.community.minimum-nearby-players must be between 1 and 32"
        }
    }

    fun allows(gameMode: String, worldName: String): Boolean = enabled &&
        gameMode.uppercase(Locale.ROOT) in eligibleGameModes &&
        (includedWorlds.isEmpty() || worldName in includedWorlds) &&
        worldName !in excludedWorlds

    private companion object {
        val SAFE_GAME_MODES = setOf("SURVIVAL", "ADVENTURE")
    }
}

data class CelebrationColor(val red: Int, val green: Int, val blue: Int) {
    init {
        require(red in 0..255 && green in 0..255 && blue in 0..255) {
            "Celebration RGB channels must be between 0 and 255"
        }
    }
}

data class CelebrationTitleSettings(
    val enabled: Boolean,
    val fadeInMillis: Long,
    val stayMillis: Long,
    val fadeOutMillis: Long,
) {
    init {
        require(fadeInMillis in 0L..10_000L && stayMillis in 0L..30_000L && fadeOutMillis in 0L..10_000L) {
            "Celebration title timings are outside their safe bounds"
        }
        require(fadeInMillis + stayMillis + fadeOutMillis > 0) { "Celebration title duration must be positive" }
    }
}

data class CelebrationSoundSettings(
    val enabled: Boolean,
    val type: String,
    val category: String,
    val volume: Float,
    val pitch: Float,
) {
    init {
        require(type.matches(Regex("[A-Z0-9_]{1,100}"))) { "Unsafe celebration sound: $type" }
        require(category in SOUND_CATEGORIES) { "Unsupported celebration sound category: $category" }
        require(volume.isFinite() && volume in 0.0f..10.0f) { "Celebration sound volume must be between 0 and 10" }
        require(pitch.isFinite() && pitch in 0.5f..2.0f) { "Celebration sound pitch must be between 0.5 and 2" }
    }

    private companion object {
        val SOUND_CATEGORIES = setOf(
            "MASTER", "MUSIC", "RECORDS", "WEATHER", "BLOCKS", "HOSTILE", "NEUTRAL",
            "PLAYERS", "AMBIENT", "VOICE",
        )
    }
}

data class CelebrationParticleSettings(
    val enabled: Boolean,
    val color: CelebrationColor,
    val size: Float,
    val radius: Double,
    val originYOffset: Double,
    val verticalStep: Double,
) {
    init {
        require(size.isFinite() && size in 0.1f..4.0f) { "Celebration particle size must be between 0.1 and 4" }
        require(radius.isFinite() && radius in 0.1..8.0) { "Celebration particle radius must be between 0.1 and 8" }
        require(originYOffset.isFinite() && originYOffset in -2.0..8.0) {
            "Celebration particle origin-y-offset must be between -2 and 8"
        }
        require(verticalStep.isFinite() && verticalStep in -1.0..1.0) {
            "Celebration particle vertical-step must be between -1 and 1"
        }
    }
}

data class CelebrationFireworkSettings(
    val enabled: Boolean,
    val delayTicks: Long,
    val originYOffset: Double,
    val horizontalOffset: Double,
    val type: String,
    val colors: List<CelebrationColor>,
    val fadeColors: List<CelebrationColor>,
    val trail: Boolean,
    val flicker: Boolean,
) {
    init {
        require(delayTicks in 1L..200L) { "Celebration firework delay-ticks must be between 1 and 200" }
        require(originYOffset.isFinite() && originYOffset in -2.0..8.0) {
            "Celebration firework origin-y-offset must be between -2 and 8"
        }
        require(horizontalOffset.isFinite() && horizontalOffset in 0.0..8.0) {
            "Celebration firework horizontal-offset must be between 0 and 8"
        }
        require(type in FIREWORK_TYPES) { "Unsupported celebration firework type: $type" }
        require(colors.size in 1..8 && fadeColors.size in 0..8) { "Celebration firework colors must contain between 1 and 8 values" }
    }

    private companion object {
        val FIREWORK_TYPES = setOf("BALL", "BALL_LARGE", "BURST", "CREEPER", "STAR")
    }
}

data class CelebrationProfileSettings(
    val particleCount: Int,
    val fireworkCount: Int,
    val networkBroadcast: Boolean,
) {
    init {
        require(particleCount in 0..256) { "Celebration particle count must be between 0 and 256" }
        require(fireworkCount in 0..4) { "Celebration firework count must be between 0 and 4" }
    }
}

data class CelebrationSettings(
    val enabled: Boolean,
    val title: CelebrationTitleSettings,
    val sound: CelebrationSoundSettings,
    val particles: CelebrationParticleSettings,
    val fireworks: CelebrationFireworkSettings,
    val profiles: Map<Int, CelebrationProfileSettings>,
) {
    init {
        require(profiles.keys == (1..9).toSet()) { "celebration.profiles must define every rank order from 1 to 9" }
    }

    fun profile(order: Int): CelebrationProfileSettings =
        requireNotNull(profiles[order]) { "Rank order is outside the celebration profile catalog: $order" }
}

data class ArcRanksSettings(
    val serverId: String,
    val promotionMode: PromotionMode,
    val features: FeatureSettings,
    val defaultLocale: String,
    val useClientLocale: Boolean,
    val passwordEnvironment: String,
    val sql: SqlConnectionConfig,
    val runtime: RuntimeSettings,
    val maximumBufferEntries: Int,
    val flushTicks: Long,
    val sampleTicks: Long,
    val maximumIdleSeconds: Long,
    val maximumMovementStepBlocks: Double,
    val communityRadiusBlocks: Double,
    val collection: ProgressCollectionSettings,
    val analytics: AnalyticsSettings,
    val gui: GuiSettings,
    val celebration: CelebrationSettings,
) {
    init {
        require(serverId.matches(Regex("[a-z0-9_-]{1,40}"))) { "Unsafe server-id: $serverId" }
        require(defaultLocale in setOf("ru", "en")) { "locale.default must be ru or en" }
        require(maximumBufferEntries in 64..100_000) { "progress.maximum-buffer-entries must be between 64 and 100000" }
        require(flushTicks >= 20 && flushTicks % 20L == 0L) {
            "progress.flush-ticks must be a positive whole-second tick interval"
        }
        require(sampleTicks >= 20) { "progress.sample-ticks must be at least 20" }
        require(sampleTicks % 1_200L == 0L) { "progress.sample-ticks must be a whole number of minutes" }
        require(maximumIdleSeconds in 60L..3_600L) { "progress.maximum-idle-seconds must be between 60 and 3600" }
        require(maximumMovementStepBlocks in 1.0..128.0) { "progress.maximum-movement-step-blocks must be between 1 and 128" }
        require(communityRadiusBlocks in 1.0..256.0) { "progress.community-radius-blocks must be between 1 and 256" }
    }

    companion object {
        fun load(
            dataRoot: Path,
            environment: (String) -> String? = System::getenv,
        ): ArcRanksSettings = loadFromConfig(dataRoot, ConfigManager.of(dataRoot, "config.yml"), environment)

        /** Reads an isolated Config so a rejected candidate cannot mutate the active generation. */
        fun loadFresh(
            dataRoot: Path,
            environment: (String) -> String? = System::getenv,
        ): ArcRanksSettings = loadFromConfig(dataRoot, Config(dataRoot, "config.yml"), environment)

        fun load(
            config: Config,
            dataRoot: Path,
            environment: (String) -> String? = System::getenv,
        ): ArcRanksSettings = loadFromConfig(dataRoot, config, environment)

        private fun loadFromConfig(
            dataRoot: Path,
            config: Config,
            environment: (String) -> String?,
        ): ArcRanksSettings {
            val passwordEnvironment = config.string("mysql.password-env").trim()
            require(passwordEnvironment.matches(Regex("[A-Z][A-Z0-9_]{2,80}"))) {
                "mysql.password-env must name a safe environment variable"
            }
            val password = environment(passwordEnvironment)?.takeIf(String::isNotBlank)
                ?: loadPluginSecret(dataRoot.resolve(".env"))
                ?: error("Environment variable $passwordEnvironment or plugin-local .env is required")
            return ArcRanksSettings(
                serverId = config.string("server-id").trim().lowercase(Locale.ROOT),
                promotionMode = PromotionMode.valueOf(config.string("promotion-mode").trim().uppercase(Locale.ROOT)),
                features = FeatureSettings(
                    contracts = config.boolean("features.contracts"),
                    perks = config.boolean("features.perks"),
                    weeklyKits = config.boolean("features.weekly-kits"),
                ),
                defaultLocale = config.string("locale.default").trim().lowercase(Locale.ROOT),
                useClientLocale = config.boolean("locale.use-client-locale"),
                passwordEnvironment = passwordEnvironment,
                sql = SqlConnectionConfig(
                    host = config.string("mysql.host").trim(),
                    port = config.int("mysql.port"),
                    database = config.string("mysql.database").trim(),
                    username = config.string("mysql.username").trim(),
                    password = password,
                    sslMode = SqlSslMode.valueOf(config.string("mysql.ssl-mode").trim().uppercase(Locale.ROOT)),
                    minimumIdle = config.int("mysql.minimum-idle"),
                    maximumPoolSize = config.int("mysql.maximum-pool-size"),
                    connectionTimeoutMs = config.long("mysql.connection-timeout-ms"),
                    socketTimeoutMs = config.long("mysql.socket-timeout-ms"),
                    validationTimeoutMs = config.long("mysql.validation-timeout-ms"),
                    maxLifetimeMs = config.long("mysql.max-lifetime-ms"),
                    failFast = config.boolean("mysql.fail-fast"),
                ),
                runtime = RuntimeSettings(
                    startupTimeoutSeconds = config.long("runtime.startup-timeout-seconds"),
                    shutdownFlushTimeoutSeconds = config.long("runtime.shutdown-flush-timeout-seconds"),
                    healthReportTicks = config.long("runtime.health-report-ticks"),
                ),
                maximumBufferEntries = config.int("progress.maximum-buffer-entries"),
                flushTicks = config.long("progress.flush-ticks"),
                sampleTicks = config.long("progress.sample-ticks"),
                maximumIdleSeconds = config.long("progress.maximum-idle-seconds"),
                maximumMovementStepBlocks = config.double("progress.maximum-movement-step-blocks"),
                communityRadiusBlocks = config.double("progress.community-radius-blocks"),
                collection = config.progressCollection(),
                analytics = AnalyticsSettings(
                    enabled = config.boolean("analytics.enabled"),
                    flushTicks = config.long("analytics.flush-ticks"),
                    maximumMetricKeys = config.int("analytics.maximum-metric-keys"),
                    maximumPlayers = config.int("analytics.maximum-players"),
                    summaryCacheSeconds = config.long("analytics.summary-cache-seconds"),
                    windows = config.list<Any>("analytics.windows").map { it.toString().toInt() },
                    defaultWindow = config.int("analytics.default-window"),
                ),
                gui = GuiSettings(
                    background = config.item("gui.background"),
                    back = config.item("gui.back"),
                    rankCompleted = config.item("gui.rank-completed"),
                    rankCurrent = config.item("gui.rank-current"),
                    rankNext = config.item("gui.rank-next"),
                    rankLocked = config.item("gui.rank-locked"),
                    path = config.item("gui.path"),
                    promotion = config.item("gui.promotion"),
                    contracts = config.item("gui.contracts"),
                    perks = config.item("gui.perks"),
                    analytics = config.item("gui.analytics"),
                    promotionBlockedTicks = config.long("gui.promotion-blocked-ticks"),
                    items = config.keys("gui.items").associateWith { key -> config.item("gui.items.$key") },
                ),
                celebration = config.celebration(),
            )
        }

        private fun loadPluginSecret(path: Path): String? {
            if (!Files.isRegularFile(path)) return null
            val value = Files.readString(path).trimEnd('\r', '\n')
            require(value.isNotBlank() && '\n' !in value && '\r' !in value) {
                "Plugin-local .env must contain exactly one non-empty line"
            }
            return value
        }
    }
}

private fun Config.progressCollection(): ProgressCollectionSettings = ProgressCollectionSettings(
    enabled = boolean("progress.collection.enabled"),
    eligibleGameModes = stringList("progress.collection.eligible-game-modes")
        .map { it.trim().uppercase(Locale.ROOT) }.toSet(),
    includedWorlds = stringList("progress.collection.included-worlds").map(String::trim).filter(String::isNotEmpty).toSet(),
    excludedWorlds = stringList("progress.collection.excluded-worlds").map(String::trim).filter(String::isNotEmpty).toSet(),
    blockPlace = filteredCounterSource("progress.collection.block-place"),
    matureCrop = counterSource("progress.collection.mature-crop"),
    matureCropMaterials = stringList("progress.collection.mature-crop.materials")
        .map { it.trim().uppercase(Locale.ROOT) }.toSet(),
    animalBreeding = counterSource("progress.collection.animal-breeding"),
    fishing = counterSource("progress.collection.fishing"),
    crafting = filteredCounterSource("progress.collection.crafting"),
    furnace = filteredCounterSource("progress.collection.furnace"),
    enchanting = counterSource("progress.collection.enchanting"),
    smithing = counterSource("progress.collection.smithing"),
    villagerTrade = milestoneSource("progress.collection.villager-trade"),
    auctionDeal = AuctionDealSourceSettings(
        enabled = boolean("progress.collection.auction-deal.enabled"),
        maximumProgressPerDeal = long("progress.collection.auction-deal.maximum-progress-per-deal"),
    ),
    travelEnabled = boolean("progress.collection.travel.enabled"),
    travelIncludeVertical = boolean("progress.collection.travel.include-vertical"),
    explorationAdvancement = milestoneSource("progress.collection.exploration-advancement"),
    dungeonCompletion = milestoneSource("progress.collection.dungeon-completion"),
    decorationPlace = counterSource("progress.collection.decoration-place"),
    activeEnabled = boolean("progress.collection.active.enabled"),
    communityEnabled = boolean("progress.collection.community.enabled"),
    communityMinimumNearbyPlayers = int("progress.collection.community.minimum-nearby-players"),
    sharedAdvancement = counterSource("progress.collection.community.shared-advancement"),
    communityChat = CommunityChatSourceSettings(
        enabled = boolean("progress.collection.community.chat.enabled"),
        amount = long("progress.collection.community.chat.amount"),
        cooldownSeconds = long("progress.collection.community.chat.cooldown-seconds"),
        duplicateWindowSeconds = long("progress.collection.community.chat.duplicate-window-seconds"),
        minimumLettersOrDigits = int("progress.collection.community.chat.minimum-letters-or-digits"),
    ),
    wealthEnabled = boolean("progress.collection.wealth.enabled"),
)

private fun Config.counterSource(path: String): CounterSourceSettings = CounterSourceSettings(
    enabled = boolean("$path.enabled"),
    amount = long("$path.amount"),
)

private fun Config.milestoneSource(path: String): MilestoneSourceSettings = MilestoneSourceSettings(
    enabled = boolean("$path.enabled"),
    amount = long("$path.amount"),
)

private fun Config.filteredCounterSource(path: String): FilteredCounterSourceSettings = FilteredCounterSourceSettings(
    source = counterSource(path),
    materials = MaterialFilterSettings(
        included = stringList("$path.included-materials").map { it.trim().uppercase(Locale.ROOT) }
            .filter(String::isNotEmpty).toSet(),
        excluded = stringList("$path.excluded-materials").map { it.trim().uppercase(Locale.ROOT) }
            .filter(String::isNotEmpty).toSet(),
    ),
)

private fun Config.celebration(): CelebrationSettings = CelebrationSettings(
    enabled = boolean("celebration.enabled"),
    title = CelebrationTitleSettings(
        enabled = boolean("celebration.title.enabled"),
        fadeInMillis = long("celebration.title.fade-in-ms"),
        stayMillis = long("celebration.title.stay-ms"),
        fadeOutMillis = long("celebration.title.fade-out-ms"),
    ),
    sound = CelebrationSoundSettings(
        enabled = boolean("celebration.sound.enabled"),
        type = string("celebration.sound.type").trim().uppercase(Locale.ROOT),
        category = string("celebration.sound.category").trim().uppercase(Locale.ROOT),
        volume = double("celebration.sound.volume").toFloat(),
        pitch = double("celebration.sound.pitch").toFloat(),
    ),
    particles = CelebrationParticleSettings(
        enabled = boolean("celebration.particles.enabled"),
        color = color("celebration.particles.color"),
        size = double("celebration.particles.size").toFloat(),
        radius = double("celebration.particles.radius"),
        originYOffset = double("celebration.particles.origin-y-offset"),
        verticalStep = double("celebration.particles.vertical-step"),
    ),
    fireworks = CelebrationFireworkSettings(
        enabled = boolean("celebration.fireworks.enabled"),
        delayTicks = long("celebration.fireworks.delay-ticks"),
        originYOffset = double("celebration.fireworks.origin-y-offset"),
        horizontalOffset = double("celebration.fireworks.horizontal-offset"),
        type = string("celebration.fireworks.type").trim().uppercase(Locale.ROOT),
        colors = keys("celebration.fireworks.colors").sortedBy(String::toInt).map { color("celebration.fireworks.colors.$it") },
        fadeColors = keys("celebration.fireworks.fade-colors").sortedBy(String::toInt).map { color("celebration.fireworks.fade-colors.$it") },
        trail = boolean("celebration.fireworks.trail"),
        flicker = boolean("celebration.fireworks.flicker"),
    ),
    profiles = (1..9).associateWith { order ->
        CelebrationProfileSettings(
            particleCount = int("celebration.profiles.$order.particle-count"),
            fireworkCount = int("celebration.profiles.$order.firework-count"),
            networkBroadcast = boolean("celebration.profiles.$order.network-broadcast"),
        )
    },
)

private fun Config.color(path: String): CelebrationColor = CelebrationColor(
    red = int("$path.red"),
    green = int("$path.green"),
    blue = int("$path.blue"),
)

private fun Config.item(path: String): GuiItemSpec = GuiItemSpec(
    material = string("$path.material").trim().uppercase(Locale.ROOT),
    customModelData = int("$path.custom-model-data"),
)
