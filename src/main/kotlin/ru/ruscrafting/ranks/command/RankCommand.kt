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
import ru.ruscrafting.ranks.analytics.AnalyticsService
import ru.ruscrafting.ranks.analytics.TelemetryHealthSnapshot
import ru.ruscrafting.ranks.api.RankProgressApi
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.config.PromotionMode
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
import ru.ruscrafting.ranks.promotion.PromotionResult
import ru.ruscrafting.ranks.promotion.PromotionService
import ru.ruscrafting.ranks.rankstate.RankState
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
    private val promotions: PromotionService,
    private val menu: RankPassportMenu,
    private val contractMenu: ContractMenu,
    private val perkMenu: PerkMenu,
    private val weeklyKitMenu: WeeklyKitMenu,
    private val analyticsMenu: AnalyticsMenu,
    private val analytics: AnalyticsService,
    private val analyticsHealth: () -> TelemetryHealthSnapshot,
    private val tasks: LifecycleTaskScope,
    private val reload: () -> Result<Unit>,
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (command.name.equals("rankup", ignoreCase = true)) {
            val player = sender as? Player ?: return playerOnly(sender)
            promote(player)
            return true
        }
        if (args.isEmpty()) {
            val player = sender as? Player ?: return playerOnly(sender)
            menu.open(player)
            return true
        }
        when (args[0].lowercase()) {
            "why" -> withPlayerSnapshot(sender, ::sendWhy)
            "benefits" -> withPlayerSnapshot(sender, ::sendBenefits)
            "focus" -> focus(sender, args.getOrNull(1))
            "contracts" -> withPlayer(sender, contractMenu::open)
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
            args.size == 1 -> listOf("why", "benefits", "focus", "contracts", "perks", "kit", "admin", "help")
            args.size == 2 && args[0].equals("focus", true) -> SpecializationPath.entries.map { it.name.lowercase() }
            args.size == 2 && args[0].equals("admin", true) -> listOf("inspect", "grant", "simulate", "analytics", "reload")
            args.size == 3 && args[0].equals("admin", true) && args[1].equals("analytics", true) -> listOf("7", "14", "30")
            args.size == 3 && args[0].equals("admin", true) && args[1] != "reload" -> server.onlinePlayers.map(Player::getName)
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
                "remaining" to locale().text((evaluation.recommendation as NextStep.ActiveMinutes).remaining),
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
            player.sendMessage(locale().render("commands.benefits.entry", player, mapOf("benefit" to locale().render(benefit, player))))
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
                reload().fold(
                    onSuccess = { sender.sendMessage(locale().render("commands.reload.success", sender)) },
                    onFailure = { sender.sendMessage(locale().render("commands.reload.failure", sender, mapOf("reason" to locale().text(it.message)))) },
                )
            }
            "inspect", "simulate" -> inspect(sender, args, simulate = args.first().equals("simulate", true))
            "grant" -> grant(sender, args)
            "analytics" -> analytics(sender, args.getOrNull(1))
            else -> sender.sendMessage(locale().render("commands.help", sender))
        }
    }

    private fun analytics(sender: CommandSender, rawDays: String?) {
        if (!sender.hasPermission("arcranks.admin.analytics")) return noPermission(sender)
        val days = rawDays?.toIntOrNull() ?: 7
        if (days !in setOf(7, 14, 30)) {
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
