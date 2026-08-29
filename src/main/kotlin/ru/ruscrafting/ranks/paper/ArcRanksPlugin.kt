package ru.ruscrafting.ranks.paper

import net.kyori.adventure.title.Title
import net.luckperms.api.LuckPerms
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
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
import ru.arc.metrics.core.ArcMetricsRuntime
import ru.arc.metrics.core.MetricPoint
import ru.arc.metrics.core.MetricsConfig
import ru.arc.metrics.core.MetricsIdentity
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.observability.StructuredDebugLine
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.sql.SqlRuntime
import ru.ruscrafting.ranks.api.RankProgressApi
import ru.ruscrafting.ranks.api.RepositoryRankProgressApi
import ru.ruscrafting.ranks.command.RankCommand
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.config.LoadedRankCatalog
import ru.ruscrafting.ranks.config.RankCatalogLoader
import ru.ruscrafting.ranks.domain.PathAvailability
import ru.ruscrafting.ranks.domain.RankEvaluator
import ru.ruscrafting.ranks.domain.RankId
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.gui.RankPassportMenu
import ru.ruscrafting.ranks.placeholder.ArcRanksPlaceholderExpansion
import ru.ruscrafting.ranks.progress.MovementAccumulator
import ru.ruscrafting.ranks.progress.PeriodicProgressSampler
import ru.ruscrafting.ranks.progress.ProgressBuffer
import ru.ruscrafting.ranks.progress.RankProgressListener
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level

class ArcRanksPlugin : JavaPlugin() {
    private lateinit var settings: ArcRanksSettings
    private lateinit var loadedCatalog: LoadedRankCatalog
    private lateinit var locale: RankLocale
    private var lifecycle: PaperPluginRuntime? = null
    private var buffer: ProgressBuffer? = null
    private var placeholders: ArcRanksPlaceholderExpansion? = null
    private val sqlReady = AtomicBoolean(false)
    private val luckPermsReady = AtomicBoolean(false)
    private val cachedPlayers = AtomicReference<RankSnapshotCache>()
    private val debug = StructuredDebugLine("ARCRANKS_EVENT")

    override fun onEnable() {
        saveDefaultConfig()
        saveResourceIfMissing("ranks.yml")
        saveResourceIfMissing("lang/ru.yml")
        saveResourceIfMissing("lang/en.yml")
        PaperArcRuntime.installScheduling(this)
        try {
            mergeBundledDefaults()
            settings = ArcRanksSettings.load(dataPath)
            loadedCatalog = RankCatalogLoader(ConfigManager.of(dataPath, "ranks.yml")).loadWithMastery()
            locale = RankLocale(dataPath, { settings.defaultLocale }, { settings.useClientLocale })
            locale.validate(loadedCatalog.catalog)
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
            val playerService = RankPlayerService(
                rankState,
                progress,
                evaluator,
                loadedCatalog.mastery,
                availability,
                cache,
            )
            val api = RepositoryRankProgressApi(progress)
            val promotionService = PromotionService(
                loadedCatalog.catalog,
                evaluator,
                progress,
                progressBuffer,
                rankState,
                promotionRepository,
                availability,
                ::celebrate,
            )
            val menu = RankPassportMenu(
                { settings },
                { loadedCatalog.catalog },
                { locale },
                playerService,
                promotionService,
                runtime.tasks,
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
                runtime.tasks,
                ::reloadPlugin,
            )
            requireNotNull(getCommand("rank")).apply { setExecutor(command); tabCompleter = command }
            requireNotNull(getCommand("rankup")).apply { setExecutor(command); tabCompleter = command }
            server.pluginManager.registerEvents(menu, this)
            server.pluginManager.registerEvents(PlayerSnapshotListener(playerService, cache), this)
            server.pluginManager.registerEvents(
                RankProgressListener(progressBuffer, MovementAccumulator(settings.maximumMovementStepBlocks)),
                this,
            )
            PeriodicProgressSampler(server, { settings }, progressBuffer, economy, runtime.tasks).start()

            server.servicesManager.register(RankProgressApi::class.java, api, this, ServicePriority.Normal)
            installPlaceholders(cache)
            installMetrics(runtime, cache)
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
        runCatching { buffer?.flushAll()?.get(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            .onFailure { logger.log(Level.WARNING, "Could not flush every ArcRanks progress mutation during shutdown", it) }
        runCatching { placeholders?.unregister() }
        server.servicesManager.unregisterAll(this)
        runCatching { lifecycle?.close() }
        placeholders = null
        buffer = null
        lifecycle = null
        sqlReady.set(false)
        luckPermsReady.set(false)
        cachedPlayers.set(null)
        ConfigManager.clear()
        Tasks.reset()
    }

    private fun mergeBundledDefaults() {
        listOf("config.yml", "ranks.yml", "lang/ru.yml", "lang/en.yml").forEach { resource ->
            ConfigManager.of(dataPath, resource).mergeMissingFromBundled(resource)
        }
    }

    private fun reloadPlugin(): Result<Unit> = runCatching {
        val previous = settings
        ConfigManager.reloadAll()
        val candidateSettings = ArcRanksSettings.load(dataPath)
        val candidateCatalog = RankCatalogLoader(ConfigManager.of(dataPath, "ranks.yml")).loadWithMastery()
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
        require(candidateCatalog == loadedCatalog) { "rank thresholds and LuckPerms groups require a restart" }
        val candidateLocale = RankLocale(dataPath, { candidateSettings.defaultLocale }, { candidateSettings.useClientLocale })
        candidateLocale.validate(candidateCatalog.catalog)
        settings = candidateSettings
        locale = candidateLocale
        lifecycle?.reload()
        lifecycle?.ready("server" to settings.serverId, "mode" to settings.promotionMode.name.lowercase())
    }

    private fun celebrate(playerId: UUID, rankId: RankId) {
        lifecycle?.tasks?.runSync {
            val player = Bukkit.getPlayer(playerId) ?: return@runSync
            val rank = loadedCatalog.catalog.require(rankId)
            player.showTitle(
                Title.title(
                    locale.render("celebration.title", player, mapOf("rank" to locale.render(rank.displayNameKey, player))),
                    locale.render("celebration.subtitle", player),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofMillis(700)),
                ),
            )
            player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
            logger.info(debug.line("event" to "promotion", "player" to playerId, "rank" to rankId.value))
        }
    }

    private fun installHealth(runtime: PaperPluginRuntime, vaultAvailable: Boolean) {
        runtime.registerHealth("progression") {
            RuntimeHealthContribution(
                state = if (sqlReady.get() && luckPermsReady.get()) RuntimeHealthState.UP else RuntimeHealthState.DOWN,
                schemas = mapOf("progress" to 1, "promotion" to 2, "external_events" to 1),
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

    private fun installMetrics(runtime: PaperPluginRuntime, cache: RankSnapshotCache) {
        val path = ConfigManager.moduleYamlPath(dataPath, "metrics.yml")
        val existed = Files.isRegularFile(path)
        val config = ConfigManager.ofModule(dataPath, "metrics.yml")
        if (!existed) {
            config.setInt("bind-port", METRICS_PORT)
            config.saveStrict()
        }
        val metrics = ArcMetricsRuntime(
            config = MetricsConfig(config),
            identity = MetricsIdentity("ArcRanks", "paper", settings.serverId, pluginMeta.version),
            dataPath = dataPath,
        )
        try {
            metrics.start()
        } catch (failure: Throwable) {
            runCatching(metrics::close)
            logger.log(Level.WARNING, "ArcRanks metrics failed to start; gameplay remains available", failure)
            return
        }
        runtime.own(metrics)
        runtime.tasks.runTimerAsync(0L, METRICS_SAMPLE_TICKS) {
            metrics.recordSnapshot("ranks", "product") {
                listOf(
                    MetricPoint("arc_ranks_buffer_entries", "Buffered rank progress mutations", buffer?.pendingCount()?.toDouble() ?: 0.0),
                    MetricPoint("arc_ranks_cached_players", "Players with non-blocking rank snapshots", cache.size().toDouble()),
                    MetricPoint("arc_ranks_sql_ready", "Shared MySQL rank storage readiness", if (sqlReady.get()) 1.0 else 0.0),
                )
            }
        }
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
        const val METRICS_SAMPLE_TICKS = 100L
        const val METRICS_PORT = 9953
    }
}
