package ru.ruscrafting.ranks.command

import org.bukkit.Server
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.ruscrafting.ranks.quest.DailyQuestBoard
import ru.ruscrafting.ranks.quest.MySqlDailyQuestRepository
import ru.ruscrafting.ranks.text.RankLocale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

/** Operator tools use normal assignment/completion authority and never erase reward history. */
class QuestAdminCommand(
    private val server: Server,
    private val locale: () -> RankLocale,
    private val tasks: LifecycleTaskScope,
    private val quests: MySqlDailyQuestRepository,
    private val flush: (UUID) -> CompletableFuture<Unit>,
    private val refresh: (UUID) -> Unit,
    private val logger: Logger,
) {
    fun execute(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(locale().render("commands.no-permission", sender))
            return
        }
        val action = args.firstOrNull()?.lowercase()
        if (action !in ACTIONS || !validArguments(action, args)) return send(sender, "help")
        val player = server.getPlayerExact(args[1]) ?: return send(sender, "offline")
        val id = player.uniqueId
        flush(id).thenCompose {
            if (action == "assign") quests.board(id).thenApply<DailyQuestBoard?> { it } else quests.existingBoard(id)
        }.whenCompleteSync(tasks) { board, failure ->
            if (failure != null) return@whenCompleteSync finish(sender, player, args, null, failure)
            if (board == null) return@whenCompleteSync send(sender, "unassigned")
            if (action == "inspect" || action == "assign") {
                if (action == "assign") audit(sender, player, args, "ready")
                inspect(sender, player, board)
                return@whenCompleteSync
            }
            val raw = args.getOrNull(2)
            val quest = raw?.toIntOrNull()?.let { board.quests.getOrNull(it - 1) }
                ?: board.quests.firstOrNull { it.quest.id == raw }
            if (!(action == "reset" && raw == "all") && quest == null) {
                return@whenCompleteSync send(sender, "unknown")
            }
            audit(sender, player, args, "requested")
            val operation: CompletableFuture<*> = when (action) {
                "replace" -> quests.replace(id, board.day, checkNotNull(quest).quest.id)
                else -> quests.adminResetProgress(id, board.day, if (raw == "all") null else checkNotNull(quest).quest.id)
            }
            operation.whenCompleteSync(tasks) { result, error ->
                if (error == null) refresh(id)
                finish(sender, player, args, result?.toString(), error)
            }
        }
    }

    fun complete(sender: CommandSender, args: List<String>): List<String> {
        if (!sender.hasPermission(PERMISSION)) return emptyList()
        val values = when (args.size) {
            1 -> ACTIONS
            2 -> server.onlinePlayers.map(Player::getName)
            3 -> if (args[0] == "reset") listOf("all") + (1..21).map(Int::toString)
                else if (args[0] == "replace") (1..21).map(Int::toString) else emptyList()
            else -> emptyList()
        }
        return values.filter { it.startsWith(args.lastOrNull().orEmpty(), true) }
    }

    private fun inspect(sender: CommandSender, player: Player, board: DailyQuestBoard) {
        send(sender, "board", mapOf("player" to player.name, "day" to board.day, "remaining" to board.replacementsLeft))
        board.quests.forEachIndexed { index, state ->
            val quest = state.quest
            send(sender, "goal", mapOf("slot" to index + 1, "id" to quest.id, "value" to state.value, "target" to quest.target,
                "money" to quest.money, "tokens" to quest.tokens, "state" to (state.rewardState?.name ?: "ACTIVE"), "objective" to quest.objective))
            quest.plan?.steps?.forEachIndexed { step, condition ->
                send(sender, "step", mapOf("step" to step + 1, "objective" to condition.objective,
                    "value" to state.stepValues[step], "target" to condition.target))
            }
        }
    }

    private fun finish(sender: CommandSender, player: Player, args: List<String>, result: String?, failure: Throwable?) {
        audit(sender, player, args, if (failure == null) result.orEmpty() else "failed:${failure.javaClass.simpleName}")
        if (failure != null) sender.sendMessage(locale().render("commands.storage-unavailable", sender))
        else send(sender, "result", mapOf("player" to player.name, "action" to args[0], "result" to result))
    }

    private fun audit(sender: CommandSender, player: Player, args: List<String>, result: String) {
        logger.info("QUEST_ADMIN actor=${sender.name} player=${player.uniqueId} action=${args.joinToString(" ")} result=$result")
    }

    private fun send(sender: CommandSender, key: String, values: Map<String, Any?> = emptyMap()) {
        val text = locale()
        sender.sendMessage(text.render("commands.quest-admin.$key", sender, values.mapValues { text.text(it.value) }))
    }

    companion object {
        const val PERMISSION = "arcranks.admin.quests"
        private val ACTIONS = listOf("inspect", "assign", "replace", "reset")
        internal fun validArguments(action: String?, args: List<String>): Boolean = args.size == when (action) {
            "inspect", "assign" -> 2
            "replace", "reset" -> 3
            else -> -1
        }
    }
}
