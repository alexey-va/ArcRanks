package ru.ruscrafting.ranks.paper

import net.luckperms.api.LuckPerms
import net.milkbowl.vault.economy.Economy
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.ConfigManager
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.logging.ArcLogging
import ru.arc.logging.LoggingConfigSource
import ru.arc.logging.LoggingModuleConfig
import ru.arc.logging.LokiAttachTarget
import ru.arc.logging.LokiInstallSpec
import ru.arc.logging.paper.PaperLoggingPlatform
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.observability.StructuredDebugLine
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.sql.SqlRuntime
import ru.ruscrafting.ranks.api.RankProgressApi
import ru.ruscrafting.ranks.api.RepositoryRankProgressApi
import ru.ruscrafting.ranks.analytics.AnalyticsService
import ru.ruscrafting.ranks.analytics.MySqlAnalyticsRepository
import ru.ruscrafting.ranks.analytics.ProductTelemetry
import ru.ruscrafting.ranks.analytics.TelemetryHealthSnapshot
import ru.ruscrafting.ranks.command.RankCommand
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.config.LoadedRankCatalog
import ru.ruscrafting.ranks.config.RankCatalogLoader
import ru.ruscrafting.ranks.domain.PathAvailability
import ru.ruscrafting.ranks.domain.RankEvaluator
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.contract.ContractCatalog
import ru.ruscrafting.ranks.contract.ContractCatalogLoader
import ru.ruscrafting.ranks.contract.ContractOfferGenerator
import ru.ruscrafting.ranks.contract.ContractService
import ru.ruscrafting.ranks.contract.MySqlContractRepository
import ru.ruscrafting.ranks.gui.AnalyticsMenu
import ru.ruscrafting.ranks.gui.ContractMenu
import ru.ruscrafting.ranks.gui.PerkMenu
import ru.ruscrafting.ranks.gui.RankPassportMenu
import ru.ruscrafting.ranks.gui.WeeklyKitMenu
import ru.ruscrafting.ranks.kit.CmiWeeklyKitProvider
import ru.ruscrafting.ranks.kit.MySqlWeeklyKitRepository
import ru.ruscrafting.ranks.kit.WeeklyKitCatalog
import ru.ruscrafting.ranks.kit.WeeklyKitCatalogLoader
import ru.ruscrafting.ranks.kit.WeeklyKitService
import ru.ruscrafting.ranks.perk.FractionalProgressBonus
import ru.ruscrafting.ranks.perk.MySqlPerkSelectionRepository
import ru.ruscrafting.ranks.perk.PerkCatalog
import ru.ruscrafting.ranks.perk.PerkCatalogLoader
import ru.ruscrafting.ranks.perk.PerkProgressModifier
import ru.ruscrafting.ranks.perk.PerkSelectionService
import ru.ruscrafting.ranks.placeholder.ArcRanksPlaceholderExpansion
import ru.ruscrafting.ranks.progress.MovementAccumulator
import ru.ruscrafting.ranks.progress.PeriodicProgressSampler
import ru.ruscrafting.ranks.progress.ProgressBuffer
import ru.ruscrafting.ranks.progress.RankProgressListener
import ru.ruscrafting.ranks.presentation.PromotionCelebration
import ru.ruscrafting.ranks.promotion.MySqlPromotionRepository
import ru.ruscrafting.ranks.promotion.PromotionService
import ru.ruscrafting.ranks.rankstate.LuckPermsRankStateGateway
import ru.ruscrafting.ranks.service.RankPlayerService
import ru.ruscrafting.ranks.service.RankSnapshotCache
import ru.ruscrafting.ranks.service.PlayerSnapshotListener
import ru.ruscrafting.ranks.storage.MySqlProgressRepository
import ru.ruscrafting.ranks.text.RankLocale
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level

class ArcRanksPlugin : JavaPlugin() {
    private lateinit var settings: ArcRanksSettings
    private lateinit var loadedCatalog: LoadedRankCatalog
    private lateinit var perkCatalog: PerkCatalog
    private lateinit var contractCatalog: ContractCatalog
    private lateinit var weeklyKitCatalog: WeeklyKitCatalog
    private lateinit var locale: RankLocale
    private var lifecycle: PaperPluginRuntime? = null
    private var buffer: ProgressBuffer? = null
    private var placeholders: ArcRanksPlaceholderExpansion? = null
    private var telemetry: ProductTelemetry? = null
    private val sqlReady = AtomicBoolean(false)
    private val luckPermsReady = AtomicBoolean(false)
    private val cachedPlayers = AtomicReference<RankSnapshotCache>()
    private val debug = StructuredDebugLine("ARCRANKS_EVENT")

    override fun onEnable() {
        saveDefaultConfig()
        saveResourceIfMissing("ranks.yml")
        saveResourceIfMissing("perks.yml")
        saveResourceIfMissing("contracts.yml")
        saveResourceIfMissing("weekly-kits.yml")
        saveResourceIfMissing("lang/ru.yml")
        saveResourceIfMissing("lang/en.yml")
        PaperArcRuntime.installScheduling(this)
        try {
            mergeBundledDefaults()
            settings = ArcRanksSettings.load(dataPath)
            loadedCatalog = RankCatalogLoader(ConfigManager.of(dataPath, "ranks.yml")).loadWithMastery()
            perkCatalog = PerkCatalogLoader(ConfigManager.of(dataPath, "perks.yml")).load()
            contractCatalog = ContractCatalogLoader(ConfigManager.of(dataPath, "contracts.yml")).load()
            weeklyKitCatalog = WeeklyKitCatalogLoader(ConfigManager.of(dataPath, "weekly-kits.yml")).load()
            locale = RankLocale(dataPath, { settings.defaultLocale }, { settings.useClientLocale })
            locale.validate(loadedCatalog.catalog, perkCatalog, weeklyKitCatalog)
            installLogging()

            val runtime = PaperPluginRuntime(this, "arc-ranks").also {
                lifecycle = it
                it.start("version" to pluginMeta.version, "mode" to settings.promotionMode.name.lowercase())
            }
            val sql = runtime.own(SqlRuntime.create(settings.sql, "arc-ranks-${settings.serverId}"))
            val progress = MySqlProgressRepository(sql)
            val promotionRepository = MySqlPromotionRepository(sql)
            progress.initialize().get(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            sqlReady.set(true)
            val analyticsRepository = MySqlAnalyticsRepository(sql)
            val perkRepository = MySqlPerkSelectionRepository(sql)
            val contractRepository = MySqlContractRepository(sql)
            val weeklyKitRepository = MySqlWeeklyKitRepository(sql)
            val productTelemetry = settings.analytics.takeIf { it.enabled }?.let { analyticsSettings ->
                ProductTelemetry(
                    settings.serverId,
                    java.time.Clock.systemUTC(),
                    analyticsSettings.maximumMetricKeys,
                    analyticsSettings.maximumPlayers,
                    analyticsRepository::write,
                )
            }.also { telemetry = it }
            val analyticsService = AnalyticsService(
                analyticsRepository,
                java.time.Clock.systemUTC(),
                Duration.ofSeconds(settings.analytics.summaryCacheSeconds),
            )

            val luckPerms = requireNotNull(server.servicesManager.getRegistration(LuckPerms::class.java)?.provider) {
                "LuckPerms service is unavailable"
            }
            luckPermsReady.set(true)
            val rankState = LuckPermsRankStateGateway(luckPerms, loadedCatalog.catalog)
            val economy = server.servicesManager.getRegistration(Economy::class.java)?.provider
            val availability = { PathAvailability(if (economy == null) setOf(SpecializationPath.TRADE) else emptySet()) }
            val progressBuffer = ProgressBuffer(settings.maximumBufferEntries, progress::applyMutations).also { buffer = it }
            val evaluator = RankEvaluator(loadedCatalog.catalog)
            val cache = RankSnapshotCache().also(cachedPlayers::set)
            val perkService = PerkSelectionService(perkCatalog, perkRepository, productTelemetry, cache::updatePerks)
            val playerService = RankPlayerService(
                rankState,
                progress,
                evaluator,
                loadedCatalog.mastery,
                availability,
                cache,
                perkService::load,
            )
            val progressModifier = PerkProgressModifier(
                progressBuffer,
                perkCatalog,
                FractionalProgressBonus(),
                productTelemetry,
            ) { playerId -> cache.get(playerId)?.activePerks.orEmpty() }
            val api = RepositoryRankProgressApi(progress)
            val contractService = ContractService(
                contractRepository,
                ContractOfferGenerator(contractCatalog, perkCatalog),
                java.time.Clock.systemUTC(),
                productTelemetry,
                progressBuffer::flush,
            )
            val healthSnapshot = { productTelemetry?.healthSnapshot() ?: EMPTY_TELEMETRY_HEALTH }
            lateinit var menu: RankPassportMenu
            val contractMenu = ContractMenu(
                { settings }, { locale }, playerService, contractService, runtime.tasks, productTelemetry,
            ) { player -> menu.open(player) }
            val perkMenu = PerkMenu(
                { settings }, { locale }, perkCatalog, playerService, perkService, runtime.tasks, productTelemetry,
            ) { player -> menu.open(player) }
            val analyticsMenu = AnalyticsMenu(
                { settings }, { locale }, analyticsService, healthSnapshot, runtime.tasks, productTelemetry,
            ) { player -> menu.open(player) }
            val weeklyKitService = WeeklyKitService(
                weeklyKitRepository,
                CmiWeeklyKitProvider(runtime.tasks),
                java.time.Clock.systemUTC(),
            )
            val weeklyKitMenu = WeeklyKitMenu(
                { settings }, { locale }, weeklyKitCatalog, playerService, weeklyKitService,
                runtime.tasks, productTelemetry,
            ) { player -> menu.open(player) }
            val celebration = PromotionCelebration(
                server,
                { loadedCatalog.catalog },
                { locale },
                runtime.tasks,
            ) { playerId, rankId ->
                logger.info(debug.line("event" to "promotion", "player" to playerId, "rank" to rankId.value))
            }
            val promotionService = PromotionService(
                loadedCatalog.catalog,
                evaluator,
                progress,
                progressBuffer,
                rankState,
                promotionRepository,
                availability,
                celebration::celebrate,
                productTelemetry,
            )
            menu = RankPassportMenu(
                { settings },
                { loadedCatalog.catalog },
                { locale },
                playerService,
                promotionService,
                runtime.tasks,
                productTelemetry,
                contractMenu::open,
                perkMenu::open,
                weeklyKitMenu::open,
            )
            val command = RankCommand(
                server,
                { settings },
                { loadedCatalog.catalog },
                { locale },
                playerService,
                api,
                promotionService,
                menu,
                contractMenu,
                perkMenu,
                weeklyKitMenu,
                analyticsMenu,
                analyticsService,
                healthSnapshot,
                runtime.tasks,
                ::reloadPlugin,
            )
            requireNotNull(getCommand("rank")).apply { setExecutor(command); tabCompleter = command }
            requireNotNull(getCommand("rankup")).apply { setExecutor(command); tabCompleter = command }
            server.pluginManager.registerEvents(menu, this)
            server.pluginManager.registerEvents(contractMenu, this)
            server.pluginManager.registerEvents(perkMenu, this)
            server.pluginManager.registerEvents(weeklyKitMenu, this)
            server.pluginManager.registerEvents(celebration, this)
            server.pluginManager.registerEvents(analyticsMenu, this)
            server.pluginManager.registerEvents(PlayerSnapshotListener(playerService, cache, productTelemetry), this)
            server.pluginManager.registerEvents(
                RankProgressListener(progressBuffer, MovementAccumulator(settings.maximumMovementStepBlocks), progressModifier),
                this,
            )
            PeriodicProgressSampler(server, { settings }, progressBuffer, progressModifier, economy, runtime.tasks).start()
            productTelemetry?.let { telemetry ->
                runtime.tasks.runTimerAsync(settings.analytics.flushTicks, settings.analytics.flushTicks) {
                    telemetry.flush()
                }
            }

            server.servicesManager.register(RankProgressApi::class.java, api, this, ServicePriority.Normal)
            installPlaceholders(cache)
            installHealth(runtime, economy != null)
            runtime.ready(
                "server" to settings.serverId,
                "mode" to settings.promotionMode.name.lowercase(),
                "vault" to (economy != null),
            )
            runtime.reportHealthEvery(HEALTH_REPORT_TICKS)
            logger.info(debug.line("state" to "ready", "server" to settings.serverId, "mode" to settings.promotionMode.name.lowercase()))
        } catch (failure: Throwable) {
            sqlReady.set(false)
            luckPermsReady.set(false)
            runCatching { lifecycle?.health?.markDown(); lifecycle?.emitHealth() }
            logger.log(Level.SEVERE, "ArcRanks failed closed during startup", failure)
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        runCatching {
            val flushes = listOfNotNull(buffer?.flushAll(), telemetry?.flushAll())
            CompletableFuture.allOf(*flushes.toTypedArray()).get(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
            .onFailure { logger.log(Level.WARNING, "Could not flush every ArcRanks progress mutation during shutdown", it) }
        runCatching { placeholders?.unregister() }
        server.servicesManager.unregisterAll(this)
        runCatching { lifecycle?.close() }
        placeholders = null
        telemetry = null
        buffer = null
        lifecycle = null
        sqlReady.set(false)
        luckPermsReady.set(false)
        cachedPlayers.set(null)
        ConfigManager.clear()
        Tasks.reset()
    }

    private fun mergeBundledDefaults() {
        listOf("config.yml", "ranks.yml", "perks.yml", "contracts.yml", "weekly-kits.yml", "lang/ru.yml", "lang/en.yml").forEach { resource ->
            ConfigManager.of(dataPath, resource).mergeMissingFromBundled(resource)
        }
    }

    private fun reloadPlugin(): Result<Unit> = runCatching {
        val previous = settings
        ConfigManager.reloadAll()
        val candidateSettings = ArcRanksSettings.load(dataPath)
        val candidateCatalog = RankCatalogLoader(ConfigManager.of(dataPath, "ranks.yml")).loadWithMastery()
        val candidatePerks = PerkCatalogLoader(ConfigManager.of(dataPath, "perks.yml")).load()
        val candidateContracts = ContractCatalogLoader(ConfigManager.of(dataPath, "contracts.yml")).load()
        val candidateWeeklyKits = WeeklyKitCatalogLoader(ConfigManager.of(dataPath, "weekly-kits.yml")).load()
        require(candidateSettings.serverId == previous.serverId) { "server-id requires a restart" }
        require(candidateSettings.sql == previous.sql) { "mysql settings require a restart" }
        require(candidateSettings.maximumBufferEntries == previous.maximumBufferEntries) { "buffer capacity requires a restart" }
        require(candidateSettings.flushTicks == previous.flushTicks && candidateSettings.sampleTicks == previous.sampleTicks) {
            "progress task periods require a restart"
        }
        require(candidateSettings.maximumIdleSeconds == previous.maximumIdleSeconds) { "idle threshold requires a restart" }
        require(candidateSettings.maximumMovementStepBlocks == previous.maximumMovementStepBlocks) {
            "movement step requires a restart"
        }
        require(candidateSettings.communityRadiusBlocks == previous.communityRadiusBlocks) {
            "community radius requires a restart"
        }
        require(candidateSettings.analytics == previous.analytics) { "analytics settings require a restart" }
        require(candidateCatalog == loadedCatalog) { "rank thresholds and LuckPerms groups require a restart" }
        require(candidatePerks == perkCatalog) { "perk definitions require a restart" }
        require(candidateContracts == contractCatalog) { "contract definitions require a restart" }
        require(candidateWeeklyKits == weeklyKitCatalog) { "weekly kit definitions require a restart" }
        val candidateLocale = RankLocale(dataPath, { candidateSettings.defaultLocale }, { candidateSettings.useClientLocale })
        candidateLocale.validate(candidateCatalog.catalog, candidatePerks, candidateWeeklyKits)
        settings = candidateSettings
        locale = candidateLocale
        lifecycle?.reload()
        lifecycle?.ready("server" to settings.serverId, "mode" to settings.promotionMode.name.lowercase())
    }

    private fun installHealth(runtime: PaperPluginRuntime, vaultAvailable: Boolean) {
        runtime.registerHealth("progression") {
            RuntimeHealthContribution(
                state = if (sqlReady.get() && luckPermsReady.get()) RuntimeHealthState.UP else RuntimeHealthState.DOWN,
                schemas = mapOf("progress" to 1, "promotion" to 2, "external_events" to 1, "analytics" to 3, "perks" to 4, "contracts" to 5, "weekly_kits" to 6),
                dependencies = mapOf(
                    "mysql" to sqlReady.get(),
                    "luckperms" to luckPermsReady.get(),
                    "vault_trade_path" to vaultAvailable,
                ),
            )
        }
    }

    private fun installPlaceholders(cache: RankSnapshotCache) {
        if (!server.pluginManager.isPluginEnabled("PlaceholderAPI")) return
        placeholders = ArcRanksPlaceholderExpansion(
            this,
            { loadedCatalog.catalog },
            { locale },
            { loadedCatalog.mastery },
            cache,
        ).also { require(it.register()) { "Could not register ArcRanks PlaceholderAPI expansion" } }
    }

    private fun installLogging() {
        val path = ConfigManager.moduleYamlPath(dataPath, LoggingModuleConfig.RESOURCE)
        val existed = Files.isRegularFile(path)
        val logging = ConfigManager.ofModule(dataPath, LoggingModuleConfig.RESOURCE)
        if (!existed) {
            logging.setString("labels.service_name", "arc-ranks-${settings.serverId}")
            logging.setString("labels.job", "paper")
            logging.saveStrict()
        }
        ArcLogging.install(
            platform = PaperLoggingPlatform("ArcRanks", "ArcRanks"),
            configSource = object : LoggingConfigSource {
                override fun config() = logging
                override fun configVersion(): Int = ConfigManager.getVersion()
            },
            loki = LokiInstallSpec(
                dataFolder = dataPath,
                target = LokiAttachTarget.LOGGER_PREFIX,
                loggerPrefix = "ru.ruscrafting.ranks",
                appenderName = "ArcRanksLokiAppender",
            ),
        )
    }

    private fun saveResourceIfMissing(path: String) {
        if (!Files.isRegularFile(dataPath.resolve(path))) saveResource(path, false)
    }

    private companion object {
        const val STARTUP_TIMEOUT_SECONDS = 30L
        const val SHUTDOWN_TIMEOUT_SECONDS = 5L
        const val HEALTH_REPORT_TICKS = 1_200L
        val EMPTY_TELEMETRY_HEALTH = TelemetryHealthSnapshot(0, 0, 0, 0, 0, 0, false)
    }
}
