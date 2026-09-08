package ru.ruscrafting.ranks.command

import net.kyori.adventure.text.Component
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.ruscrafting.ranks.admin.AdminProgressAdvanceResult
import ru.ruscrafting.ranks.admin.AdminProgressService
import ru.ruscrafting.ranks.analytics.AnalyticsService
import ru.ruscrafting.ranks.analytics.TelemetryHealthSnapshot
import ru.ruscrafting.ranks.api.RankProgressApi
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.config.PromotionMode
import ru.ruscrafting.ranks.contract.ContractAdminCompleteResult
import ru.ruscrafting.ranks.contract.ContractService
import ru.ruscrafting.ranks.domain.NextStep
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.domain.RankCatalog
import ru.ruscrafting.ranks.domain.RankEligibility
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.gui.RankPassportMenu
import ru.ruscrafting.ranks.gui.ContractMenu
import ru.ruscrafting.ranks.gui.PerkMenu
import ru.ruscrafting.ranks.gui.AnalyticsMenu
import ru.ruscrafting.ranks.gui.WeeklyKitMenu
import ru.ruscrafting.ranks.dialog.RankDialogController
import ru.ruscrafting.ranks.kit.WeeklyKitAdminResetResult
import ru.ruscrafting.ranks.kit.WeeklyKitService
import ru.ruscrafting.ranks.promotion.PromotionResult
import ru.ruscrafting.ranks.promotion.PromotionService
import ru.ruscrafting.ranks.rankstate.RankState
import ru.ruscrafting.ranks.reload.ArcRanksReloadResult
import ru.ruscrafting.ranks.service.RankPlayerService
import ru.ruscrafting.ranks.service.RankPlayerSnapshot
import ru.ruscrafting.ranks.text.RankLocale
import ru.ruscrafting.ranks.storage.ExternalProgressResult

class RankCommand(
    private val server: Server,
    private val settings: () -> ArcRanksSettings,
    private val catalog: () -> RankCatalog,
    private val locale: () -> RankLocale,
    private val players: RankPlayerService,
    private val progressApi: RankProgressApi,
    private val adminProgress: AdminProgressService,
    private val promotions: PromotionService,
    private val menu: RankPassportMenu,
    private val contractMenu: ContractMenu,
    private val contracts: ContractService,
    private val perkMenu: PerkMenu,
    private val weeklyKitMenu: WeeklyKitMenu,
    private val weeklyKits: WeeklyKitService,
    private val analyticsMenu: AnalyticsMenu,
    private val analytics: AnalyticsService,
    private val analyticsHealth: () -> TelemetryHealthSnapshot,
    private val tasks: LifecycleTaskScope,
    private val reload: () -> ArcRanksReloadResult,
    private val dialogs: RankDialogController,
    private val openDailyQuests: (Player) -> Unit = {},
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (command.name.equals("rankup", ignoreCase = true)) {
            val player = sender as? Player ?: return playerOnly(sender)
            promote(player)
            return true
        }
        if (args.isEmpty()) {
            val player = sender as? Player ?: return playerOnly(sender)
            dialogs.beginFlowAndOpen(player)
            return true
        }
        when (args[0].lowercase()) {
            "dialog" -> withPlayer(sender, dialogs::beginFlowAndOpen)
            "chest" -> withPlayer(sender, menu::open)
            "why" -> withPlayerSnapshot(sender, ::sendWhy)
            "benefits" -> withPlayerSnapshot(sender, ::sendBenefits)
            "focus" -> focus(sender, args.getOrNull(1))
            "quests", "daily", "contracts" -> withPlayer(sender, openDailyQuests)
            "legacy-contracts" -> withPlayer(sender, contractMenu::open)
            "perks" -> withPlayer(sender, perkMenu::open)
            "kit", "weekly" -> withPlayer(sender, weeklyKitMenu::open)
            "help" -> sender.sendMessage(locale().render("commands.help", sender))
            "admin" -> admin(sender, args.drop(1))
            else -> sender.sendMessage(locale().render("commands.help", sender))
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val options = when {
            command.name.equals("rankup", true) -> emptyList()
            args.size == 1 -> listOf("quests", "chest", "dialog", "why", "benefits", "focus", "perks", "kit", "admin", "help")
            args.size == 2 && args[0].equals("focus", true) -> SpecializationPath.entries.map { it.name.lowercase() }
            args.size == 2 && args[0].equals("admin", true) ->
                listOf("inspect", "grant", "advance", "simulate", "analytics", "contract", "kit", "reload")
            args.size == 3 && args[0].equals("admin", true) && args[1].equals("advance", true) ->
                server.onlinePlayers.map(Player::getName)
            args.size == 3 && args[0].equals("admin", true) && args[1].equals("contract", true) -> listOf("complete")
            args.size == 4 && args[0].equals("admin", true) && args[1].equals("contract", true) && args[2].equals("complete", true) ->
                server.onlinePlayers.map(Player::getName)
            args.size == 3 && args[0].equals("admin", true) && args[1].equals("kit", true) -> listOf("reset")
            args.size == 4 && args[0].equals("admin", true) && args[1].equals("kit", true) && args[2].equals("reset", true) ->
                server.onlinePlayers.map(Player::getName)
            args.size == 3 && args[0].equals("admin", true) && args[1].equals("analytics", true) ->
                settings().analytics.windows.map(Int::toString)
            args.size == 3 && args[0].equals("admin", true) && args[1] !in setOf("reload", "contract", "kit") ->
                server.onlinePlayers.map(Player::getName)
            args.size == 4 && args[0].equals("admin", true) && args[1] == "grant" ->
                ProgressMetric.entries.filterNot { it == ProgressMetric.WEALTH_PEAK }.map { it.name.lowercase() }
            else -> emptyList()
        }
        return options.filter { it.startsWith(args.lastOrNull().orEmpty(), ignoreCase = true) }
    }

    private fun promote(player: Player) {
        if (settings().promotionMode == PromotionMode.SHADOW) {
            player.sendMessage(locale().render("commands.shadow-mode", player))
            return
        }
        promotions.promote(player.uniqueId).whenCompleteSync(tasks) { result, failure ->
            if (failure != null || result == null) player.sendMessage(locale().render("commands.storage-unavailable", player))
            else sendPromotionResult(player, result)
        }
    }

    private fun sendPromotionResult(player: Player, result: PromotionResult) {
        val key = when (result) {
            is PromotionResult.Promoted, is PromotionResult.Recovered -> "commands.promotion.success"
            is PromotionResult.NotEligible -> "commands.promotion.not-eligible"
            is PromotionResult.RankStateProblem -> rankStateMessage(result.state)
            PromotionResult.TopRank -> "commands.why.top"
            PromotionResult.Busy -> "commands.promotion.busy"
            PromotionResult.Retryable -> "commands.promotion.retryable"
        }
        val id = when (result) {
            is PromotionResult.Promoted -> result.rankId
            is PromotionResult.Recovered -> result.rankId
            else -> null
        }
        val values = id?.let { mapOf("rank" to locale().render(catalog().require(it).displayNameKey, player)) }.orEmpty()
        player.sendMessage(locale().render(key, player, values))
    }

    private fun withPlayerSnapshot(sender: CommandSender, action: (Player, RankPlayerSnapshot) -> Unit) {
        val player = sender as? Player
        if (player == null) {
            playerOnly(sender)
            return
        }
        sender.sendMessage(locale().render("commands.loading", sender))
        players.load(player.uniqueId).whenCompleteSync(tasks) { snapshot, failure ->
            if (failure != null || snapshot == null) sender.sendMessage(locale().render("commands.storage-unavailable", sender))
            else action(player, snapshot)
        }
    }

    private fun sendWhy(player: Player, snapshot: RankPlayerSnapshot) {
        val evaluation = snapshot.evaluation
        if (evaluation == null) {
            player.sendMessage(locale().render(rankStateMessage(snapshot.rankState), player))
            return
        }
        val nextName = evaluation.nextRank?.let { locale().render(it.displayNameKey, player) } ?: Component.empty()
        val (key, values) = when (evaluation.eligibility) {
            RankEligibility.READY -> "commands.why.ready" to mapOf("rank" to nextName)
            RankEligibility.CORE_INCOMPLETE -> "commands.why.core" to mapOf(
                "rank" to nextName,
                "remaining" to locale().renderDurationMinutes(
                    (evaluation.recommendation as NextStep.ActiveMinutes).remaining,
                    player,
                ),
            )
            RankEligibility.CHOICES_INCOMPLETE -> {
                val next = evaluation.recommendation as? NextStep.PathGoal
                "commands.why.paths" to mapOf(
                    "rank" to nextName,
                    "remaining" to locale().text(evaluation.requiredChoices - evaluation.completedChoices),
                    "path" to (next?.let { locale().render(it.path.nameKey(), player) } ?: Component.empty()),
                )
            }
            RankEligibility.INSUFFICIENT_AVAILABLE_PATHS -> "commands.why.unavailable" to mapOf(
                "available" to locale().text(evaluation.availableChoices),
                "required" to locale().text(evaluation.requiredChoices),
            )
            RankEligibility.TOP_RANK -> "commands.why.top" to emptyMap()
        }
        player.sendMessage(locale().render(key, player, values))
    }

    private fun sendBenefits(player: Player, snapshot: RankPlayerSnapshot) {
        val evaluation = snapshot.evaluation
        if (evaluation == null) {
            player.sendMessage(locale().render(rankStateMessage(snapshot.rankState), player))
            return
        }
        val rank = evaluation.nextRank ?: evaluation.currentRank
        player.sendMessage(locale().render("commands.benefits.header", player, mapOf("rank" to locale().render(rank.displayNameKey, player))))
        rank.benefitKeys.forEach { benefit ->
            player.sendMessage(locale().render(benefit, player))
        }
    }

    private fun focus(sender: CommandSender, rawPath: String?) {
        val player = sender as? Player
        if (player == null) {
            playerOnly(sender)
            return
        }
        val path = SpecializationPath.entries.firstOrNull { it.name.equals(rawPath, ignoreCase = true) }
        if (path == null) {
            sender.sendMessage(locale().render("commands.focus.unknown", sender))
            return
        }
        players.selectFocus(player.uniqueId, path).whenCompleteSync(tasks) { _, failure ->
            if (failure != null) sender.sendMessage(locale().render("commands.storage-unavailable", sender))
            else sender.sendMessage(locale().render("commands.focus.selected", sender, mapOf("path" to locale().render(path.nameKey(), sender))))
        }
    }

    private fun admin(sender: CommandSender, args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            "reload" -> {
                if (!sender.hasPermission("arcranks.admin.reload")) return noPermission(sender)
                when (val result = reload()) {
                    is ArcRanksReloadResult.Applied -> sender.sendMessage(locale().render("commands.reload.success", sender))
                    is ArcRanksReloadResult.NoChanges -> sender.sendMessage(locale().render("commands.reload.no-changes", sender))
                    is ArcRanksReloadResult.RestartRequired -> sender.sendMessage(
                        locale().render(
                            "commands.reload.restart-required",
                            sender,
                            mapOf("paths" to locale().text(result.paths.sorted().joinToString(", "))),
                        ),
                    )
                    ArcRanksReloadResult.Busy -> sender.sendMessage(locale().render("commands.reload.busy", sender))
                    is ArcRanksReloadResult.Invalid -> sender.sendMessage(
                        locale().render("commands.reload.failure", sender, mapOf("reason" to locale().text(result.reason))),
                    )
                    is ArcRanksReloadResult.RolledBack -> sender.sendMessage(
                        locale().render("commands.reload.failure", sender, mapOf("reason" to locale().text(result.reason))),
                    )
                }
            }
            "inspect", "simulate" -> inspect(sender, args, simulate = args.first().equals("simulate", true))
            "grant" -> grant(sender, args)
            "advance" -> adminAdvance(sender, args.getOrNull(1))
            "analytics" -> analytics(sender, args.getOrNull(1))
            "contract" -> adminContract(sender, args)
            "kit" -> adminKit(sender, args)
            else -> sender.sendMessage(locale().render("commands.help", sender))
        }
    }

    private fun adminAdvance(sender: CommandSender, rawTarget: String?) {
        if (!sender.hasPermission(RankPassportMenu.ADMIN_GRANT_PERMISSION)) return noPermission(sender)
        val target = if (rawTarget == null) sender as? Player else server.getPlayerExact(rawTarget)
        if (target == null) {
            sender.sendMessage(locale().render("commands.admin.advance-invalid", sender))
            return
        }
        players.load(target.uniqueId).whenCompleteSync(tasks) { snapshot, loadFailure ->
            val evaluation = snapshot?.evaluation
            if (loadFailure != null || evaluation == null) {
                sender.sendMessage(locale().render("commands.storage-unavailable", sender))
                return@whenCompleteSync
            }
            adminProgress.advance(target.uniqueId, evaluation).whenCompleteSync(tasks) { result, failure ->
                val key = when {
                    failure != null || result == null -> "commands.storage-unavailable"
                    result is AdminProgressAdvanceResult.Applied -> "commands.admin.advance-applied"
                    result == AdminProgressAdvanceResult.Duplicate -> "commands.admin.advance-duplicate"
                    result == AdminProgressAdvanceResult.TopRank -> "commands.admin.advance-top"
                    else -> "commands.admin.advance-ready"
                }
                val values = buildMap {
                    put("player", locale().text(target.name))
                    if (result is AdminProgressAdvanceResult.Applied) put("amount", locale().text(result.amount))
                }
                sender.sendMessage(locale().render(key, sender, values))
            }
        }
    }

    private fun adminContract(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission(ContractMenu.ADMIN_CONTRACT_PERMISSION)) return noPermission(sender)
        if (!args.getOrNull(1).equals("complete", ignoreCase = true)) {
            sender.sendMessage(locale().render("commands.admin.contract-invalid", sender))
            return
        }
        val rawTarget = args.getOrNull(2)
        val target = if (rawTarget == null) sender as? Player else server.getPlayerExact(rawTarget)
        if (target == null) {
            sender.sendMessage(locale().render("commands.admin.contract-invalid", sender))
            return
        }
        val actor = (sender as? Player)?.uniqueId?.toString() ?: "CONSOLE"
        contracts.adminComplete(target.uniqueId, actor).whenCompleteSync(tasks) { result, failure ->
            val key = when {
                failure != null || result == null -> "commands.contracts.storage-unavailable"
                result is ContractAdminCompleteResult.Completed -> "commands.admin.contract-completed"
                result is ContractAdminCompleteResult.AlreadyReady -> "commands.admin.contract-already-ready"
                result is ContractAdminCompleteResult.NoActive -> "commands.admin.contract-no-active"
                result is ContractAdminCompleteResult.StorageUnavailable -> "commands.contracts.storage-unavailable"
                else -> "commands.contracts.storage-unavailable"
            }
            sender.sendMessage(locale().render(key, sender, mapOf("player" to locale().text(target.name))))
        }
    }

    private fun adminKit(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission(WeeklyKitMenu.ADMIN_PERMISSION)) return noPermission(sender)
        if (!args.getOrNull(1).equals("reset", ignoreCase = true)) {
            sender.sendMessage(locale().render("commands.admin.kit-invalid", sender))
            return
        }
        val rawTarget = args.getOrNull(2)
        val target = if (rawTarget == null) sender as? Player else server.getPlayerExact(rawTarget)
        if (target == null) {
            sender.sendMessage(locale().render("commands.admin.kit-invalid", sender))
            return
        }
        val actor = (sender as? Player)?.uniqueId?.toString() ?: "CONSOLE"
        weeklyKits.adminReset(target.uniqueId, actor).whenCompleteSync(tasks) { result, failure ->
            val key = when {
                failure != null || result == null || result == WeeklyKitAdminResetResult.StorageUnavailable ->
                    "commands.weekly-kit.admin-reset-storage-unavailable"
                result == WeeklyKitAdminResetResult.Reset -> "commands.weekly-kit.admin-reset"
                result == WeeklyKitAdminResetResult.DeliveryPending -> "commands.weekly-kit.admin-reset-delivering"
                else -> "commands.weekly-kit.admin-reset-not-claimed"
            }
            sender.sendMessage(locale().render(key, sender, mapOf("player" to locale().text(target.name))))
        }
    }

    private fun analytics(sender: CommandSender, rawDays: String?) {
        if (!sender.hasPermission("arcranks.admin.analytics")) return noPermission(sender)
        val days = rawDays?.toIntOrNull() ?: settings().analytics.defaultWindow
        if (days !in settings().analytics.windows) {
            sender.sendMessage(locale().render("commands.admin.analytics-invalid", sender))
            return
        }
        if (sender is Player) {
            analyticsMenu.open(sender, days)
            return
        }
        analytics.summary(days).whenCompleteSync(tasks) { summary, failure ->
            if (failure != null || summary == null) {
                sender.sendMessage(locale().render("commands.storage-unavailable", sender))
                return@whenCompleteSync
            }
            val health = analyticsHealth()
            sender.sendMessage(
                locale().render(
                    "commands.admin.analytics",
                    sender,
                    mapOf(
                        "days" to locale().text(days),
                        "players" to locale().text(summary.uniqueSeenPlayers),
                        "passport" to locale().text(summary.passportPlayers),
                        "accepted" to locale().text(summary.contractAcceptedPlayers),
                        "completed" to locale().text(summary.contractCompletedPlayers),
                        "perks" to locale().text(summary.perkSelectedPlayers),
                        "promotions" to locale().text(summary.promotionSuccesses),
                        "dropped" to locale().text(health.droppedMetricKeys + health.droppedPlayers),
                    ),
                ),
            )
        }
    }

    private fun withPlayer(sender: CommandSender, action: (Player) -> Unit) {
        val player = sender as? Player
        if (player == null) playerOnly(sender) else action(player)
    }

    private fun inspect(sender: CommandSender, args: List<String>, simulate: Boolean) {
        val permission = if (simulate) "arcranks.admin.simulate" else "arcranks.admin.inspect"
        if (!sender.hasPermission(permission)) return noPermission(sender)
        val target = args.getOrNull(1)?.let(server::getPlayerExact)
        if (target == null) {
            sender.sendMessage(locale().render("commands.help", sender))
            return
        }
        players.load(target.uniqueId).whenCompleteSync(tasks) { snapshot, failure ->
            if (failure != null || snapshot == null) {
                sender.sendMessage(locale().render("commands.storage-unavailable", sender))
                return@whenCompleteSync
            }
            val evaluation = snapshot.evaluation
            val current = evaluation?.currentRank?.let { locale().render(it.displayNameKey, sender) } ?: locale().text("?")
            val next = evaluation?.nextRank?.let { locale().render(it.displayNameKey, sender) } ?: locale().text("—")
            val key = if (simulate) "commands.admin.simulate" else "commands.admin.inspect"
            val values = if (simulate) mapOf(
                "player" to locale().text(target.name),
                "result" to locale().text(evaluation?.eligibility ?: snapshot.rankState),
            ) else mapOf("player" to locale().text(target.name), "rank" to current, "next" to next)
            sender.sendMessage(locale().render(key, sender, values))
        }
    }

    private fun grant(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("arcranks.admin.grant")) return noPermission(sender)
        val target = args.getOrNull(1)?.let(server::getPlayerExact)
        val metric = ProgressMetric.entries.firstOrNull { it.name.equals(args.getOrNull(2), ignoreCase = true) }
            ?.takeUnless { it == ProgressMetric.WEALTH_PEAK }
        val amount = args.getOrNull(3)?.toLongOrNull()?.takeIf { it > 0 }
        val eventId = args.getOrNull(4)
        if (target == null || metric == null || amount == null || eventId == null) {
            sender.sendMessage(locale().render("commands.admin.grant-invalid", sender))
            return
        }
        progressApi.record("admin", eventId, target.uniqueId, metric, amount).whenCompleteSync(tasks) { result, failure ->
            if (failure != null || result == null) {
                sender.sendMessage(locale().render("commands.storage-unavailable", sender))
            } else if (result == ExternalProgressResult.DUPLICATE) {
                sender.sendMessage(locale().render("commands.admin.grant-duplicate", sender, mapOf(
                    "event-id" to locale().text(eventId),
                )))
            } else {
                sender.sendMessage(locale().render("commands.admin.grant-success", sender, mapOf(
                    "player" to locale().text(target.name),
                    "metric" to locale().text(metric.name.lowercase()),
                    "amount" to locale().text(amount),
                )))
            }
        }
    }

    private fun rankStateMessage(state: RankState): String = when (state) {
        RankState.Missing -> "commands.rank-state.missing"
        is RankState.Conflict -> "commands.rank-state.conflict"
        is RankState.Unknown -> "commands.rank-state.unknown"
        is RankState.Exact -> "commands.promotion.retryable"
    }

    private fun playerOnly(sender: CommandSender): Boolean {
        sender.sendMessage(locale().render("commands.player-only", sender))
        return true
    }

    private fun noPermission(sender: CommandSender) {
        sender.sendMessage(locale().render("commands.no-permission", sender))
    }
}

private fun SpecializationPath.nameKey(): String = "paths.${name.lowercase()}.name"
