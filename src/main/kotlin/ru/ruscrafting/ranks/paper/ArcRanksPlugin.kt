package ru.ruscrafting.ranks.paper

import net.luckperms.api.LuckPerms
import net.milkbowl.vault.economy.Economy
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
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
import ru.arc.sql.onetime.MySqlOneTimeUseLedger
import ru.arc.sql.onetime.MySqlOneTimeUsePartition
import ru.ruscrafting.ranks.analytics.AnalyticsService
import ru.ruscrafting.ranks.analytics.AnalyticsTuning
import ru.ruscrafting.ranks.admin.AdminProgressService
import ru.ruscrafting.ranks.analytics.MySqlAnalyticsRepository
import ru.ruscrafting.ranks.analytics.ProductTelemetry
import ru.ruscrafting.ranks.analytics.ProductTelemetryTuning
import ru.ruscrafting.ranks.api.RankProgressApi
import ru.ruscrafting.ranks.api.RepositoryRankProgressApi
import ru.ruscrafting.ranks.command.RankCommand
import ru.ruscrafting.ranks.config.ArcRanksConfigLoader
import ru.ruscrafting.ranks.config.ArcRanksConfigStore
import ru.ruscrafting.ranks.config.ArcRanksLiveArea
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.contract.ContractOfferConfiguration
import ru.ruscrafting.ranks.contract.ContractOfferGenerator
import ru.ruscrafting.ranks.contract.ContractService
import ru.ruscrafting.ranks.contract.ContractRewardDeliveryService
import ru.ruscrafting.ranks.contract.PaperContractRewardProvider
import ru.ruscrafting.ranks.contract.MySqlContractRepository
import ru.ruscrafting.ranks.domain.PathAvailability
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.dialog.RankDialogController
import ru.ruscrafting.ranks.gui.AnalyticsMenu
import ru.ruscrafting.ranks.gui.ArcRanksMenuSessions
import ru.ruscrafting.ranks.gui.ArcRanksMenuLayouts
import ru.ruscrafting.ranks.gui.ContractMenu
import ru.ruscrafting.ranks.gui.PerkMenu
import ru.ruscrafting.ranks.gui.RankPassportMenu
import ru.ruscrafting.ranks.gui.WeeklyKitMenu
import ru.ruscrafting.ranks.kit.CmiWeeklyKitProvider
import ru.ruscrafting.ranks.kit.MySqlWeeklyKitRepository
import ru.ruscrafting.ranks.kit.WeeklyKitService
import ru.ruscrafting.ranks.perk.FractionalProgressBonus
import ru.ruscrafting.ranks.perk.MySqlPerkSelectionRepository
import ru.ruscrafting.ranks.perk.PerkProgressModifier
import ru.ruscrafting.ranks.perk.PerkSelectionService
import ru.ruscrafting.ranks.perk.eligibleForProgress
import ru.ruscrafting.ranks.placeholder.ArcRanksPlaceholderExpansion
import ru.ruscrafting.ranks.presentation.PromotionCelebration
import ru.ruscrafting.ranks.progress.MovementAccumulator
import ru.ruscrafting.ranks.progress.MovementTuning
import ru.ruscrafting.ranks.progress.AuctionProgressIntegration
import ru.ruscrafting.ranks.progress.BuildingProgressGate
import ru.ruscrafting.ranks.progress.BuilderProgressIntegration
import ru.ruscrafting.ranks.progress.DynamicTickCadence
import ru.ruscrafting.ranks.progress.EliteMobsProgressListener
import ru.ruscrafting.ranks.progress.PeriodicProgressSampler
import ru.ruscrafting.ranks.progress.ProgressBuffer
import ru.ruscrafting.ranks.progress.RankProgressListener
import ru.ruscrafting.ranks.promotion.MySqlPromotionRepository
import ru.ruscrafting.ranks.promotion.PromotionConfiguration
import ru.ruscrafting.ranks.promotion.PromotionService
import ru.ruscrafting.ranks.rankstate.LuckPermsRankStateGateway
import ru.ruscrafting.ranks.reload.ArcRanksReloadCoordinator
import ru.ruscrafting.ranks.reload.ArcRanksReloadResult
import ru.ruscrafting.ranks.reload.ArcRanksLoggingReloader
import ru.ruscrafting.ranks.reload.ArcRanksLoggingReloadResult
import ru.ruscrafting.ranks.service.PlayerSnapshotListener
import ru.ruscrafting.ranks.service.RankPlayerEvaluationConfiguration
import ru.ruscrafting.ranks.service.RankPlayerService
import ru.ruscrafting.ranks.service.RankSnapshotCache
import ru.ruscrafting.ranks.storage.MySqlProgressRepository
import ru.arc.paper.menu.PaperDialogRuntime
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level

class ArcRanksPlugin : JavaPlugin() {
    private lateinit var configuration: ArcRanksConfigStore
    private lateinit var reloadCoordinator: ArcRanksReloadCoordinator
    private lateinit var loggingReloader: ArcRanksLoggingReloader
    private lateinit var menuLayouts: ArcRanksMenuLayouts
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
            val loader = ArcRanksConfigLoader(dataPath)
            val initial = loader.load(1)
            configuration = ArcRanksConfigStore(initial)
            menuLayouts = ArcRanksMenuLayouts(dataPath)
            installLogging(initial.settings)

            val runtime = PaperPluginRuntime(this, "arc-ranks").also {
                lifecycle = it
                it.start("version" to pluginMeta.version, "mode" to initial.settings.promotionMode.name.lowercase())
            }
            val callbackTasks = runtime.own(LifecycleTaskScope())
            val dialogRuntime = runtime.own(PaperDialogRuntime(this))
            val sql = runtime.own(SqlRuntime.create(initial.settings.sql, "arc-ranks-${initial.settings.serverId}"))
            val progress = MySqlProgressRepository(sql)
            val promotionRepository = MySqlPromotionRepository(sql)
            progress.initialize().get(initial.settings.runtime.startupTimeoutSeconds, TimeUnit.SECONDS)
            sqlReady.set(true)
            val analyticsRepository = MySqlAnalyticsRepository(sql)
            val perkRepository = MySqlPerkSelectionRepository(sql)
            val contractRepository = MySqlContractRepository(sql)
            val weeklyKitRepository = MySqlWeeklyKitRepository(sql)
            val productTelemetry = ProductTelemetry(
                initial.settings.serverId,
                Clock.systemUTC(),
                tuningProvider = {
                    val analytics = configuration.current().settings.analytics
                    ProductTelemetryTuning(analytics.enabled, analytics.maximumMetricKeys, analytics.maximumPlayers)
                },
                writer = analyticsRepository::write,
            ).also { telemetry = it }
            val analyticsService = AnalyticsService(
                analyticsRepository,
                Clock.systemUTC(),
                tuningProvider = {
                    val analytics = configuration.current().settings.analytics
                    AnalyticsTuning(analytics.windows.toSet(), Duration.ofSeconds(analytics.summaryCacheSeconds))
                },
            )

            val luckPerms = requireNotNull(server.servicesManager.getRegistration(LuckPerms::class.java)?.provider) {
                "LuckPerms service is unavailable"
            }
            luckPermsReady.set(true)
            // Rank id/group/order topology is restart-only, so this gateway remains valid for the process lifetime.
            val rankState = LuckPermsRankStateGateway(luckPerms, initial.ranks.catalog)
            val economy = server.servicesManager.getRegistration(Economy::class.java)?.provider
            val auctionAvailable = AtomicBoolean(false)
            val availability = {
                val collection = configuration.current().settings.collection
                val tradeAvailable = economy != null || collection.villagerTrade.enabled ||
                    (collection.auctionDeal.enabled && auctionAvailable.get())
                PathAvailability(if (tradeAvailable) emptySet() else setOf(SpecializationPath.TRADE))
            }
            val progressBuffer = ProgressBuffer(
                maximumEntriesProvider = { configuration.current().settings.maximumBufferEntries },
                writer = progress::applyMutations,
            ).also { buffer = it }
            val cache = RankSnapshotCache().also(cachedPlayers::set)
            val perkService = PerkSelectionService(
                catalogProvider = { configuration.current().perks },
                repository = perkRepository,
                telemetry = productTelemetry,
                onChanged = cache::updatePerks,
            )
            val playerService = RankPlayerService(
                rankState = rankState,
                progress = progress,
                evaluationConfiguration = {
                    val snapshot = configuration.current()
                    RankPlayerEvaluationConfiguration(snapshot.evaluator, snapshot.ranks.mastery)
                },
                availability = availability,
                cache = cache,
                perks = perkService::load,
            )
            val progressModifier = PerkProgressModifier(
                buffer = progressBuffer,
                catalogProvider = { configuration.current().perks },
                fractionalBonus = FractionalProgressBonus(),
                telemetry = productTelemetry,
                selectedPerks = { playerId ->
                    val source = cache.perkProgress(playerId)
                    val snapshot = configuration.current()
                    if (source == null || !snapshot.settings.features.perks) {
                        emptySet()
                    } else {
                        snapshot.perks.eligibleForProgress(
                            source.activePerks,
                            source.profile,
                            snapshot.ranks.mastery,
                        )
                    }
                },
            )
            val api = RepositoryRankProgressApi(progress)
            val contractService = ContractService(
                contractRepository,
                ContractOfferGenerator(
                    configuration = {
                        val snapshot = configuration.current()
                        ContractOfferConfiguration(snapshot.contracts, snapshot.perks)
                    },
                ),
                Clock.systemUTC(),
                productTelemetry,
                progressBuffer::flush,
            )
            val redisEconomyClassLoader = server.pluginManager.getPlugin("RedisEconomy")?.javaClass?.classLoader
            val contractRewardProvider = PaperContractRewardProvider.create(
                server,
                economy,
                redisEconomyClassLoader,
                initial.contracts.bonusRewards,
            )
            val contractRewardLedger = runtime.own(
                MySqlOneTimeUseLedger.attach(
                    sql,
                    "arc-ranks-${initial.settings.serverId}",
                    MySqlOneTimeUsePartition("contract_reward"),
                ),
            )
            val contractRewardDelivery = ContractRewardDeliveryService(
                contractRepository,
                contractRewardLedger,
                contractRewardProvider,
                callbackTasks,
                logger,
            )
            val healthSnapshot = productTelemetry::healthSnapshot
            val settings = { configuration.current().settings }
            val locale = { configuration.current().locale }
            val generation = { configuration.current().generation }
            lateinit var menu: RankPassportMenu
            val contractMenu = ContractMenu(
                settings, locale, playerService, contractService, callbackTasks, productTelemetry,
                back = { player -> menu.open(player) },
                layouts = menuLayouts,
                configGeneration = generation,
                rewardDelivery = contractRewardDelivery::deliver,
            )
            val perkMenu = PerkMenu(
                settings, locale, { configuration.current().perks }, playerService, perkService,
                callbackTasks, productTelemetry,
                back = { player -> menu.open(player) },
                layouts = menuLayouts,
                configGeneration = generation,
            )
            val analyticsMenu = AnalyticsMenu(
                settings, locale, analyticsService, healthSnapshot, callbackTasks, productTelemetry,
                back = { player -> menu.open(player) },
                layouts = menuLayouts,
                configGeneration = generation,
            )
            val weeklyKitService = WeeklyKitService(
                weeklyKitRepository,
                CmiWeeklyKitProvider(callbackTasks),
                Clock.systemUTC(),
            )
            val weeklyKitMenu = WeeklyKitMenu(
                settings, locale, { configuration.current().weeklyKits }, playerService, weeklyKitService,
                callbackTasks, productTelemetry,
                back = { player -> menu.open(player) },
                layouts = menuLayouts,
                configGeneration = generation,
            )
            val celebration = PromotionCelebration(
                server,
                configuration::current,
                callbackTasks,
            ) { playerId, rankId ->
                logger.info(debug.line("event" to "promotion", "player" to playerId, "rank" to rankId.value))
            }
            val promotionService = PromotionService(
                configuration = {
                    val snapshot = configuration.current()
                    PromotionConfiguration(snapshot.ranks.catalog, snapshot.evaluator)
                },
                progress = progress,
                buffer = progressBuffer,
                rankState = rankState,
                promotions = promotionRepository,
                availability = availability,
                celebrate = celebration::celebrate,
                telemetry = productTelemetry,
            )
            val adminProgressService = AdminProgressService(api)
            menu = RankPassportMenu(
                settings = settings,
                catalog = { configuration.current().ranks.catalog },
                locale = locale,
                players = playerService,
                promotions = promotionService,
                adminProgress = adminProgressService,
                tasks = callbackTasks,
                telemetry = productTelemetry,
                openContracts = contractMenu::open,
                openPerks = perkMenu::open,
                openWeeklyKit = weeklyKitMenu::open,
                openAnalytics = analyticsMenu::open,
                layouts = menuLayouts,
                configGeneration = generation,
            )
            val dialogs = RankDialogController(
                runtime = dialogRuntime,
                settings = settings,
                catalog = { configuration.current().ranks.catalog },
                locale = locale,
                players = playerService,
                promotions = promotionService,
                adminProgress = adminProgressService,
                contracts = contractService,
                rewardDelivery = contractRewardDelivery::deliver,
                perksCatalog = { configuration.current().perks },
                perks = perkService,
                weeklyKitCatalog = { configuration.current().weeklyKits },
                weeklyKits = weeklyKitService,
                analytics = analyticsService,
                analyticsHealth = healthSnapshot,
                tasks = callbackTasks,
                openHelp = { player -> if (!player.performCommand("menu")) menu.open(player) },
            )
            val command = RankCommand(
                server,
                settings,
                { configuration.current().ranks.catalog },
                locale,
                playerService,
                api,
                adminProgressService,
                promotionService,
                menu,
                contractMenu,
                contractService,
                perkMenu,
                weeklyKitMenu,
                weeklyKitService,
                analyticsMenu,
                analyticsService,
                healthSnapshot,
                callbackTasks,
                ::reloadPlugin,
                dialogs,
            )
            requireNotNull(getCommand("rank")).apply { setExecutor(command); tabCompleter = command }
            requireNotNull(getCommand("rankup")).apply { setExecutor(command); tabCompleter = command }
            server.pluginManager.registerEvents(menu, this)
            server.pluginManager.registerEvents(dialogs, this)
            server.pluginManager.registerEvents(contractMenu, this)
            server.pluginManager.registerEvents(contractRewardDelivery, this)
            server.pluginManager.registerEvents(perkMenu, this)
            server.pluginManager.registerEvents(weeklyKitMenu, this)
            server.pluginManager.registerEvents(celebration, this)
            server.pluginManager.registerEvents(analyticsMenu, this)
            server.pluginManager.registerEvents(PlayerSnapshotListener(playerService, cache, productTelemetry), this)
            val movement = MovementAccumulator {
                val current = configuration.current().settings
                MovementTuning(current.maximumMovementStepBlocks, current.collection.travelIncludeVertical)
            }
            val buildingProgress = BuildingProgressGate()
            server.pluginManager.registerEvents(
                RankProgressListener(progressBuffer, movement, progressModifier, settings, callbackTasks, building = buildingProgress),
                this,
            )
            val eliteMobsAvailable = server.pluginManager.isPluginEnabled("EliteMobs")
            if (eliteMobsAvailable) {
                server.pluginManager.registerEvents(
                    EliteMobsProgressListener(api, settings, logger, cache::invalidateSnapshot),
                    this,
                )
            }
            auctionAvailable.set(AuctionProgressIntegration(this, api, settings, cache::invalidateSnapshot).install())
            BuilderProgressIntegration(this, api, settings, buildingProgress, callbackTasks, cache::invalidateSnapshot).install()
            val sampler = PeriodicProgressSampler(server, settings, progressBuffer, progressModifier, economy)

            server.servicesManager.register(RankProgressApi::class.java, api, this, ServicePriority.Normal)
            server.onlinePlayers.forEach(contractRewardDelivery::deliverPending)
            installPlaceholders(cache)
            installHealth(runtime, economy != null, auctionAvailable::get, eliteMobsAvailable, progressBuffer)
            val recurringTasks = ArcRanksRecurringTasks(
                runtime = runtime,
                callbackTasks = callbackTasks,
                settings = settings,
                sampler = sampler,
                progressBuffer = progressBuffer,
                productTelemetry = productTelemetry,
                heartbeatTicks = RECURRING_HEARTBEAT_TICKS,
                ticksPerMinute = TICKS_PER_MINUTE,
            )
            recurringTasks.install(initial.settings.runtime.healthReportTicks)
            reloadCoordinator = ArcRanksReloadCoordinator(
                loader = loader,
                store = configuration,
                // The gameplay heartbeat keeps cadence carry across health task reconfiguration.
                restartRecurringTasks = {},
                installRecurringTasks = { candidate ->
                    recurringTasks.install(candidate.settings.runtime.healthReportTicks)
                },
                afterCommit = { candidate, diff ->
                    val warnings = mutableListOf<String>()
                    movement.clearAll()
                    analyticsService.invalidateCache()
                    ArcRanksMenuSessions.closeOpen(server)
                    if (diff.liveAreas.any { it in SNAPSHOT_INVALIDATING_AREAS }) {
                        cache.invalidateSnapshots()
                        server.onlinePlayers.forEach { player ->
                            playerService.load(player.uniqueId).exceptionally { null }
                        }
                    }
                    runCatching {
                        runtime.ready(
                            "server" to candidate.settings.serverId,
                            "mode" to candidate.settings.promotionMode.name.lowercase(),
                            "generation" to candidate.generation,
                            "areas" to diff.liveAreas.joinToString(",") { it.name.lowercase() },
                        )
                    }.onFailure { warnings += "runtime ready event: ${it.message ?: it.javaClass.simpleName}" }
                    logger.info(
                        debug.line(
                            "state" to "reloaded",
                            "generation" to candidate.generation,
                            "areas" to diff.liveAreas.joinToString(",") { it.name.lowercase() },
                        ),
                    )
                    warnings
                },
            )
            runtime.ready(
                "server" to initial.settings.serverId,
                "mode" to initial.settings.promotionMode.name.lowercase(),
                "vault" to (economy != null),
                "generation" to initial.generation,
            )
            logger.info(
                debug.line(
                    "state" to "ready",
                    "server" to initial.settings.serverId,
                    "mode" to initial.settings.promotionMode.name.lowercase(),
                ),
            )
        } catch (failure: Throwable) {
            sqlReady.set(false)
            luckPermsReady.set(false)
            runCatching { lifecycle?.health?.markDown(); lifecycle?.emitHealth() }
            logger.log(Level.SEVERE, "ArcRanks failed closed during startup", failure)
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        val timeout = if (::configuration.isInitialized) {
            configuration.current().settings.runtime.shutdownFlushTimeoutSeconds
        } else {
            DEFAULT_SHUTDOWN_TIMEOUT_SECONDS
        }
        runCatching {
            val flushes = listOfNotNull(buffer?.flushAll(), telemetry?.flushAll())
            CompletableFuture.allOf(*flushes.toTypedArray()).get(timeout, TimeUnit.SECONDS)
        }.onFailure { logger.log(Level.WARNING, "Could not flush every ArcRanks progress mutation during shutdown", it) }
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

    private fun reloadPlugin(): ArcRanksReloadResult {
        val menuCandidate = runCatching { menuLayouts.prepare(dataPath) }.getOrElse { failure ->
            return ArcRanksReloadResult.Invalid(
                "config.yml gui.layouts: ${(failure.message ?: failure.javaClass.simpleName).replace('\n', ' ').take(240)}",
            ).also { logger.warning("ArcRanks rejected invalid reload candidate: ${it.reason}") }
        }
        val coreResult = reloadCoordinator.reload()
        if (coreResult is ArcRanksReloadResult.Applied || coreResult is ArcRanksReloadResult.NoChanges) {
            menuLayouts.replace(menuCandidate)
            ArcRanksMenuSessions.closeOpen(server)
        }
        val result = when (coreResult) {
            is ArcRanksReloadResult.Applied,
            is ArcRanksReloadResult.NoChanges,
            -> mergeLoggingReload(coreResult, loggingReloader.reloadIfChanged())
            else -> coreResult
        }
        when (result) {
            is ArcRanksReloadResult.Invalid -> logger.warning("ArcRanks rejected invalid reload candidate: ${result.reason}")
            is ArcRanksReloadResult.RestartRequired -> logger.warning(
                "ArcRanks reload requires restart for: ${result.paths.sorted().joinToString(", ")}",
            )
            is ArcRanksReloadResult.RolledBack -> {
                val failure = result.rollbackFailure
                if (failure == null) logger.warning("ArcRanks reload rolled back: ${result.reason}")
                else {
                    lifecycle?.health?.markDown()
                    logger.severe("ArcRanks reload and timer rollback failed: ${result.reason}; rollback=$failure")
                }
            }
            else -> Unit
        }
        return result
    }

    private fun mergeLoggingReload(
        core: ArcRanksReloadResult,
        logging: ArcRanksLoggingReloadResult,
    ): ArcRanksReloadResult = when (logging) {
        ArcRanksLoggingReloadResult.Unchanged -> core
        ArcRanksLoggingReloadResult.Applied -> when (core) {
            is ArcRanksReloadResult.Applied -> core.copy(liveAreas = core.liveAreas + ArcRanksLiveArea.LOGGING)
            is ArcRanksReloadResult.NoChanges -> ArcRanksReloadResult.Applied(
                generation = core.generation,
                liveAreas = setOf(ArcRanksLiveArea.LOGGING),
            )
            else -> core
        }
        is ArcRanksLoggingReloadResult.Invalid -> when (core) {
            is ArcRanksReloadResult.Applied -> core.copy(warnings = core.warnings + "logging.yml: ${logging.reason}")
            is ArcRanksReloadResult.NoChanges -> ArcRanksReloadResult.Invalid("logging.yml: ${logging.reason}")
            else -> core
        }
    }

    private fun mergeBundledDefaults() {
        listOf("config.yml", "ranks.yml", "perks.yml", "contracts.yml", "weekly-kits.yml", "lang/ru.yml", "lang/en.yml")
            .forEach { resource -> ConfigManager.of(dataPath, resource).mergeMissingFromBundled(resource) }
    }

    private fun installHealth(
        runtime: PaperPluginRuntime,
        vaultAvailable: Boolean,
        auctionAvailable: () -> Boolean,
        eliteMobsAvailable: Boolean,
        progressBuffer: ProgressBuffer,
    ) {
        runtime.registerHealth("progression") {
            val rejected = progressBuffer.rejectedCount()
            RuntimeHealthContribution(
                state = if (sqlReady.get() && luckPermsReady.get() && rejected == 0L) {
                    RuntimeHealthState.UP
                } else {
                    RuntimeHealthState.DOWN
                },
                recoveryBacklog = progressBuffer.pendingCount(),
                schemas = mapOf(
                    "progress" to 1,
                    "promotion" to 2,
                    "external_events" to 1,
                    "analytics" to 3,
                    "perks" to 4,
                    "contracts" to 5,
                    "weekly_kits" to 6,
                ),
                dependencies = mapOf(
                    "mysql" to sqlReady.get(),
                    "luckperms" to luckPermsReady.get(),
                    "vault_trade_path" to vaultAvailable,
                    "auction_trade_source" to auctionAvailable(),
                    "elitemobs_dungeon_source" to eliteMobsAvailable,
                    "progress_buffer_no_rejections" to (rejected == 0L),
                ),
            )
        }
    }

    private fun installPlaceholders(cache: RankSnapshotCache) {
        if (!server.pluginManager.isPluginEnabled("PlaceholderAPI")) return
        placeholders = ArcRanksPlaceholderExpansion(
            this,
            { configuration.current().ranks.catalog },
            { configuration.current().locale },
            { configuration.current().ranks.mastery },
            cache,
        ).also { require(it.register()) { "Could not register ArcRanks PlaceholderAPI expansion" } }
    }

    private fun installLogging(settings: ArcRanksSettings) {
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
        loggingReloader = ArcRanksLoggingReloader(path, ConfigManager::reloadAll)
    }

    private fun saveResourceIfMissing(path: String) {
        if (!Files.isRegularFile(dataPath.resolve(path))) saveResource(path, false)
    }

    private companion object {
        const val DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 5L
        const val RECURRING_HEARTBEAT_TICKS = 20L
        const val TICKS_PER_MINUTE = 1_200L
        val SNAPSHOT_INVALIDATING_AREAS = setOf(
            ArcRanksLiveArea.RANK_RULES,
            ArcRanksLiveArea.MASTERY,
        )
    }
}

private class ArcRanksRecurringTasks(
    private val runtime: PaperPluginRuntime,
    private val callbackTasks: LifecycleTaskScope,
    private val settings: () -> ArcRanksSettings,
    private val sampler: PeriodicProgressSampler,
    private val progressBuffer: ProgressBuffer,
    private val productTelemetry: ProductTelemetry,
    private val heartbeatTicks: Long,
    private val ticksPerMinute: Long,
) {
    private val sampling = DynamicTickCadence(heartbeatTicks)
    private val progressFlush = DynamicTickCadence(heartbeatTicks)
    private val analyticsFlush = DynamicTickCadence(heartbeatTicks)
    private var installedHealthPeriodTicks: Long? = null

    fun install(healthPeriodTicks: Long) {
        if (installedHealthPeriodTicks == healthPeriodTicks) return
        try {
            if (installedHealthPeriodTicks != null) {
                runtime.reload()
                installedHealthPeriodTicks = null
            }
            checkNotNull(runtime.tasks.runTimer(heartbeatTicks, heartbeatTicks, ::heartbeat)) {
                "Could not schedule ArcRanks recurring heartbeat"
            }
            runtime.reportHealthEvery(healthPeriodTicks)
            installedHealthPeriodTicks = healthPeriodTicks
        } catch (failure: Throwable) {
            if (installedHealthPeriodTicks == null) {
                runCatching { runtime.reload() }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
    }

    private fun heartbeat() {
        val current = settings()
        val sampledTicks = sampling.advance(current.sampleTicks)
        if (sampledTicks > 0L) sampler.sampleMinutes(sampledTicks / ticksPerMinute)
        if (progressFlush.advance(current.flushTicks) > 0L) {
            callbackTasks.runAsync { progressBuffer.flushAll() }
        }
        if (analyticsFlush.advance(current.analytics.flushTicks) > 0L) {
            callbackTasks.runAsync { productTelemetry.flush() }
        }
    }
}
