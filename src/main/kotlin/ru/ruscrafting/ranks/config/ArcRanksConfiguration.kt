package ru.ruscrafting.ranks.config

import org.bukkit.FireworkEffect
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.SoundCategory
import ru.arc.config.Config
import ru.ruscrafting.ranks.contract.ContractCatalog
import ru.ruscrafting.ranks.contract.ContractCatalogLoader
import ru.ruscrafting.ranks.domain.RankEvaluator
import ru.ruscrafting.ranks.kit.WeeklyKitCatalog
import ru.ruscrafting.ranks.kit.WeeklyKitCatalogLoader
import ru.ruscrafting.ranks.perk.PerkCatalog
import ru.ruscrafting.ranks.perk.PerkCatalogLoader
import ru.ruscrafting.ranks.text.RankLocale
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

data class ArcRanksConfigFingerprints(val files: Map<String, String>) {
    init {
        require(files.keys == CONFIG_RESOURCES.toSet()) { "ArcRanks fingerprints must cover every public YAML resource" }
        require(files.values.all { it.matches(Regex("[a-f0-9]{64}")) }) { "Invalid ArcRanks configuration fingerprint" }
    }
}

data class ArcRanksConfigSnapshot(
    val generation: Long,
    val settings: ArcRanksSettings,
    val ranks: LoadedRankCatalog,
    val perks: PerkCatalog,
    val contracts: ContractCatalog,
    val weeklyKits: WeeklyKitCatalog,
    val locale: RankLocale,
    val fingerprints: ArcRanksConfigFingerprints,
) {
    val evaluator: RankEvaluator = RankEvaluator(ranks.catalog)

    init {
        require(generation > 0) { "ArcRanks configuration generation must be positive" }
    }
}

class ArcRanksConfigStore(initial: ArcRanksConfigSnapshot) {
    private val current = AtomicReference(initial)

    fun current(): ArcRanksConfigSnapshot = current.get()

    fun compareAndSet(expected: ArcRanksConfigSnapshot, replacement: ArcRanksConfigSnapshot): Boolean =
        current.compareAndSet(expected, replacement)
}

class ArcRanksConfigLoader(
    private val dataRoot: Path,
    private val environment: (String) -> String? = System::getenv,
) {
    /** Builds and validates a fully isolated generation without touching ConfigManager's live cache. */
    fun load(generation: Long): ArcRanksConfigSnapshot {
        ensureBundledResourcesExist()
        val beforeRead = fingerprints()
        val settings = ArcRanksSettings.load(Config(dataRoot, "config.yml"), dataRoot, environment)
        val ranks = RankCatalogLoader(Config(dataRoot, "ranks.yml")).loadWithMastery()
        val perks = PerkCatalogLoader(Config(dataRoot, "perks.yml")).load()
        val contracts = ContractCatalogLoader(Config(dataRoot, "contracts.yml")).load()
        val weeklyKits = WeeklyKitCatalogLoader(Config(dataRoot, "weekly-kits.yml")).load()
        val locale = RankLocale.fresh(
            dataRoot,
            defaultLocale = { settings.defaultLocale },
            useClientLocale = { settings.useClientLocale },
        )
        validate(settings, ranks, perks, weeklyKits, locale)
        val afterRead = fingerprints()
        require(beforeRead == afterRead) {
            "ArcRanks configuration files changed while the reload candidate was being read; retry reload"
        }
        return ArcRanksConfigSnapshot(
            generation = generation,
            settings = settings,
            ranks = ranks,
            perks = perks,
            contracts = contracts,
            weeklyKits = weeklyKits,
            locale = locale,
            fingerprints = afterRead,
        )
    }

    private fun fingerprints(): ArcRanksConfigFingerprints = ArcRanksConfigFingerprints(
        CONFIG_RESOURCES.associateWith { resource -> sha256(dataRoot.resolve(resource)) },
    )

    private fun ensureBundledResourcesExist() {
        CONFIG_RESOURCES.filterNot { resource -> Files.isRegularFile(dataRoot.resolve(resource)) }
            .forEach { resource -> Config(dataRoot, resource) }
    }

    private fun validate(
        settings: ArcRanksSettings,
        ranks: LoadedRankCatalog,
        perks: PerkCatalog,
        weeklyKits: WeeklyKitCatalog,
        locale: RankLocale,
    ) {
        val rankIds = ranks.catalog.ranks.map { it.id }.toSet()
        val kitRankIds = weeklyKits.definitions.map { it.rankId }.toSet()
        require(kitRankIds == rankIds) { "weekly-kits.yml must define exactly one kit for every configured rank" }

        val paperRuntimeAvailable = runCatching { Bukkit.getServer() }.getOrNull() != null
        settings.gui.allItems().forEach { (path, spec) ->
            validateItemMaterial(path, spec, paperRuntimeAvailable)
        }
        weeklyKits.definitions.forEach { definition ->
            validateItemMaterial(
                "weekly-kits.${definition.rankId.value}.icon",
                definition.icon,
                paperRuntimeAvailable,
            )
        }
        settings.collection.matureCropMaterials.forEach { name ->
            val material = Material.matchMaterial(name)
            require(material != null && (!paperRuntimeAvailable || material.isBlock)) {
                "progress.collection.mature-crop.materials contains a non-block material: $name"
            }
        }
        validateMaterialFilter(
            "progress.collection.block-place",
            settings.collection.blockPlace.materials.included + settings.collection.blockPlace.materials.excluded,
            paperRuntimeAvailable,
            Material::isBlock,
        )
        validateMaterialFilter(
            "progress.collection.crafting",
            settings.collection.crafting.materials.included + settings.collection.crafting.materials.excluded,
            paperRuntimeAvailable,
            Material::isItem,
        )
        validateMaterialFilter(
            "progress.collection.furnace",
            settings.collection.furnace.materials.included + settings.collection.furnace.materials.excluded,
            paperRuntimeAvailable,
            Material::isItem,
        )
        if (paperRuntimeAvailable) {
            runCatching { Sound.valueOf(settings.celebration.sound.type) }
                .getOrElse { throw IllegalArgumentException("Unknown celebration sound: ${settings.celebration.sound.type}", it) }
        }
        runCatching { SoundCategory.valueOf(settings.celebration.sound.category) }
            .getOrElse {
                throw IllegalArgumentException(
                    "Unknown celebration sound category: ${settings.celebration.sound.category}",
                    it,
                )
            }
        runCatching { FireworkEffect.Type.valueOf(settings.celebration.fireworks.type) }
            .getOrElse { throw IllegalArgumentException("Unknown celebration firework type: ${settings.celebration.fireworks.type}", it) }
        locale.validate(ranks.catalog, perks, weeklyKits)
    }

    private fun validateItemMaterial(path: String, spec: GuiItemSpec, paperRuntimeAvailable: Boolean) {
        val material = Material.matchMaterial(spec.material)
        require(material != null && (!paperRuntimeAvailable || material.isItem)) {
            "$path must resolve to a Bukkit item material"
        }
    }

    private fun validateMaterialFilter(
        path: String,
        names: Set<String>,
        paperRuntimeAvailable: Boolean,
        validKind: (Material) -> Boolean,
    ) {
        names.forEach { name ->
            val material = Material.matchMaterial(name)
            require(material != null && (!paperRuntimeAvailable || validKind(material))) {
                "$path contains an invalid material: $name"
            }
        }
    }

    private fun sha256(path: Path): String {
        require(Files.isRegularFile(path)) { "Missing ArcRanks configuration resource: ${path.fileName}" }
        return MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

enum class ArcRanksLiveArea {
    PROMOTION,
    FEATURES,
    LOGGING,
    LOCALE,
    RUNTIME,
    PROGRESS,
    ANALYTICS,
    GUI,
    CELEBRATION,
    RANK_RULES,
    MASTERY,
    PERK_RULES,
    CONTRACTS,
    WEEKLY_KITS,
}

data class ArcRanksConfigDiff(
    val liveAreas: Set<ArcRanksLiveArea>,
    val restartRequired: Set<String>,
) {
    val noChanges: Boolean
        get() = liveAreas.isEmpty() && restartRequired.isEmpty()
}

object ArcRanksConfigDiffer {
    fun diff(current: ArcRanksConfigSnapshot, candidate: ArcRanksConfigSnapshot): ArcRanksConfigDiff {
        if (current.fingerprints == candidate.fingerprints && current.settings.sql == candidate.settings.sql) {
            return ArcRanksConfigDiff(emptySet(), emptySet())
        }
        val restart = linkedSetOf<String>()
        val live = linkedSetOf<ArcRanksLiveArea>()
        val before = current.settings
        val after = candidate.settings

        if (before.serverId != after.serverId) restart += "server-id"
        mysqlChanges(before, after, restart)
        if (before.runtime.startupTimeoutSeconds != after.runtime.startupTimeoutSeconds) {
            restart += "runtime.startup-timeout-seconds"
        }
        rankTopologyChanges(current.ranks, candidate.ranks, restart)
        val oldPerkIds = current.perks.perks.map { it.id }.toSet()
        val newPerkIds = candidate.perks.perks.map { it.id }.toSet()
        if (oldPerkIds != newPerkIds) restart += "perks.ids"
        val oldKitRanks = current.weeklyKits.definitions.map { it.rankId }.toSet()
        val newKitRanks = candidate.weeklyKits.definitions.map { it.rankId }.toSet()
        if (oldKitRanks != newKitRanks) restart += "weekly-kits.rank-ids"

        if (before.promotionMode != after.promotionMode) live += ArcRanksLiveArea.PROMOTION
        if (before.features != after.features) live += ArcRanksLiveArea.FEATURES
        if (before.defaultLocale != after.defaultLocale || before.useClientLocale != after.useClientLocale ||
            localeFingerprints(current) != localeFingerprints(candidate)
        ) live += ArcRanksLiveArea.LOCALE
        if (before.runtime.shutdownFlushTimeoutSeconds != after.runtime.shutdownFlushTimeoutSeconds ||
            before.runtime.healthReportTicks != after.runtime.healthReportTicks
        ) live += ArcRanksLiveArea.RUNTIME
        if (progressSettings(before) != progressSettings(after)) live += ArcRanksLiveArea.PROGRESS
        if (before.analytics != after.analytics) live += ArcRanksLiveArea.ANALYTICS
        if (before.gui != after.gui) live += ArcRanksLiveArea.GUI
        if (before.celebration != after.celebration) live += ArcRanksLiveArea.CELEBRATION

        val oldRanks = current.ranks.catalog.ranks
        val newRanks = candidate.ranks.catalog.ranks
        if (oldRanks != newRanks && restart.none { it.startsWith("ranks.") }) live += ArcRanksLiveArea.RANK_RULES
        if (current.ranks.mastery != candidate.ranks.mastery) live += ArcRanksLiveArea.MASTERY
        if (current.perks.perks != candidate.perks.perks && "perks.ids" !in restart) live += ArcRanksLiveArea.PERK_RULES
        if (current.contracts != candidate.contracts) live += ArcRanksLiveArea.CONTRACTS
        if (current.weeklyKits.definitions != candidate.weeklyKits.definitions && "weekly-kits.rank-ids" !in restart) {
            live += ArcRanksLiveArea.WEEKLY_KITS
        }
        return ArcRanksConfigDiff(live, restart)
    }

    private fun mysqlChanges(
        before: ArcRanksSettings,
        after: ArcRanksSettings,
        changes: MutableSet<String>,
    ) {
        val left = before.sql
        val right = after.sql
        if (left.host != right.host) changes += "mysql.host"
        if (left.port != right.port) changes += "mysql.port"
        if (left.database != right.database) changes += "mysql.database"
        if (left.username != right.username) changes += "mysql.username"
        if (before.passwordEnvironment != after.passwordEnvironment || left.password != right.password) changes += "mysql.password"
        if (left.sslMode != right.sslMode) changes += "mysql.ssl-mode"
        if (left.minimumIdle != right.minimumIdle) changes += "mysql.minimum-idle"
        if (left.maximumPoolSize != right.maximumPoolSize) changes += "mysql.maximum-pool-size"
        if (left.connectionTimeoutMs != right.connectionTimeoutMs) changes += "mysql.connection-timeout-ms"
        if (left.socketTimeoutMs != right.socketTimeoutMs) changes += "mysql.socket-timeout-ms"
        if (left.validationTimeoutMs != right.validationTimeoutMs) changes += "mysql.validation-timeout-ms"
        if (left.maxLifetimeMs != right.maxLifetimeMs) changes += "mysql.max-lifetime-ms"
        if (left.failFast != right.failFast) changes += "mysql.fail-fast"
    }

    private fun rankTopologyChanges(
        before: LoadedRankCatalog,
        after: LoadedRankCatalog,
        changes: MutableSet<String>,
    ) {
        val left = before.catalog.ranks.associateBy { it.id }
        val right = after.catalog.ranks.associateBy { it.id }
        if (left.keys != right.keys) changes += "ranks.ids"
        left.keys.intersect(right.keys).sortedBy { it.value }.forEach { id ->
            val old = left.getValue(id)
            val new = right.getValue(id)
            if (old.order != new.order) changes += "ranks.${id.value}.order"
            if (old.luckPermsGroup != new.luckPermsGroup) changes += "ranks.${id.value}.group"
        }
    }

    private fun localeFingerprints(snapshot: ArcRanksConfigSnapshot): List<String> =
        listOf("lang/ru.yml", "lang/en.yml").map { snapshot.fingerprints.files.getValue(it) }

    private fun progressSettings(settings: ArcRanksSettings): List<Any> = listOf(
        settings.maximumBufferEntries,
        settings.flushTicks,
        settings.sampleTicks,
        settings.maximumIdleSeconds,
        settings.maximumMovementStepBlocks,
        settings.communityRadiusBlocks,
        settings.collection,
    )
}

private fun GuiSettings.allItems(): Map<String, GuiItemSpec> = buildMap {
    put("gui.background", background)
    put("gui.back", back)
    put("gui.rank-completed", rankCompleted)
    put("gui.rank-current", rankCurrent)
    put("gui.rank-next", rankNext)
    put("gui.rank-locked", rankLocked)
    put("gui.path", path)
    put("gui.promotion", promotion)
    put("gui.contracts", contracts)
    put("gui.perks", perks)
    put("gui.analytics", analytics)
    items.forEach { (key, value) -> put("gui.items.$key", value) }
}

internal val CONFIG_RESOURCES = listOf(
    "config.yml",
    "ranks.yml",
    "perks.yml",
    "contracts.yml",
    "weekly-kits.yml",
    "lang/ru.yml",
    "lang/en.yml",
)
