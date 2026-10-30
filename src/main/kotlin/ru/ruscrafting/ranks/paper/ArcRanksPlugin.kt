package ru.ruscrafting.ranks.paper

import net.luckperms.api.LuckPerms
import net.milkbowl.vault.economy.Economy
import org.bukkit.entity.Player
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.ConfigManager
import ru.arc.core.whenCompleteSync
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
import ru.arc.paper.runtime.PaperPluginHotload
import ru.arc.paper.sql.PaperSqlServices
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
import ru.ruscrafting.ranks.config.locationsFor
import ru.ruscrafting.ranks.contract.ContractOfferConfiguration
import ru.ruscrafting.ranks.contract.ContractOfferGenerator
import ru.ruscrafting.ranks.contract.ContractService
import ru.ruscrafting.ranks.contract.ContractRewardDeliveryService
import ru.ruscrafting.ranks.contract.PaperContractRewardProvider
import ru.ruscrafting.ranks.contract.MySqlContractRepository
import ru.ruscrafting.ranks.domain.PathAvailability
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.dialog.RankDialogController
import ru.ruscrafting.ranks.gui.AnalyticsMenu
import ru.ruscrafting.ranks.gui.ArcRanksMenuSessions
import ru.ruscrafting.ranks.gui.ArcRanksMenuLayouts
import ru.ruscrafting.ranks.gui.DailyQuestMenu
import ru.ruscrafting.ranks.gui.ContractMenu
import ru.ruscrafting.ranks.gui.PerkMenu
import ru.ruscrafting.ranks.gui.RankPassportMenu
import ru.ruscrafting.ranks.gui.WeeklyKitMenu
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
import ru.ruscrafting.ranks.service.RankReminderService
import ru.ruscrafting.ranks.service.RankSnapshotCache
import ru.ruscrafting.ranks.storage.MySqlProgressRepository
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.display.PaperPacketDisplays
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level

open class ArcRanksPlugin : JavaPlugin() {
    private lateinit var configuration: ArcRanksConfigStore
    private lateinit var reloadCoordinator: ArcRanksReloadCoordinator
    private lateinit var loggingReloader: ArcRanksLoggingReloader
    private lateinit var menuLayouts: ArcRanksMenuLayouts
    private var lifecycle: PaperPluginRuntime? = null
    private var buffer: ProgressBuffer? = null
    private var questAvailabilityClient: ru.ruscrafting.ranks.progress.QuestAvailability? = null
    private var placeholders: ArcRanksPlaceholderExpansion? = null
    private var telemetry: ProductTelemetry? = null
    private val sqlReady = AtomicBoolean(false)
    private val luckPermsReady = AtomicBoolean(false)
    private val cachedPlayers = AtomicReference<RankSnapshotCache>()
    private val debug = StructuredDebugLine("ARCRANKS_EVENT")

    private lateinit var storage: ru.arc.core.BoundedAsyncExecutor
    private lateinit var nativeWork: ru.arc.core.BudgetedTaskQueue
    @Volatile private var sqlRuntime: SqlRuntime? = null
    private var reloadInFlight: CompletableFuture<ArcRanksReloadResult>? = null
    private val storageResources = java.util.concurrent.ConcurrentLinkedDeque<AutoCloseable>()
    private var samplingStorage: ru.arc.core.BoundedAsyncExecutor? = null
    private var sqlBinding: AutoCloseable? = null
    private var celebrationDisplays: PaperPacketDisplays? = null
    private var celebrationDisplayDrain = CompletableFuture.completedFuture<Void>(null)
    private var shutdownDrain = CompletableFuture.completedFuture(Unit)
    internal fun drained(): CompletableFuture<Unit> =
        CompletableFuture.allOf(shutdownDrain, celebrationDisplayDrain).thenApply { Unit }

    private val hotload = PaperPluginHotload(this) {
        stopRuntime()
        mapOf("storage" to shutdownDrain, "celebration-displays" to celebrationDisplayDrain)
    }

    override fun onEnable() {
        hotload.beginGeneration()
        celebrationDisplayDrain = CompletableFuture.completedFuture(null)
        hotload.register()
        val runtime = PaperPluginRuntime(this, "arc-ranks", ru.arc.core.BukkitTaskScheduler(this)).also {
            lifecycle = it
            it.start("version" to pluginMeta.version)
        }
        try {
            ru.ruscrafting.ranks.analytics.ExternalArcProductTelemetryBridge.install()
            PaperArcRuntime.installScheduling(this)
            storage = ru.arc.core.BoundedAsyncExecutor("arc-ranks-storage")
            shutdownDrain = CompletableFuture()
            sqlBinding = PaperSqlServices.installConsumer(this, shutdownDrain)
            val prepared = storage.submit {
                saveDefaultConfig()
                listOf("ranks.yml", "perks.yml", "contracts.yml", "daily-quests.yml", "weekly-kits.yml", "lang/ru.yml", "lang/en.yml")
                    .forEach(::saveResourceIfMissing)
                mergeBundledDefaults()
                val loader = ArcRanksConfigLoader(dataPath)
                val initial = loader.load(1)
                val layouts = ArcRanksMenuLayouts(dataPath)
                installLogging(initial.settings)
                val sql = SqlRuntime.create(initial.settings.sql, "arc-ranks-${initial.settings.serverId}").also { sqlRuntime = it }
                MySqlProgressRepository(sql).initialize().get(initial.settings.runtime.startupTimeoutSeconds, TimeUnit.SECONDS)
                Triple(loader, initial, layouts)
            }.get()
            finishEnable(runtime, prepared.first, prepared.second, prepared.third, requireNotNull(sqlRuntime))
        } catch (failure: Throwable) { failStartup(failure) }
    }

    private fun finishEnable(runtime: PaperPluginRuntime, loader: ArcRanksConfigLoader,
                             initial: ru.ruscrafting.ranks.config.ArcRanksConfigSnapshot,
                             layouts: ArcRanksMenuLayouts, sql: SqlRuntime) {
            sqlRuntime = sql
            configuration = ArcRanksConfigStore(initial)
            menuLayouts = layouts
            val callbackTasks = runtime.own(LifecycleTaskScope())
            nativeWork = runtime.own(ru.arc.core.BudgetedTaskQueue(callbackTasks))
            val dialogRuntime = runtime.own(PaperDialogRuntime(this))
            val luckPerms = requireNotNull(server.servicesManager.getRegistration(LuckPerms::class.java)?.provider) {
                "LuckPerms service is unavailable"
            }
            luckPermsReady.set(true)
            // Rank id/group/order topology is restart-only, so this gateway remains valid for the process lifetime.
            val rankState = LuckPermsRankStateGateway(luckPerms, initial.ranks.catalog)
            val socialQuests = runtime.own(ru.ruscrafting.ranks.progress.SocialQuestIntegration(this, callbackTasks, storage, storageResources::add))
            val questAvailability = runtime.own(ru.ruscrafting.ranks.progress.QuestAvailability(this, callbackTasks, socialQuests))
                .also { questAvailabilityClient = it }
            val dailyQuests = ru.ruscrafting.ranks.quest.MySqlDailyQuestRepository(
                sql, catalog = { configuration.current().dailyQuests },
                rank = { id -> rankState.load(id).thenApply { state ->
                    require(state is ru.ruscrafting.ranks.rankstate.RankState.Exact) { "Cannot assign quests without an authoritative rank" }
                    state.rankId.value
                } },
                availability = questAvailability::forAssignment,
            )
            val clanQuests = ru.ruscrafting.ranks.clan.ClanQuestRepository(sql,
                catalog = { configuration.current().clanQuests })
            val clanIntegration = ru.ruscrafting.ranks.progress.ClanQuestIntegration(this, callbackTasks, clanQuests)
            server.servicesManager.register(ru.ruscrafting.ranks.api.ClanArenaQuestApi::class.java,
                ru.ruscrafting.ranks.api.ClanArenaQuestApi { id, groups, at ->
                    val writes = groups.map { group -> clanIntegration.recordCapturedGroup(
                        "arcduels_arena", id, group.entries.map { it.key to it.value }, "clan.arena_match", at,
                    ) }
                    CompletableFuture.allOf(*writes.toTypedArray()).thenApply { writes.sumOf { it.join() } }
                }, this, ServicePriority.Normal)
            val progress = MySqlProgressRepository(sql, dailyQuests)
            val promotionRepository = MySqlPromotionRepository(sql)
            sqlReady.set(true)
            val analyticsRepository = MySqlAnalyticsRepository(sql)
            val perkRepository = MySqlPerkSelectionRepository(sql)
            val contractRepository = MySqlContractRepository(sql)
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

            val economy = server.servicesManager.getRegistration(Economy::class.java)?.provider
            val auctionAvailable = AtomicBoolean(false)
            val availability = {
                val collection = configuration.current().settings.collection
                val tradeAvailable = economy != null || collection.villagerTrade.enabled ||
                    (collection.auctionDeal.enabled && auctionAvailable.get())
                PathAvailability(if (tradeAvailable) emptySet() else setOf(SpecializationPath.TRADE))
            }
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
            var deliverDailyRewards: (java.util.UUID) -> Unit = {}
            val progressBuffer = ProgressBuffer(
                maximumEntriesProvider = { configuration.current().settings.maximumBufferEntries },
                writer = { id, mutations -> progress.applyMutations(id, mutations).also { future ->
                    future.whenCompleteSync(callbackTasks) { _, failure ->
                        if (failure == null) {
                            if (
                                server.getPlayer(id)?.isOnline == true &&
                                mutations.any { it.metric == ProgressMetric.ACTIVE_MINUTES }
                            ) {
                                playerService.refreshAfterActiveTimePersistence(id).whenComplete { _, refreshFailure ->
                                    if (refreshFailure != null) {
                                        logger.log(
                                            java.util.logging.Level.WARNING,
                                            "Could not refresh cached ArcRanks active-time progress for $id",
                                            refreshFailure,
                                        )
                                    }
                                }
                            }
                            deliverDailyRewards(id)
                        }
                    }
                } },
            ).also { buffer = it }
            val eligiblePerks: (java.util.UUID) -> Collection<ru.ruscrafting.ranks.perk.PerkId> = { playerId ->
                val source = cache.perkProgress(playerId)
                val snapshot = configuration.current()
                if (source == null || !snapshot.settings.features.perks) emptySet()
                else snapshot.perks.eligibleForProgress(source.activePerks, source.profile, snapshot.ranks.mastery)
            }
            val progressModifier = PerkProgressModifier(
                buffer = progressBuffer,
                catalogProvider = { configuration.current().perks },
                fractionalBonus = FractionalProgressBonus(),
                telemetry = productTelemetry,
                selectedPerks = eligiblePerks,
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
                allowNewContracts = { false },
            )
            val redisEconomyClassLoader = server.pluginManager.getPlugin("RedisEconomy")?.javaClass?.classLoader
            val contractRewardProvider = PaperContractRewardProvider.create(
                server,
                economy,
                redisEconomyClassLoader,
                initial.contracts.bonusRewards,
            )
            val contractRewardLedger =
                MySqlOneTimeUseLedger.attach(
                    sql,
                    "arc-ranks-${initial.settings.serverId}",
                    MySqlOneTimeUsePartition("contract_reward"),
                ).also(storageResources::add)
            runtime.own(AutoCloseable(contractRewardLedger::close))
            val contractRewardDelivery = ContractRewardDeliveryService(
                contractRepository,
                contractRewardLedger,
                contractRewardProvider,
                callbackTasks,
                logger,
            )
            val storyService = { server.servicesManager.load(ru.ruscrafting.onboarding.api.StoryQuestService::class.java) }
            val storyTracked: (java.util.UUID) -> Boolean = { id ->
                val provider = storyService()
                provider?.ready(id) == true && provider.quests(id).any { it.tracked() }
            }
            val questTracker = runtime.own(ru.ruscrafting.ranks.quest.QuestTracker(
                this, callbackTasks, { configuration.current().dailyQuests }, { configuration.current().locale },
                ru.ruscrafting.ranks.quest.MySqlQuestTrackingRepository(sql), dailyQuests::board,
                storyTracked = storyTracked,
            ))
            questTracker.install()
            lateinit var celebration: PromotionCelebration
            val dailyRewardDelivery = ru.ruscrafting.ranks.reward.RankRewardDeliveryService(
                dailyQuests, contractRewardLedger, contractRewardProvider, callbackTasks, logger,
                onGranted = { player, reward ->
                    questTracker.refresh(player.uniqueId)
                    player.sendMessage(ru.ruscrafting.ranks.quest.QuestCompletionMessage.render(
                        configuration.current().locale, player, reward,
                    ))
                    reward.questSummary?.let { celebration.celebrateQuest(player, it) }
                },
            )
            val rankReminders = runtime.own(RankReminderService(
                plugin = this,
                tasks = callbackTasks,
                players = playerService,
                flush = progressBuffer::flush,
                settings = { configuration.current().settings.reminders },
                promotionMode = { configuration.current().settings.promotionMode },
                locale = { configuration.current().locale },
                perksEnabled = { configuration.current().settings.features.perks },
                perks = { configuration.current().perks },
            )).also { it.install() }
            deliverDailyRewards = { id ->
                server.getPlayer(id)?.let(dailyRewardDelivery::deliverPending)
                questTracker.refresh(id)
            }
            val questApi = object : ru.ruscrafting.ranks.api.RankQuestApi {
                override fun record(source: String, eventId: String, id: java.util.UUID, objective: String, amount: Long) =
                    progressBuffer.flush(id).thenCompose { progress.recordQuestEvent(source, eventId, id, objective, amount) }.also { future ->
                        future.whenCompleteSync(callbackTasks) { result, failure ->
                            if (failure == null && result == ru.ruscrafting.ranks.storage.ExternalProgressResult.APPLIED) {
                                cache.invalidateSnapshot(id)
                                deliverDailyRewards(id)
                            }
                        }
                    }

                override fun itemRewardSources(): ru.ruscrafting.ranks.api.RankQuestApi.ItemRewardSourceSnapshot {
                    val current = configuration.current()
                    return ru.ruscrafting.ranks.quest.dailyQuestItemRewardSources(current.dailyQuests) { quest ->
                        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                            .serialize(current.locale.render("daily.${quest.textId}.name"))
                    }
                }
            }
            server.servicesManager.register(ru.ruscrafting.ranks.api.RankQuestApi::class.java, questApi, this, ServicePriority.Normal)
            ru.ruscrafting.ranks.progress.ServerQuestIntegration(this, questApi, callbackTasks).install()
            questAvailability.votesEnabled = ru.ruscrafting.ranks.progress.VoteQuestIntegration(this, questApi, callbackTasks).install()
            questAvailability.install(dailyQuests, questApi)
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
                masteryThresholds = { configuration.current().ranks.mastery },
            )
            val analyticsMenu = AnalyticsMenu(
                settings, locale, analyticsService, healthSnapshot, callbackTasks, productTelemetry,
                back = { player -> menu.open(player) },
                layouts = menuLayouts,
                configGeneration = generation,
            )
            val weeklyKitMenu = WeeklyKitMenu(locale)
            val displayRuntime = PaperPacketDisplays(this, "rank-celebrations", nativeWork)
                .also { celebrationDisplays = it }
            celebration = runtime.own(PromotionCelebration(
                this,
                server,
                configuration::current,
                callbackTasks,
                displayRuntime,
            ) { playerId, event, scene ->
                logger.info(debug.line("event" to "celebration", "kind" to event, "player" to playerId, "scene" to scene))
            })
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
            val questDiagnostics = ru.ruscrafting.ranks.quest.QuestProgressDiagnostics()
            val adminProgressService = AdminProgressService(api)
            val loadQuestBoard: (java.util.UUID) -> CompletableFuture<ru.ruscrafting.ranks.quest.DailyQuestBoard> = { id ->
                progressBuffer.flush(id).thenCompose { progress.dailyQuests.board(id) }
                    .thenApply { board ->
                        board.quests.filter { state ->
                            state.quest.objective.startsWith("fish") || state.quest.plan?.steps?.any { it.objective.startsWith("fish") } == true
                        }.forEach { state ->
                            logger.info(debug.line(
                                "event" to "fishing_quest_snapshot",
                                "player" to id,
                                "day" to board.day,
                                "quest" to state.quest.id,
                                "completed" to state.completed,
                                "value" to state.value,
                                "target" to state.quest.target,
                                "reward_state" to state.rewardState,
                                "steps" to state.quest.plan?.steps?.mapIndexed { index, step ->
                                    "${step.objective}:${state.stepValues[index]}/${step.target}"
                                }?.joinToString(","),
                            ))
                        }
                        board
                    }
            }
            val trackQuest: (Player, ru.ruscrafting.ranks.quest.DailyQuestBoard, String) -> CompletableFuture<Unit> = { player, _, quest -> progressBuffer.flush(player.uniqueId).thenCompose { dailyQuests.board(player.uniqueId) }
                    .thenCompose { board ->
                        val result = CompletableFuture<Unit>()
                        val scheduled = callbackTasks.runSync {
                            val provider = storyService()
                            val resumingDaily = provider != null && (!provider.ready(player.uniqueId) || storyTracked(player.uniqueId))
                            val clearStory = runCatching {
                                provider?.track(player.uniqueId, null)?.toCompletableFuture()
                                    ?: CompletableFuture.completedFuture(null)
                            }.getOrElse { CompletableFuture.failedFuture(it) }
                            clearStory.whenCompleteSync(callbackTasks) { _, failure ->
                                if (failure != null) result.completeExceptionally(failure)
                                else questTracker.toggle(player, board, quest, forceSelect = resumingDaily).whenComplete { _, error ->
                                    if (error == null) result.complete(Unit) else result.completeExceptionally(error)
                                }
                            }
                        }
                        if (scheduled == null) result.cancel(false)
                        result
                    } }
            val replaceQuest: (java.util.UUID, java.time.LocalDate, String) -> CompletableFuture<ru.ruscrafting.ranks.quest.QuestReplaceResult> = { id, day, quest -> progressBuffer.flush(id).thenCompose { dailyQuests.replace(id, day, quest) }.also { future ->
                    future.whenCompleteSync(callbackTasks) { _, failure -> if (failure == null) questTracker.refresh(id) }
                } }
            val dialogCloseOnEscape: (Player) -> Boolean = { player ->
                luckPerms.getPlayerAdapter(Player::class.java).getUser(player)
                    .cachedData.metaData.getMetaValue("arc-menu-escape") == "close"
            }
            val clanDialogs = ru.ruscrafting.ranks.dialog.ClanQuestDialogController(
                runtime = dialogRuntime, load = clanIntegration::board, tasks = callbackTasks,
                locale = locale, generation = generation, closeOnEscape = dialogCloseOnEscape,
                openDungeon = { player ->
                    travelToDungeonEntrance(player, "dungeon.complete", configuration.current().settings.dungeonQuestDestinations)
                },
            )
            server.servicesManager.register(ru.ruscrafting.ranks.api.ClanQuestMenuApi::class.java,
                ru.ruscrafting.ranks.api.ClanQuestMenuApi { id ->
                    check(server.isPrimaryThread) { "Clan menus require the gameplay thread" }
                    server.getPlayer(id)?.takeIf { it.isOnline && it.hasPermission("arcranks.use") }?.let {
                        clanDialogs.open(it)
                        true
                    } ?: false
                }, this, ServicePriority.Normal)
            val dailyQuestMenu = DailyQuestMenu(
                settings, locale,
                load = loadQuestBoard,
                openClanQuests = clanDialogs::open,
                loadClanQuests = clanIntegration::board,
                selected = questTracker::selected,
                diagnose = { id, state -> questDiagnostics.latest(id, ru.ruscrafting.ranks.quest.QuestTrackingView.of(state).objective) },
                track = trackQuest,
                trackingEnabled = { configuration.current().dailyQuests.trackingEnabled },
                replace = replaceQuest,
                tasks = callbackTasks, layouts = menuLayouts, generation = generation,
                back = { player -> menu.open(player) },
            )
            menu = RankPassportMenu(
                settings = settings,
                catalog = { configuration.current().ranks.catalog },
                locale = locale,
                players = playerService,
                promotions = promotionService,
                adminProgress = adminProgressService,
                tasks = callbackTasks,
                telemetry = productTelemetry,
                openDailyQuests = dailyQuestMenu::open,
                openContracts = contractMenu::open,
                openPerks = perkMenu::open,
                openAnalytics = analyticsMenu::open,
                layouts = menuLayouts,
                configGeneration = generation,
            )
            lateinit var dialogs: RankDialogController
            val questDialogs = ru.ruscrafting.ranks.dialog.DailyQuestDialogController(
                runtime = dialogRuntime,
                load = loadQuestBoard,
                openClanQuests = clanDialogs::open,
                loadClanQuests = clanIntegration::board,
                selected = questTracker::selected,
                track = trackQuest,
                diagnose = { id, state -> questDiagnostics.latest(id, ru.ruscrafting.ranks.quest.QuestTrackingView.of(state).objective) },
                trackingEnabled = { configuration.current().dailyQuests.trackingEnabled },
                replace = replaceQuest,
                generation = generation,
                tasks = callbackTasks,
                locale = locale,
                openRank = { player -> dialogs.open(player) },
                openDungeon = { player, objective ->
                    travelToDungeonEntrance(player, objective, configuration.current().settings.dungeonQuestDestinations)
                },
                travelLocations = { objective -> configuration.current().settings.questTravelLocations.locationsFor(objective) },
                travel = { player, objective, location ->
                    travelToQuestLocation(player, objective, location, configuration.current().settings.questTravelLocations)
                },
                closeOnEscape = dialogCloseOnEscape,
            )
            val questJournal = ru.ruscrafting.ranks.dialog.QuestJournalDialogController(
                runtime = dialogRuntime, stories = storyService,
                openDaily = questDialogs::open, openClan = clanDialogs::open,
                dailySummary = questTracker::summary, refreshTracking = questTracker::refresh,
                tasks = callbackTasks, locale = locale, logger = logger,
                closeOnEscape = dialogCloseOnEscape, generation = generation,
            )
            dialogs = RankDialogController(
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
                analytics = analyticsService,
                analyticsHealth = healthSnapshot,
                tasks = callbackTasks,
                openHelp = { player -> if (!player.performCommand("menu")) menu.open(player) },
                openDailyQuests = questJournal::open,
                loadQuestSummary = dailyQuests::existingBoard,
                masteryThresholds = { configuration.current().ranks.mastery },
                closeOnEscape = dialogCloseOnEscape,
                diagnostic = { line -> logger.info(line) },
            )
            val questDisplaySettings = ru.ruscrafting.ranks.dialog.QuestDisplaySettingsDialogController(
                runtime = dialogRuntime,
                mode = questTracker::displayMode,
                available = questTracker::displaySettingsAvailable,
                select = questTracker::selectDisplay,
                tasks = callbackTasks,
                locale = locale,
                closeOnEscape = dialogCloseOnEscape,
                generation = generation,
            )
            server.pluginManager.registerEvents(questDisplaySettings, this)
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
                analyticsMenu,
                analyticsService,
                healthSnapshot,
                callbackTasks,
                ::reloadPlugin,
                dialogs,
                openDailyQuests = questDialogs::beginFlowAndOpen,
                openQuestJournal = questJournal::beginFlowAndOpen,
                openClanQuests = clanDialogs::beginFlowAndOpen,
                openQuestDisplaySettings = questDisplaySettings::beginFlowAndOpen,
                questAdmin = ru.ruscrafting.ranks.command.QuestAdminCommand(
                    server, locale, callbackTasks, dailyQuests, progressBuffer::flush, questTracker::refresh, logger,
                ),
                celebrationScenes = celebration::sceneIds,
                playCelebration = celebration::preview,
                playAllCelebrations = celebration::previewAll,
            )
            requireNotNull(getCommand("rank")).apply { setExecutor(command); tabCompleter = command }
            requireNotNull(getCommand("quests")).apply { setExecutor(command); tabCompleter = command }
            requireNotNull(getCommand("perks")).apply { setExecutor(command); tabCompleter = command }
            requireNotNull(getCommand("rankup")).apply { setExecutor(command); tabCompleter = command }
            server.pluginManager.registerEvents(dailyRewardDelivery, this)
            server.pluginManager.registerEvents(dailyQuestMenu, this)
            server.pluginManager.registerEvents(menu, this)
            server.pluginManager.registerEvents(dialogs, this)
            server.pluginManager.registerEvents(questDialogs, this)
            server.pluginManager.registerEvents(questJournal, this)
            server.pluginManager.registerEvents(clanDialogs, this)
            server.pluginManager.registerEvents(contractMenu, this)
            server.pluginManager.registerEvents(contractRewardDelivery, this)
            server.pluginManager.registerEvents(perkMenu, this)
            server.pluginManager.registerEvents(
                ru.ruscrafting.ranks.perk.GameplayPerkListener(settings, { configuration.current().perks }, eligiblePerks), this,
            )
            server.pluginManager.registerEvents(
                ru.ruscrafting.ranks.perk.UtilityPerkListener(
                    settings, { configuration.current().perks }, eligiblePerks, callbackTasks, generation, locale,
                ), this,
            )
            val perkPlantingAccess = ru.ruscrafting.ranks.perk.PerkPlantingAccess(this)
            server.pluginManager.registerEvents(
                ru.ruscrafting.ranks.perk.HarvestPerkListener(
                    settings, { configuration.current().perks }, eligiblePerks, callbackTasks, generation,
                    perkPlantingAccess::allows,
                ), this,
            )
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
                RankProgressListener(progressBuffer, movement, progressModifier, settings, callbackTasks, building = buildingProgress, diagnostics = questDiagnostics,
                    fishDiagnostic = { line -> logger.info(line) }),
                this,
            )
            val eliteMobsAvailable = server.pluginManager.isPluginEnabled("EliteMobs")
            if (eliteMobsAvailable) {
                server.pluginManager.registerEvents(
                    ru.ruscrafting.ranks.progress.ClanEliteCombatListener(clanIntegration, settings, logger), this,
                )
                server.pluginManager.registerEvents(
                    EliteMobsProgressListener(api, settings, logger, cache::invalidateSnapshot, questApi,
                        clanIntegration::membership, { source, id, participants, objective, instant ->
                            clanIntegration.recordCapturedGroup(source, id, participants, objective, instant)
                        }),
                    this,
                )
            }
            auctionAvailable.set(AuctionProgressIntegration(this, api, settings, cache::invalidateSnapshot).install())
            BuilderProgressIntegration(this, api, settings, buildingProgress, callbackTasks, cache::invalidateSnapshot, questApi).install()
            val samplingWorker = runtime.own(ru.arc.core.BoundedAsyncExecutor("arc-ranks-sampling"))
                .also { samplingStorage = it }
            val sampler = PeriodicProgressSampler(
                server, settings, progressBuffer, progressModifier, economy, callbackTasks, nativeWork, samplingWorker,
                { failure -> logger.log(Level.WARNING, "Could not sample ArcRanks player progress", failure) },
            )

            server.servicesManager.register(RankProgressApi::class.java, api, this, ServicePriority.Normal)
            val recoveringPlayers = server.onlinePlayers.toTypedArray().iterator()
            nativeWork.submit({ true }) {
                if (!recoveringPlayers.hasNext()) true else {
                    val player = recoveringPlayers.next()
                    if (player.isOnline) {
                        contractRewardDelivery.deliverPending(player)
                        dailyRewardDelivery.deliverPending(player)
                    }
                    !recoveringPlayers.hasNext()
                }
            }.whenComplete { _, failure -> if (failure != null) logger.log(Level.WARNING, "Could not finish online rank reward recovery", failure) }
            installPlaceholders(cache, rankState, questTracker::placeholder)
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
                    rankReminders.onReload()
                    if (diff.liveAreas.any { it in SNAPSHOT_INVALIDATING_AREAS }) {
                        cache.invalidateSnapshots()
                        val players = server.onlinePlayers.toTypedArray().iterator()
                        nativeWork.submit({ configuration.current() === candidate }) {
                            if (!players.hasNext()) true else {
                                val player = players.next()
                                if (player.isOnline) playerService.load(player.uniqueId).exceptionally { null }
                                !players.hasNext()
                            }
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
    }

    private fun failStartup(failure: Throwable) {
        sqlReady.set(false)
        luckPermsReady.set(false)
        runCatching { lifecycle?.health?.markDown(); lifecycle?.emitHealth() }
        logger.log(Level.SEVERE, "ArcRanks failed closed during startup", failure)
        server.pluginManager.disablePlugin(this)
    }

    override fun onDisable() {
        hotload.prepare()
        hotload.close()
    }

    private fun stopRuntime() {
        val runtime = lifecycle ?: return
        lifecycle = null
        val sql = sqlRuntime.also { sqlRuntime = null }
        val binding = sqlBinding.also { sqlBinding = null }
        val pendingBuffer = buffer
        val pendingTelemetry = telemetry
        val sampler = samplingStorage.also { samplingStorage = null }
        var failure: Throwable? = null
        fun attempt(close: () -> Unit) {
            try { close() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        attempt { if (::nativeWork.isInitialized) nativeWork.close() }
        attempt { questAvailabilityClient?.close() }
        questAvailabilityClient = null
        attempt { placeholders?.unregister() }
        attempt { server.servicesManager.unregisterAll(this) }
        attempt { runtime.close() }
        // ArcCore's cleanup service retires packets independently of the closed nativeWork queue.
        celebrationDisplayDrain = try {
            celebrationDisplays?.closeAsync() ?: CompletableFuture.completedFuture(null)
        } catch (error: Throwable) {
            CompletableFuture.failedFuture(error)
        }
        celebrationDisplays = null
        attempt { sampler?.closeAsync()?.join() }
        // Callback scopes are cancelled above; the terminal lane only performs IO.
        attempt {
            if (::storage.isInitialized) storage.closeAsync {
                var terminalFailure: Throwable? = null
                fun closeResource(close: () -> Unit) {
                    try { close() } catch (error: Throwable) {
                        if (terminalFailure == null) terminalFailure = error else if (terminalFailure !== error) terminalFailure.addSuppressed(error)
                    }
                }
                closeResource { pendingBuffer?.flushAll()?.join() }
                closeResource { pendingTelemetry?.flushAll()?.join() }
                while (true) {
                    val resource = storageResources.pollLast() ?: break
                    closeResource { if (resource is MySqlOneTimeUseLedger) resource.closeAsync().join() else resource.close() }
                }
                closeResource { sql?.executor?.closeAsync()?.join() }
                closeResource { sql?.close() }
                terminalFailure?.let { throw it }
            }.join()
        }
        attempt { binding?.close() }
        attempt { hotload.retireGameplayRegistrations() }
        attempt { ArcLogging.close() }
        if (failure == null) shutdownDrain.complete(Unit) else {
            shutdownDrain.completeExceptionally(failure)
            logger.log(Level.SEVERE, "Could not close ArcRanks storage", failure)
        }
        placeholders = null
        telemetry = null
        buffer = null
        reloadInFlight = null
        sqlReady.set(false)
        luckPermsReady.set(false)
        cachedPlayers.set(null)
        ConfigManager.clear()
        Tasks.reset()
    }

    private fun reloadPlugin(): CompletableFuture<ArcRanksReloadResult> {
        reloadInFlight?.takeUnless { it.isDone }?.let { return CompletableFuture.completedFuture(ArcRanksReloadResult.Busy) }
        data class Prepared(val menus: ru.arc.menu.MenuCatalog, val reload: ArcRanksReloadCoordinator.Prepared)
        var pending: ArcRanksReloadCoordinator.Prepared? = null
        val reload = storage.submit {
            val menus = menuLayouts.prepare(dataPath)
            val prepared = reloadCoordinator.prepare().also { pending = it }
            Prepared(menus, prepared)
        }.thenCompose { prepared ->
            nativeWork.call({ true }) {
                val core = reloadCoordinator.apply(prepared.reload)
                if (core is ArcRanksReloadResult.Applied || core is ArcRanksReloadResult.NoChanges) {
                    menuLayouts.replace(prepared.menus)
                    ArcRanksMenuSessions.closeOpen(server)
                }
                core
            }
        }.thenCompose { core ->
            if (core is ArcRanksReloadResult.Applied || core is ArcRanksReloadResult.NoChanges) {
                storage.submit { mergeLoggingReload(core, loggingReloader.reloadIfChanged()) }
            } else CompletableFuture.completedFuture(core)
        }.handle { result, failure -> result ?: ArcRanksReloadResult.Invalid(
            (failure?.cause?.message ?: failure?.message ?: "Reload did not complete").replace('\n', ' ').take(240))
        }.thenCompose { result -> nativeWork.call({ true }) { reportReloadResult(result); result } }
        reload.whenComplete { _, failure -> if (failure != null) pending?.let(reloadCoordinator::abandon) }
        return reload.also { reloadInFlight = it }
    }

    private fun reportReloadResult(result: ArcRanksReloadResult) {
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
        listOf("config.yml", "ranks.yml", "perks.yml", "contracts.yml", "daily-quests.yml", "weekly-kits.yml", "lang/ru.yml", "lang/en.yml")
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
                    "daily_quests" to 12,
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

    private fun installPlaceholders(
        cache: RankSnapshotCache,
        rankState: LuckPermsRankStateGateway,
        questPlaceholder: (java.util.UUID, String) -> String?,
    ) {
        if (!server.pluginManager.isPluginEnabled("PlaceholderAPI")) return
        placeholders = ArcRanksPlaceholderExpansion(
            this,
            { configuration.current().ranks.catalog },
            { configuration.current().locale },
            { configuration.current().ranks.mastery },
            cache,
            currentRank = rankState::current,
            questPlaceholder = questPlaceholder,
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
