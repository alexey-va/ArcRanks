package ru.ruscrafting.ranks.dialog

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.TextColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.ruscrafting.ranks.admin.AdminProgressAdvanceResult
import ru.ruscrafting.ranks.admin.AdminProgressService
import ru.ruscrafting.ranks.analytics.AnalyticsService
import ru.ruscrafting.ranks.analytics.TelemetryHealthSnapshot
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.config.PromotionMode
import ru.ruscrafting.ranks.contract.ActiveContract
import ru.ruscrafting.ranks.contract.ContractAcceptResult
import ru.ruscrafting.ranks.contract.ContractAdminCompleteResult
import ru.ruscrafting.ranks.contract.ContractBoard
import ru.ruscrafting.ranks.contract.ContractBonusReward
import ru.ruscrafting.ranks.contract.ContractClaimResult
import ru.ruscrafting.ranks.contract.ContractId
import ru.ruscrafting.ranks.contract.ContractPlayerContext
import ru.ruscrafting.ranks.contract.ContractRerollResult
import ru.ruscrafting.ranks.contract.ContractRewardDeliveryResult
import ru.ruscrafting.ranks.contract.ContractService
import ru.ruscrafting.ranks.domain.GoalProgress
import ru.ruscrafting.ranks.domain.GoalState
import ru.ruscrafting.ranks.domain.MasteryLevel
import ru.ruscrafting.ranks.domain.NextStep
import ru.ruscrafting.ranks.domain.RankCatalog
import ru.ruscrafting.ranks.domain.RankDefinition
import ru.ruscrafting.ranks.domain.RankEligibility
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.gui.ContractMenu
import ru.ruscrafting.ranks.gui.RankPassportMenu
import ru.ruscrafting.ranks.gui.WeeklyKitMenu
import ru.ruscrafting.ranks.kit.WeeklyKitAdminResetResult
import ru.ruscrafting.ranks.kit.WeeklyKitCatalog
import ru.ruscrafting.ranks.kit.WeeklyKitClaimRequest
import ru.ruscrafting.ranks.kit.WeeklyKitClaimResult
import ru.ruscrafting.ranks.kit.WeeklyKitClaimState
import ru.ruscrafting.ranks.kit.WeeklyKitDefinition
import ru.ruscrafting.ranks.kit.WeeklyKitService
import ru.ruscrafting.ranks.perk.PerkCatalog
import ru.ruscrafting.ranks.perk.PerkDefinition
import ru.ruscrafting.ranks.perk.PerkId
import ru.ruscrafting.ranks.perk.PerkSelectionResult
import ru.ruscrafting.ranks.perk.PerkSelectionService
import ru.ruscrafting.ranks.promotion.PromotionResult
import ru.ruscrafting.ranks.promotion.PromotionService
import ru.ruscrafting.ranks.rankstate.RankState
import ru.ruscrafting.ranks.service.RankPlayerService
import ru.ruscrafting.ranks.service.RankPlayerSnapshot
import ru.ruscrafting.ranks.text.RankLocale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Native-dialog presentation of the same rank domain used by the inventory menus. */
class RankDialogController(
    private val runtime: PaperDialogRuntime,
    private val settings: () -> ArcRanksSettings,
    private val catalog: () -> RankCatalog,
    private val locale: () -> RankLocale,
    private val players: RankPlayerService,
    private val promotions: PromotionService,
    private val adminProgress: AdminProgressService,
    private val contracts: ContractService,
    private val rewardDelivery: (Player, ActiveContract) -> CompletableFuture<ContractRewardDeliveryResult>,
    private val perksCatalog: () -> PerkCatalog,
    private val perks: PerkSelectionService,
    private val weeklyKitCatalog: () -> WeeklyKitCatalog,
    private val weeklyKits: WeeklyKitService,
    private val analytics: AnalyticsService,
    private val analyticsHealth: () -> TelemetryHealthSnapshot,
    private val tasks: LifecycleTaskScope,
    private val openHelp: (Player) -> Unit,
) : Listener {
    private val serial = AtomicLong()
    private val navigation = ConcurrentHashMap<java.util.UUID, Long>()

    fun open(player: Player) = loadSnapshot(player, ::showRoot)

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        navigation.remove(event.player.uniqueId)
    }

    private fun showRoot(player: Player, snapshot: RankPlayerSnapshot) {
        val evaluation = snapshot.evaluation
        if (evaluation == null) return showRankStateError(player, snapshot.rankState, ::open)
        val current = evaluation.currentRank
        val values = mapOf(
            "rank" to tr(current.displayNameKey, player),
            "focus" to tr(snapshot.profile.selectedFocus.nameKey(), player),
            "next" to (evaluation.nextRank?.let { tr(it.displayNameKey, player) } ?: tr("dialogs.common.none", player)),
            "recommendation" to recommendation(player, snapshot),
        )
        val buttons = buildList {
            add(button("paths", "dialogs.root.paths", player) { showPaths(player, snapshot) })
            add(button("benefits", "dialogs.root.benefits", player) { showBenefitCatalog(player, snapshot) })
            if (settings().features.contracts) add(button("contracts", "dialogs.root.contracts", player) { openContracts(player) })
            if (settings().features.perks) add(button("perks", "dialogs.root.perks", player) { openPerks(player) })
            if (settings().features.weeklyKits) add(button("weekly_kit", "dialogs.root.weekly-kit", player) { openWeeklyKit(player) })
            if (evaluation.eligibility == RankEligibility.READY && player.hasPermission("arcranks.rankup")) {
                add(button("promote", "dialogs.root.promote", player, "dialogs.root.promote-tooltip") { promote(player) })
            }
            if (player.hasPermission(RankPassportMenu.ADMIN_GRANT_PERMISSION)) {
                add(button("admin_advance", "dialogs.admin.advance", player, "dialogs.admin.advance-tooltip") { adminAdvance(player) })
            }
            if (player.hasPermission(RankPassportMenu.ADMIN_ANALYTICS_PERMISSION)) {
                add(button("admin_analytics", "dialogs.admin.analytics", player, "dialogs.admin.analytics-tooltip") { openAnalytics(player) })
            }
        }
        runtime.open(
            player,
            PaperDialogScreen(
                id = "ranks.root",
                title = tr("dialogs.root.title", player),
                body = listOf(
                    body("dialogs.root.intro", player),
                    body("dialogs.root.status", player, values),
                    body(if (evaluation.eligibility == RankEligibility.TOP_RANK) "dialogs.root.top" else "dialogs.root.next", player, values),
                ),
                buttons = buttons,
                exitButton = button("help", "dialogs.common.help", player, "dialogs.common.help-tooltip", close = true) { openHelp(player) },
                columns = 2,
            ),
        )
    }

    private fun showBenefitCatalog(player: Player, snapshot: RankPlayerSnapshot) {
        val currentOrder = snapshot.evaluation?.currentRank?.order ?: 0
        runtime.open(
            player,
            PaperDialogScreen(
                id = "ranks.benefits",
                title = tr("dialogs.benefits.title", player),
                body = listOf(body("dialogs.benefits.intro", player)),
                buttons = catalog().ranks.map { rank ->
                    val stateKey = when {
                        rank.order < currentOrder -> "dialogs.benefits.completed"
                        rank.order == currentOrder -> "dialogs.benefits.current"
                        rank.order == currentOrder + 1 -> "dialogs.benefits.next"
                        else -> "dialogs.benefits.locked"
                    }
                    button(
                        "rank_${rank.id.value}",
                        label = singleLineLabel(
                            tr(stateKey, player),
                            tr(rank.displayNameKey, player)
                                .append(Component.space())
                                .append(navigationMarker()),
                        ),
                        tooltip = tr("dialogs.benefits.open-tooltip", player),
                    ) { showBenefits(player, snapshot, rank) }
                },
                exitButton = back("root", player) { open(player) },
                columns = 3,
            ),
        )
    }

    private fun showBenefits(player: Player, snapshot: RankPlayerSnapshot, rank: RankDefinition) {
        val currentOrder = snapshot.evaluation?.currentRank?.order ?: 0
        val state = when {
            rank.order < currentOrder -> "dialogs.benefits.state-completed"
            rank.order == currentOrder -> "dialogs.benefits.state-current"
            rank.order == currentOrder + 1 -> "dialogs.benefits.state-next"
            else -> "dialogs.benefits.state-locked"
        }
        val sections = rank.benefitSections.associateBy { it.startIndex }
        val lines = buildList {
            add(tr("dialogs.benefits.detail", player, mapOf("rank" to tr(rank.displayNameKey, player), "state" to tr(state, player))))
            val sectionLines = mutableListOf<Component>()
            rank.benefitKeys.forEachIndexed { index, key ->
                sections[index]?.let {
                    if (sectionLines.isNotEmpty()) add(joined(*sectionLines.toTypedArray()))
                    sectionLines.clear()
                    sectionLines.add(tr(it.titleKey, player))
                }
                sectionLines.add(tr(key, player))
            }
            if (sectionLines.isNotEmpty()) add(joined(*sectionLines.toTypedArray()))
        }
        runtime.open(
            player,
            PaperDialogScreen(
                id = "ranks.benefits.detail",
                title = tr(rank.displayNameKey, player),
                body = listOf(PaperDialogBody(joinedSpaced(*lines.toTypedArray()), 520)),
                buttons = listOf(button("all_ranks", "dialogs.root.benefits", player) { showBenefitCatalog(player, snapshot) }),
                exitButton = back("benefits", player) { showBenefitCatalog(player, snapshot) },
            ),
        )
    }

    private fun showPaths(player: Player, snapshot: RankPlayerSnapshot) {
        val evaluation = snapshot.evaluation ?: return showRankStateError(player, snapshot.rankState, ::open)
        val values = mapOf(
            "completed" to locale().text(evaluation.completedChoices),
            "required" to locale().text(evaluation.requiredChoices),
            "focus" to tr(snapshot.profile.selectedFocus.nameKey(), player),
        )
        runtime.open(
            player,
            PaperDialogScreen(
                id = "ranks.paths",
                title = tr("dialogs.paths.title", player),
                body = listOf(body("dialogs.paths.intro", player), body("dialogs.paths.status", player, values)),
                buttons = SpecializationPath.entries.map { path ->
                    val goal = evaluation.goals.firstOrNull { it.path == path }
                    val status = when {
                        snapshot.profile.selectedFocus == path -> "dialogs.paths.marker-focus"
                        goal?.state == GoalState.COMPLETE -> "dialogs.paths.marker-complete"
                        goal?.state == GoalState.UNAVAILABLE -> "dialogs.paths.marker-unavailable"
                        else -> "dialogs.paths.marker-progress"
                    }
                    button(
                        "path_${path.name.lowercase()}",
                        label = singleLineLabel(
                            tr(status, player),
                            tr(path.nameKey(), player)
                                .append(Component.space())
                                .append(navigationMarker()),
                        ),
                        tooltip = tr(path.summaryKey(), player),
                    ) { showPath(player, snapshot, path) }
                },
                exitButton = back("root", player) { open(player) },
                columns = 2,
            ),
        )
    }

    private fun showPath(player: Player, snapshot: RankPlayerSnapshot, path: SpecializationPath) {
        val evaluation = snapshot.evaluation ?: return showRankStateError(player, snapshot.rankState, ::open)
        val current = path.progressValue(snapshot.profile.progress)
        val goal = evaluation.goals.firstOrNull { it.path == path }
        val target = goal?.required ?: current
        val sources = locale().renderLines(path.sourcesKey(), player)
        val values = mapOf(
            "path" to tr(path.nameKey(), player),
            "summary" to tr(path.summaryKey(), player),
            "details" to tr(path.detailsKey(), player),
            "current" to locale().text(current),
            "target" to locale().text(target),
            "bar" to progressBar(current, target),
            "percent" to locale().text(progressPercent(current, target)),
            "mastery" to tr(snapshot.mastery.getValue(path).localeKey(), player),
            "state" to tr(pathStateKey(snapshot, goal, path), player),
        )
        val explanation = listOf(
            PaperDialogBody(tr("dialogs.paths.detail", player, values), 440),
            PaperDialogBody(
                joined(tr("dialogs.paths.actions", player), *sources.toTypedArray()),
                440,
            ),
            PaperDialogBody(tr("dialogs.paths.progress", player, values), 440),
            PaperDialogBody(tr("dialogs.paths.focus-explanation", player, values), 440),
        )
        val buttons = buildList {
            if (snapshot.profile.selectedFocus != path && snapshot.availability.isAvailable(path)) {
                add(button("select_focus", "dialogs.paths.select-focus", player, "dialogs.paths.select-focus-tooltip") { selectFocus(player, path) })
            }
            add(button("all_paths", "dialogs.paths.all", player) { showPaths(player, snapshot) })
        }
        runtime.open(
            player,
            PaperDialogScreen(
                id = "ranks.path",
                title = tr(path.nameKey(), player),
                body = explanation,
                buttons = buttons,
                exitButton = back("root", player) { showRoot(player, snapshot) },
                columns = 2,
            ),
        )
    }

    private fun selectFocus(player: Player, path: SpecializationPath) {
        val token = showLoading(player, "dialogs.paths.saving") { open(player) }
        players.load(player.uniqueId).thenCompose { current ->
            if (!current.availability.isAvailable(path)) {
                CompletableFuture.failedFuture(IllegalStateException("Path is no longer available"))
            } else {
                players.selectFocus(player.uniqueId, path)
            }
        }.whenCompleteSync(tasks) { snapshot, failure ->
            if (!current(player, token)) return@whenCompleteSync
            if (failure != null || snapshot == null) showError(player, ::open)
            else {
                player.sendMessage(tr("commands.focus.selected", player, mapOf("path" to tr(path.nameKey(), player))))
                showPath(player, snapshot, path)
            }
        }
    }

    private fun openContracts(player: Player) {
        if (!settings().features.contracts) return featureDisabled(player, "features.contracts")
        val token = showLoading(player, "dialogs.contracts.loading", ::open)
        players.load(player.uniqueId).thenCompose { snapshot ->
            contracts.board(player.uniqueId, snapshot.contractContext()).thenApply { snapshot to it }
        }.whenCompleteSync(tasks) { loaded, failure ->
            if (!current(player, token)) return@whenCompleteSync
            if (failure != null || loaded == null) showError(player, ::openContracts)
            else showContracts(player, loaded.first, loaded.second)
        }
    }

    private fun showContracts(player: Player, snapshot: RankPlayerSnapshot, board: ContractBoard) {
        val status = mapOf(
            "period" to locale().renderWeekPeriod(board.cycle.start, player),
            "stamps" to locale().text(board.claimedStamps),
            "remaining" to locale().text((3 - board.claimedStamps).coerceAtLeast(0)),
        )
        val bodies = mutableListOf(body("dialogs.contracts.intro", player), body("dialogs.contracts.status", player, status))
        val buttons = mutableListOf<PaperDialogButton>()
        val active = board.active
        if (active != null) {
            bodies += PaperDialogBody(contractBody(player, active), 520)
            if (active.completed) {
                buttons += button("claim", "dialogs.contracts.claim", player, "dialogs.contracts.claim-tooltip") { claimContract(player) }
            } else if (player.hasPermission(ContractMenu.ADMIN_CONTRACT_PERMISSION)) {
                buttons += button("admin_complete", "dialogs.admin.contract-complete", player, "dialogs.admin.contract-complete-tooltip") {
                    adminCompleteContract(player)
                }
            }
        } else if (board.offers.isNotEmpty()) {
            board.offers.forEachIndexed { index, offer ->
                bodies += PaperDialogBody(
                    offerBody(player, index + 1, offer.path, offer.targetDelta, offer.rewardDelta, offer.bonusReward),
                    520,
                )
                buttons += button(
                    "offer_${index + 1}",
                    label = tr("dialogs.contracts.accept", player, mapOf("number" to locale().text(index + 1))),
                    tooltip = tr("dialogs.contracts.accept-tooltip", player),
                ) { acceptContract(player, offer.id) }
            }
            if (board.rerollAvailable) {
                buttons += button("reroll", "dialogs.contracts.reroll", player, "dialogs.contracts.reroll-tooltip") { rerollContracts(player) }
            }
        } else {
            bodies += body("dialogs.contracts.complete", player)
        }
        buttons += button("refresh", "dialogs.common.refresh", player) { openContracts(player) }
        runtime.open(
            player,
            PaperDialogScreen(
                id = "ranks.contracts",
                title = tr("dialogs.contracts.title", player),
                body = bodies,
                buttons = buttons,
                exitButton = back("root", player) { open(player) },
                columns = 2,
            ),
        )
    }

    private fun acceptContract(player: Player, offerId: ContractId) {
        val token = showLoading(player, "dialogs.contracts.saving", ::openContracts)
        players.load(player.uniqueId).thenCompose { snapshot ->
            contracts.accept(player.uniqueId, offerId, snapshot.contractContext())
        }.whenCompleteSync(tasks) { result, failure ->
            if (!current(player, token)) return@whenCompleteSync
            contractResultMessage(player, result, failure)
            if (player.isOnline) openContracts(player)
        }
    }

    private fun rerollContracts(player: Player) {
        val token = showLoading(player, "dialogs.contracts.saving", ::openContracts)
        players.load(player.uniqueId).thenCompose { snapshot ->
            contracts.reroll(player.uniqueId, snapshot.contractContext())
        }.whenCompleteSync(tasks) { result, failure ->
            if (!current(player, token)) return@whenCompleteSync
            contractResultMessage(player, result, failure)
            if (player.isOnline) openContracts(player)
        }
    }

    private fun adminCompleteContract(player: Player) {
        if (!player.hasPermission(ContractMenu.ADMIN_CONTRACT_PERMISSION)) return openContracts(player)
        val token = showLoading(player, "dialogs.contracts.saving", ::openContracts)
        contracts.adminComplete(player.uniqueId, player.uniqueId.toString()).whenCompleteSync(tasks) { result, failure ->
            if (!current(player, token)) return@whenCompleteSync
            contractResultMessage(player, result, failure)
            if (player.isOnline) openContracts(player)
        }
    }

    private fun claimContract(player: Player) {
        val token = showLoading(player, "dialogs.contracts.saving", ::openContracts)
        contracts.claim(player.uniqueId).whenCompleteSync(tasks) { result, failure ->
            if (!current(player, token)) return@whenCompleteSync
            if (failure != null || result == null) {
                player.sendMessage(tr("commands.contracts.storage-unavailable", player))
                if (player.isOnline) openContracts(player)
                return@whenCompleteSync
            }
            if (result is ContractClaimResult.Claimed) {
                rewardDelivery(player, result.contract).whenCompleteSync(tasks) { delivery, deliveryFailure ->
                    if (!current(player, token)) return@whenCompleteSync
                    val key = when {
                        deliveryFailure != null || delivery == ContractRewardDeliveryResult.RECOVERY -> "commands.contracts.delivery-recovery"
                        delivery == ContractRewardDeliveryResult.PENDING -> "commands.contracts.delivery-pending"
                        else -> "commands.contracts.claimed"
                    }
                    player.sendMessage(tr(key, player, contractValues(player, result.contract)))
                    if (player.isOnline) openContracts(player)
                }
            } else {
                contractResultMessage(player, result, null)
                if (player.isOnline) openContracts(player)
            }
        }
    }

    private fun openPerks(player: Player) = loadSnapshot(player) { loadedPlayer, snapshot -> showPerks(loadedPlayer, snapshot) }

    private fun showPerks(player: Player, snapshot: RankPlayerSnapshot) {
        if (!settings().features.perks) return featureDisabled(player, "features.perks")
        val currentCatalog = perksCatalog()
        val lines = buildList {
            add(tr("dialogs.perks.intro", player))
            (1..2).forEach { slot ->
                val perk = snapshot.perkSlots[slot]?.let(currentCatalog::require)
                add(
                    tr(
                        if (perk == null) "dialogs.perks.slot-empty" else "dialogs.perks.slot-active",
                        player,
                        mapOf(
                            "slot" to locale().text(slot),
                            "perk" to (perk?.let { tr(it.nameKey, player) } ?: tr("dialogs.common.none", player)),
                            "description" to (perk?.let { tr(it.descriptionKey, player) } ?: Component.empty()),
                        ),
                    ),
                )
            }
        }
        runtime.open(
            player,
            PaperDialogScreen(
                id = "ranks.perks",
                title = tr("dialogs.perks.title", player),
                body = listOf(PaperDialogBody(joined(*lines.toTypedArray()), 520)),
                buttons = listOf(
                    button("slot_1", label = tr("dialogs.perks.choose-slot", player, mapOf("slot" to locale().text(1)))) { showPerkPaths(player, snapshot, 1) },
                    button("slot_2", label = tr("dialogs.perks.choose-slot", player, mapOf("slot" to locale().text(2)))) { showPerkPaths(player, snapshot, 2) },
                    button("refresh", "dialogs.common.refresh", player) { openPerks(player) },
                ),
                exitButton = back("root", player) { open(player) },
                columns = 2,
            ),
        )
    }

    private fun showPerkPaths(player: Player, snapshot: RankPlayerSnapshot, slot: Int) {
        runtime.open(
            player,
            PaperDialogScreen(
                id = "ranks.perks.slot",
                title = tr("dialogs.perks.slot-title", player, mapOf("slot" to locale().text(slot))),
                body = listOf(body("dialogs.perks.slot-intro", player, mapOf("slot" to locale().text(slot)))),
                buttons = SpecializationPath.entries.map { path ->
                    button(
                        "perk_path_${path.name.lowercase()}",
                        label = tr(path.nameKey(), player),
                        tooltip = tr(path.summaryKey(), player),
                    ) { showPerkPath(player, snapshot, slot, path) }
                },
                exitButton = back("perks", player) { showPerks(player, snapshot) },
                columns = 2,
            ),
        )
    }

    private fun showPerkPath(player: Player, snapshot: RankPlayerSnapshot, slot: Int, path: SpecializationPath) {
        val definitions = perksCatalog().forPath(path)
        val lines = buildList {
            add(tr("dialogs.perks.path-intro", player, mapOf(
                "path" to tr(path.nameKey(), player),
                "mastery" to tr(snapshot.mastery.getValue(path).localeKey(), player),
                "slot" to locale().text(slot),
            )))
            definitions.forEachIndexed { index, perk -> add(perkBody(player, index + 1, perk, snapshot)) }
        }
        val buttons = definitions.mapIndexed { index, perk ->
            val selected = snapshot.perkSlots[slot] == perk.id
            button(
                "perk_${index + 1}",
                label = tr(
                    if (selected) "dialogs.perks.disable" else "dialogs.perks.select",
                    player,
                    mapOf("perk" to tr(perk.nameKey, player)),
                ),
                tooltip = tr(perk.descriptionKey, player),
            ) { changePerk(player, snapshot, slot, perk.id, selected) }
        }
        runtime.open(
            player,
            PaperDialogScreen(
                id = "ranks.perks.path",
                title = tr(path.nameKey(), player),
                body = listOf(PaperDialogBody(joined(*lines.toTypedArray()), 520)),
                buttons = buttons,
                exitButton = back("perk_paths", player) { showPerkPaths(player, snapshot, slot) },
                columns = 1,
            ),
        )
    }

    private fun changePerk(player: Player, snapshot: RankPlayerSnapshot, slot: Int, perkId: PerkId, remove: Boolean) {
        val token = showLoading(player, "dialogs.perks.saving", ::openPerks)
        val operation = if (remove) {
            perks.remove(player.uniqueId, perkId)
        } else {
            players.load(player.uniqueId).thenCompose { current ->
                perks.selectIntoSlot(player.uniqueId, slot, perkId, current.mastery)
            }
        }
        operation.whenCompleteSync(tasks) { result, failure ->
            if (!current(player, token)) return@whenCompleteSync
            val key = when {
                failure != null || result == null -> "commands.perks.storage-unavailable"
                result is PerkSelectionResult.Selected -> "commands.perks.selected"
                result is PerkSelectionResult.AlreadySelected -> "commands.perks.already-selected"
                result is PerkSelectionResult.Removed -> "commands.perks.removed"
                result is PerkSelectionResult.NotSelected -> "commands.perks.not-selected"
                result is PerkSelectionResult.Locked -> "commands.perks.locked"
                result is PerkSelectionResult.Full -> "commands.perks.full"
                else -> "commands.perks.storage-unavailable"
            }
            val definition = when (result) {
                is PerkSelectionResult.Selected -> result.perk
                is PerkSelectionResult.AlreadySelected -> result.perk
                is PerkSelectionResult.Removed -> result.perk
                is PerkSelectionResult.NotSelected -> result.perk
                is PerkSelectionResult.Locked -> result.perk
                is PerkSelectionResult.Full -> result.perk
                else -> null
            }
            player.sendMessage(tr(key, player, definition?.let { mapOf("perk" to tr(it.nameKey, player)) }.orEmpty()))
            if (player.isOnline) openPerks(player)
        }
    }

    private fun openWeeklyKit(player: Player) {
        if (!settings().features.weeklyKits) return featureDisabled(player, "features.weekly-kits")
        val token = showLoading(player, "dialogs.weekly-kit.loading", ::open)
        players.load(player.uniqueId).thenCompose { snapshot ->
            val rankId = (snapshot.rankState as? RankState.Exact)?.rankId
                ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("Player has no exact progression rank"))
            val definition = weeklyKitCatalog().require(rankId)
            weeklyKits.state(player.uniqueId).thenApply { state -> Triple(snapshot, definition, state) }
        }.whenCompleteSync(tasks) { loaded, failure ->
            if (!current(player, token)) return@whenCompleteSync
            if (failure != null || loaded == null) showError(player, ::openWeeklyKit)
            else showWeeklyKit(player, loaded.first, loaded.second, loaded.third)
        }
    }

    private fun showWeeklyKit(
        player: Player,
        snapshot: RankPlayerSnapshot,
        definition: WeeklyKitDefinition,
        state: WeeklyKitClaimState,
    ) {
        val values = mapOf(
            "rank" to tr("ranks.${definition.rankId.value}.name", player),
            "required" to locale().text(definition.minimumFreeSlots),
            "state" to tr("dialogs.weekly-kit.state.${state.name.lowercase()}", player),
        )
        val content = definition.contentKeys.map { tr(it, player) }
        val buttons = buildList {
            if (state == WeeklyKitClaimState.AVAILABLE) {
                add(button("claim_kit", "dialogs.weekly-kit.claim", player, "dialogs.weekly-kit.claim-tooltip") { claimWeeklyKit(player) })
            }
            if (state == WeeklyKitClaimState.CLAIMED && player.hasPermission(WeeklyKitMenu.ADMIN_PERMISSION)) {
                add(button("admin_reset_kit", "dialogs.admin.kit-reset", player, "dialogs.admin.kit-reset-tooltip") { resetWeeklyKit(player) })
            }
            add(button("refresh", "dialogs.common.refresh", player) { openWeeklyKit(player) })
        }
        runtime.open(
            player,
            PaperDialogScreen(
                id = "ranks.weekly-kit",
                title = tr("dialogs.weekly-kit.title", player),
                body = listOf(
                    body("dialogs.weekly-kit.intro", player),
                    body("dialogs.weekly-kit.status", player, values),
                    PaperDialogBody(joined(tr(definition.summaryKey, player), *content.toTypedArray()), 520),
                ),
                buttons = buttons,
                exitButton = back("root", player) { open(player) },
                columns = 2,
            ),
        )
    }

    private fun claimWeeklyKit(player: Player) {
        val token = showLoading(player, "dialogs.weekly-kit.claiming", ::openWeeklyKit)
        val freeSlots = player.inventory.storageContents.count { it == null || it.type.isAir }
        players.load(player.uniqueId).thenCompose { current ->
            val currentRank = (current.rankState as? RankState.Exact)?.rankId
                ?: return@thenCompose CompletableFuture.failedFuture(IllegalStateException("Player has no exact progression rank"))
            val currentDefinition = weeklyKitCatalog().require(currentRank)
            weeklyKits.claim(
                WeeklyKitClaimRequest(player.uniqueId, player.name, currentDefinition, settings().serverId, freeSlots),
            )
        }.whenCompleteSync(tasks) { result, failure ->
            if (!current(player, token)) return@whenCompleteSync
            val resolved = if (failure == null && result != null) result else WeeklyKitClaimResult.StorageUnavailable
            val key = when (resolved) {
                WeeklyKitClaimResult.Claimed -> "commands.weekly-kit.claimed"
                WeeklyKitClaimResult.AlreadyClaimed -> "commands.weekly-kit.already-claimed"
                WeeklyKitClaimResult.DeliveryPending -> "commands.weekly-kit.delivery-pending"
                is WeeklyKitClaimResult.InventoryFull -> "commands.weekly-kit.inventory-full"
                WeeklyKitClaimResult.ProviderRejected -> "commands.weekly-kit.provider-rejected"
                WeeklyKitClaimResult.StorageUnavailable -> "commands.weekly-kit.storage-unavailable"
            }
            val values = if (resolved is WeeklyKitClaimResult.InventoryFull) {
                mapOf("required" to locale().text(resolved.requiredFreeSlots))
            } else emptyMap()
            player.sendMessage(tr(key, player, values))
            if (player.isOnline) openWeeklyKit(player)
        }
    }

    private fun resetWeeklyKit(player: Player) {
        if (!player.hasPermission(WeeklyKitMenu.ADMIN_PERMISSION)) return openWeeklyKit(player)
        val token = showLoading(player, "dialogs.weekly-kit.saving", ::openWeeklyKit)
        weeklyKits.adminReset(player.uniqueId, player.uniqueId.toString()).whenCompleteSync(tasks) { result, failure ->
            if (!current(player, token)) return@whenCompleteSync
            val key = when {
                failure != null || result == null || result == WeeklyKitAdminResetResult.StorageUnavailable ->
                    "commands.weekly-kit.admin-reset-storage-unavailable"
                result == WeeklyKitAdminResetResult.Reset -> "commands.weekly-kit.admin-reset"
                result == WeeklyKitAdminResetResult.DeliveryPending -> "commands.weekly-kit.admin-reset-delivering"
                else -> "commands.weekly-kit.admin-reset-not-claimed"
            }
            player.sendMessage(tr(key, player, mapOf("player" to locale().text(player.name))))
            if (player.isOnline) openWeeklyKit(player)
        }
    }

    private fun promote(player: Player) {
        if (!player.hasPermission("arcranks.rankup")) return open(player)
        if (settings().promotionMode == PromotionMode.SHADOW) {
            player.sendMessage(tr("commands.shadow-mode", player))
            return open(player)
        }
        val token = showLoading(player, "dialogs.root.promoting", ::open)
        promotions.promote(player.uniqueId).whenCompleteSync(tasks) { result, failure ->
            if (!current(player, token)) return@whenCompleteSync
            val key = when {
                failure != null || result == null -> "commands.storage-unavailable"
                result is PromotionResult.Promoted || result is PromotionResult.Recovered -> "commands.promotion.success"
                result is PromotionResult.NotEligible -> "commands.promotion.not-eligible"
                result is PromotionResult.RankStateProblem -> "commands.promotion.retryable"
                result == PromotionResult.TopRank -> "commands.why.top"
                result == PromotionResult.Busy -> "commands.promotion.busy"
                else -> "commands.promotion.retryable"
            }
            val rankId = when (result) {
                is PromotionResult.Promoted -> result.rankId
                is PromotionResult.Recovered -> result.rankId
                else -> null
            }
            player.sendMessage(tr(key, player, rankId?.let { mapOf("rank" to tr(catalog().require(it).displayNameKey, player)) }.orEmpty()))
            if (player.isOnline) open(player)
        }
    }

    private fun adminAdvance(player: Player) {
        if (!player.hasPermission(RankPassportMenu.ADMIN_GRANT_PERMISSION)) return open(player)
        val token = showLoading(player, "dialogs.admin.working", ::open)
        players.load(player.uniqueId).thenCompose { snapshot ->
            snapshot.evaluation?.let { adminProgress.advance(player.uniqueId, it) }
                ?: CompletableFuture.failedFuture(IllegalStateException("Player has no rank evaluation"))
        }.whenCompleteSync(tasks) { result, failure ->
            if (!current(player, token)) return@whenCompleteSync
            val key = when {
                failure != null || result == null -> "commands.storage-unavailable"
                result is AdminProgressAdvanceResult.Applied -> "commands.admin.advance-applied"
                result == AdminProgressAdvanceResult.Duplicate -> "commands.admin.advance-duplicate"
                result == AdminProgressAdvanceResult.TopRank -> "commands.admin.advance-top"
                else -> "commands.admin.advance-ready"
            }
            val values = buildMap {
                put("player", locale().text(player.name))
                if (result is AdminProgressAdvanceResult.Applied) put("amount", locale().text(result.amount))
            }
            player.sendMessage(tr(key, player, values))
            if (player.isOnline) open(player)
        }
    }

    private fun openAnalytics(player: Player, days: Int = settings().analytics.defaultWindow) {
        if (!player.hasPermission(RankPassportMenu.ADMIN_ANALYTICS_PERMISSION)) return open(player)
        val token = showLoading(player, "dialogs.admin.analytics-loading", ::open)
        analytics.summary(days).whenCompleteSync(tasks) { summary, failure ->
            if (!current(player, token)) return@whenCompleteSync
            if (failure != null || summary == null) return@whenCompleteSync showError(player, ::openAnalytics)
            val health = analyticsHealth()
            val values = mapOf(
                "days" to locale().text(days),
                "players" to locale().text(summary.uniqueSeenPlayers),
                "passport" to locale().text(summary.passportPlayers),
                "accepted" to locale().text(summary.contractAcceptedPlayers),
                "completed" to locale().text(summary.contractCompletedPlayers),
                "perks" to locale().text(summary.perkSelectedPlayers),
                "promotions" to locale().text(summary.promotionSuccesses),
                "dropped" to locale().text(health.droppedMetricKeys + health.droppedPlayers),
            )
            runtime.open(
                player,
                PaperDialogScreen(
                    id = "ranks.admin.analytics",
                    title = tr("dialogs.admin.analytics-title", player),
                    body = listOf(body("dialogs.admin.analytics-body", player, values)),
                    buttons = settings().analytics.windows.map { window ->
                        button("window_$window", label = tr("dialogs.admin.analytics-window", player, mapOf("days" to locale().text(window)))) {
                            openAnalytics(player, window)
                        }
                    },
                    exitButton = back("root", player) { open(player) },
                    columns = 3,
                ),
            )
        }
    }

    private fun loadSnapshot(player: Player, action: (Player, RankPlayerSnapshot) -> Unit) {
        val token = showLoading(player, "dialogs.common.loading", openHelp)
        players.load(player.uniqueId).whenCompleteSync(tasks) { snapshot, failure ->
            if (!current(player, token)) return@whenCompleteSync
            if (failure != null || snapshot == null) showError(player, ::open)
            else action(player, snapshot)
        }
    }

    private fun showLoading(player: Player, bodyKey: String, backAction: (Player) -> Unit): Long {
        val token = markNavigation(player)
        runtime.open(
            player,
            PaperDialogScreen(
                id = "ranks.loading",
                title = tr("dialogs.common.loading-title", player),
                body = listOf(body(bodyKey, player)),
                buttons = listOf(button("back", "dialogs.common.back", player) { backAction(player) }),
                canCloseWithEscape = false,
            ),
        )
        return token
    }

    private fun showError(player: Player, retry: (Player) -> Unit) {
        runtime.open(
            player,
            PaperDialogScreen(
                id = "ranks.error",
                title = tr("dialogs.common.error-title", player),
                body = listOf(body("dialogs.common.error", player)),
                buttons = listOf(button("retry", "dialogs.common.retry", player) { retry(player) }),
                exitButton = button("help", "dialogs.common.help", player, close = true) { openHelp(player) },
            ),
        )
    }

    private fun showRankStateError(player: Player, state: RankState, retry: (Player) -> Unit) {
        val key = when (state) {
            RankState.Missing -> "commands.rank-state.missing"
            is RankState.Conflict -> "commands.rank-state.conflict"
            else -> "commands.rank-state.unknown"
        }
        runtime.open(
            player,
            PaperDialogScreen(
                id = "ranks.rank-state-error",
                title = tr("dialogs.common.error-title", player),
                body = listOf(PaperDialogBody(tr(key, player), 500)),
                buttons = listOf(button("retry", "dialogs.common.retry", player) { retry(player) }),
                exitButton = button("help", "dialogs.common.help", player, close = true) { openHelp(player) },
            ),
        )
    }

    private fun featureDisabled(player: Player, feature: String) {
        player.sendMessage(tr("commands.feature-disabled", player, mapOf("feature" to tr(feature, player))))
        open(player)
    }

    private fun contractResultMessage(player: Player, result: Any?, failure: Throwable?) {
        val key = when {
            failure != null || result == null -> "commands.contracts.storage-unavailable"
            result is ContractAcceptResult.Accepted -> "commands.contracts.accepted"
            result is ContractAcceptResult.AlreadyActive -> "commands.contracts.already-active"
            result == ContractAcceptResult.OfferUnavailable -> "commands.contracts.offer-unavailable"
            result == ContractAcceptResult.CycleComplete -> "commands.contracts.cycle-complete"
            result == ContractAcceptResult.StorageUnavailable -> "commands.contracts.storage-unavailable"
            result is ContractClaimResult.NotReady -> "commands.contracts.not-ready"
            result is ContractClaimResult.AlreadyClaimed -> "commands.contracts.already-claimed"
            result == ContractClaimResult.NoActive -> "commands.contracts.no-active"
            result == ContractClaimResult.StorageUnavailable -> "commands.contracts.storage-unavailable"
            result is ContractRerollResult.Rerolled -> "commands.contracts.rerolled"
            result is ContractRerollResult.Active -> "commands.contracts.already-active"
            result == ContractRerollResult.AlreadyUsed -> "commands.contracts.reroll-used"
            result == ContractRerollResult.CycleComplete -> "commands.contracts.cycle-complete"
            result == ContractRerollResult.StorageUnavailable -> "commands.contracts.storage-unavailable"
            result is ContractAdminCompleteResult.Completed -> "commands.admin.contract-completed"
            result is ContractAdminCompleteResult.AlreadyReady -> "commands.admin.contract-already-ready"
            result == ContractAdminCompleteResult.NoActive -> "commands.admin.contract-no-active"
            else -> "commands.contracts.storage-unavailable"
        }
        val contract = when (result) {
            is ContractAcceptResult.Accepted -> result.contract
            is ContractClaimResult.Claimed -> result.contract
            else -> null
        }
        val values = contract?.let { acceptedContractValues(player, it) }.orEmpty() +
            mapOf("player" to locale().text(player.name))
        player.sendMessage(tr(key, player, values))
    }

    private fun offerBody(
        player: Player,
        number: Int,
        path: SpecializationPath,
        target: Long,
        reward: Long,
        bonus: ContractBonusReward,
    ): Component = joinedSpaced(
        tr("dialogs.contracts.offer", player, contractValues(player, path, target, reward, bonus) + mapOf("number" to locale().text(number))),
        *contractActions(player, path, target).toTypedArray(),
    )

    private fun contractBody(player: Player, contract: ActiveContract): Component = joinedSpaced(
        tr(
            if (contract.completed) "dialogs.contracts.active-ready" else "dialogs.contracts.active",
            player,
            contractValues(player, contract) + mapOf(
                "current" to locale().text(contract.completedDelta),
                "remaining" to locale().text((contract.targetDelta - contract.completedDelta).coerceAtLeast(0)),
                "bar" to progressBar(contract.completedDelta, contract.targetDelta),
            ),
        ),
        *contractActions(player, contract.path, contract.targetDelta).toTypedArray(),
    )

    private fun contractActions(player: Player, path: SpecializationPath, target: Long): List<Component> {
        val root = "gui.contracts.actions.${path.name.lowercase()}"
        val values = mapOf("target" to locale().text(target))
        return listOf(
            tr("dialogs.contracts.counts", player),
            tr("$root.first", player, values),
            tr("$root.second", player, values),
            tr("$root.third", player, values),
        )
    }

    private fun contractValues(player: Player, contract: ActiveContract): Map<String, Component> =
        contractValues(player, contract.path, contract.targetDelta, contract.rewardDelta, contract.bonusReward)

    private fun acceptedContractValues(player: Player, contract: ActiveContract): Map<String, Component> {
        val actions = contractActions(player, contract.path, contract.targetDelta).drop(1)
        return contractValues(player, contract) + mapOf(
            "action-first" to actions[0],
            "action-second" to actions[1],
            "action-third" to actions[2],
        )
    }

    private fun contractValues(
        player: Player,
        path: SpecializationPath,
        target: Long,
        reward: Long,
        bonus: ContractBonusReward,
    ): Map<String, Component> = mapOf(
        "path" to tr(path.nameKey(), player),
        "target" to locale().text(target),
        "reward" to locale().text(reward),
        "money" to locale().text(bonus.money),
        "tokens" to locale().text(bonus.tokens),
        "item-amount" to locale().text(bonus.itemAmount),
        "item" to tr("gui.contracts.rewards.items.${bonus.itemPreset}", player),
    )

    private fun perkBody(player: Player, number: Int, perk: PerkDefinition, snapshot: RankPlayerSnapshot): Component {
        val state = when {
            perk.id in snapshot.activePerks -> "dialogs.perks.state-active"
            snapshot.mastery.getValue(perk.path).ordinal < perk.requiredMastery.ordinal -> "dialogs.perks.state-locked"
            else -> "dialogs.perks.state-available"
        }
        return tr(
            "dialogs.perks.card",
            player,
            mapOf(
                "number" to locale().text(number),
                "perk" to tr(perk.nameKey, player),
                "description" to tr(perk.descriptionKey, player),
                "required" to tr(perk.requiredMastery.localeKey(), player),
                "state" to tr(state, player),
            ),
        )
    }

    private fun recommendation(player: Player, snapshot: RankPlayerSnapshot): Component = when (val next = snapshot.evaluation?.recommendation) {
        is NextStep.ActiveMinutes -> tr("gui.recommendation.active", player, mapOf("remaining" to locale().renderDurationMinutes(next.remaining, player)))
        is NextStep.PathGoal -> tr("gui.recommendation.path", player, mapOf(
            "path" to tr(next.path.nameKey(), player),
            "remaining" to locale().text(next.remaining),
        ))
        null -> tr(if (snapshot.evaluation?.eligibility == RankEligibility.TOP_RANK) "gui.recommendation.top" else "gui.recommendation.ready", player)
    }

    private fun pathStateKey(snapshot: RankPlayerSnapshot, goal: GoalProgress?, path: SpecializationPath): String = when {
        !snapshot.availability.isAvailable(path) -> "dialogs.paths.state-unavailable"
        snapshot.profile.selectedFocus == path -> "dialogs.paths.state-focus"
        goal?.state == GoalState.COMPLETE -> "dialogs.paths.state-complete"
        else -> "dialogs.paths.state-progress"
    }

    private fun body(key: String, player: Player, values: Map<String, Component> = emptyMap()): PaperDialogBody =
        PaperDialogBody(tr(key, player, values), 520)

    private fun back(id: String, player: Player, action: () -> Unit): PaperDialogButton =
        button("back_$id", "dialogs.common.back", player, onClick = action)

    private fun button(
        id: String,
        key: String,
        player: Player,
        tooltipKey: String? = null,
        close: Boolean = false,
        onClick: () -> Unit,
    ): PaperDialogButton = button(
        id,
        tr(key, player),
        tooltipKey?.let { tr(it, player) } ?: Component.empty(),
        close,
        onClick,
    )

    private fun button(
        id: String,
        label: Component,
        tooltip: Component = Component.empty(),
        close: Boolean = false,
        onClick: () -> Unit,
    ): PaperDialogButton = PaperDialogButton(
        id = PaperDialogActionId.of(id),
        label = label,
        tooltip = tooltip,
        closeDialogBeforeAction = close,
        onClick = {
            markNavigation(it.player)
            onClick()
        },
    )

    private fun tr(key: String, player: Player, values: Map<String, Component> = emptyMap()): Component =
        locale().render(key, player, values)

    private fun joined(vararg lines: Component): Component = Component.join(
        JoinConfiguration.separator(Component.newline()),
        lines.asList(),
    )

    private fun joinedSpaced(vararg lines: Component): Component = Component.join(
        JoinConfiguration.separator(Component.text("\n\n")),
        lines.asList(),
    )

    private fun navigationMarker(): Component = Component.text("›").color(TextColor.color(157, 176, 186))

    private fun markNavigation(player: Player): Long = serial.incrementAndGet().also { navigation[player.uniqueId] = it }

    private fun current(player: Player, token: Long): Boolean =
        player.isOnline && navigation[player.uniqueId] == token

    private fun RankPlayerSnapshot.contractContext(): ContractPlayerContext = ContractPlayerContext(
        rankOrder = evaluation?.currentRank?.order ?: 1,
        selectedFocus = profile.selectedFocus,
        nearestIncompletePath = (evaluation?.recommendation as? NextStep.PathGoal)?.path,
        progress = profile.progress,
        availability = availability,
        activePerks = activePerks,
    )

    private fun SpecializationPath.nameKey() = "paths.${name.lowercase()}.name"
    private fun SpecializationPath.summaryKey() = "paths.${name.lowercase()}.summary"
    private fun SpecializationPath.detailsKey() = "paths.${name.lowercase()}.details"
    private fun SpecializationPath.sourcesKey() = "paths.${name.lowercase()}.sources"
    private fun MasteryLevel.localeKey() = "mastery.${name.lowercase()}"

}

private const val PATH_PROGRESS_BAR_SIZE = 16

internal fun singleLineLabel(prefix: Component, name: Component): Component =
    prefix.append(Component.space()).append(name)

internal fun progressPercent(current: Long, required: Long): Int = when {
    required <= 0L -> 100
    else -> ((current.coerceIn(0L, required).toDouble() / required.toDouble()) * 100.0).toInt()
}

internal fun progressBar(current: Long, required: Long): Component {
    val filled = if (required <= 0L) PATH_PROGRESS_BAR_SIZE else
        ((current.coerceIn(0L, required).toDouble() / required.toDouble()) * PATH_PROGRESS_BAR_SIZE).toInt()
    val empty = PATH_PROGRESS_BAR_SIZE - filled
    return Component.text("■".repeat(filled)).color(TextColor.color(43, 186, 67))
        .append(Component.text("□".repeat(empty)).color(TextColor.color(140, 140, 140)))
}
