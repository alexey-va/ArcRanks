package ru.ruscrafting.ranks.quest

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.ruscrafting.ranks.text.RankLocale

enum class QuestDisplayMode { SCOREBOARD, ACTIONBAR, OFF;
    fun next(): QuestDisplayMode = entries[(ordinal + 1) % entries.size]
    companion object {
        fun fromStored(value: String?): QuestDisplayMode = entries.firstOrNull { it.name == value } ?: SCOREBOARD
    }
}

/** Immutable strings only: PlaceholderAPI/TAB may read these from another thread. */
data class QuestHudSnapshot(
    val lines: List<String>,
    val context: String,
    val compact: String,
    val day: java.time.LocalDate,
    val active: Boolean = true,
    val boardHeader: String = "",
    val boardLines: List<String> = emptyList(),
    val boardRewards: List<String> = emptyList(),
    val reward: String = "",
) {
    fun placeholder(key: String): String? = when (key) {
        "quest_active" -> active.toString()
        "quest_context" -> context
        "quest_compact" -> compact
        "quest_reward" -> reward
        "quest_board_reward_1", "quest_board_reward_2", "quest_board_reward_3" -> boardRewards.getOrElse(key.last().digitToInt() - 1) { "" }
        "quest_line_1", "quest_line_2", "quest_line_3", "quest_line_4" -> lines.getOrElse(key.last().digitToInt() - 1) { "" }
        "quest_board_header" -> boardHeader
        "quest_board_1", "quest_board_2", "quest_board_3" -> boardLines.getOrElse(key.last().digitToInt() - 1) { "" }
        else -> null
    }

    companion object {
        fun emptyPlaceholder(key: String): String? = when (key) {
            "quest_active" -> "false"
            "quest_context" -> "none"
            "quest_compact", "quest_line_1", "quest_line_2", "quest_line_3", "quest_line_4", "quest_board_header",
            "quest_board_1", "quest_board_2", "quest_board_3", "quest_reward",
            "quest_board_reward_1", "quest_board_reward_2", "quest_board_reward_3" -> ""
            else -> null
        }

        fun render(state: DailyQuestProgress, player: Player, text: RankLocale, day: java.time.LocalDate): QuestHudSnapshot =
            render(DailyQuestBoard(day, listOf(state)), state.quest.id, player, text)

        fun render(board: DailyQuestBoard, pinnedQuestId: String?, player: Player, text: RankLocale): QuestHudSnapshot {
            val ordered = board.quests.asSequence()
                .filterNot { it.completed }
                .distinctBy { it.quest.id }
                .toList()
                .sortedWith(compareByDescending<DailyQuestProgress> { it.quest.tokens > 0 }
                    .thenByDescending { it.quest.id == pinnedQuestId })
            val pinned = pinnedQuestId?.let { id -> ordered.firstOrNull { it.quest.id == id } }
            val featured = ordered.firstOrNull { it.quest.tokens > 0 } ?: pinned
            val legacy = featured?.let { renderLegacy(it, player, text, board.day) }
                ?: QuestHudSnapshot(emptyList(), "none", "", board.day, active = false)
            val serializer = LegacyComponentSerializer.legacySection()
            val header = ordered.firstOrNull()?.let { serializer.serialize(text.render("daily.hud.board-section", player)) } ?: ""
            val rows = java.util.Collections.unmodifiableList(ordered.take(3).map { renderBoardRow(it, player, text) })
            return legacy.copy(
                active = featured != null,
                boardHeader = header,
                boardLines = rows,
                boardRewards = java.util.Collections.unmodifiableList(ordered.take(3).map { renderReward(it, player, text) }),
                reward = featured?.let { renderReward(it, player, text) } ?: "",
            )
        }

        private fun renderLegacy(state: DailyQuestProgress, player: Player, text: RankLocale, day: java.time.LocalDate): QuestHudSnapshot {
            val view = QuestTrackingView.of(state)
            val serializer = LegacyComponentSerializer.legacySection()
            val values = values(state, view, player, text)
            val category = DailyQuestHints.category(view.objective) ?: "active"
            fun render(key: String) = serializer.serialize(text.render(key, player, values))
            val context = when (category) { "farm-job", "lumber-job", "mine-job" -> "farm"; "dungeon" -> "dungeon"; else -> "normal" }
            val goal = PlainTextComponentSerializer.plainText().serialize(values.getValue("step-name"))
            val parts = questGoalParts(goal, "${view.value}/${view.target}")
            fun goalLine(key: String, part: String) = serializer.serialize(text.render(
                key,
                player,
                values + ("goal" to text.text(part)),
            ))
            val lines = if (parts.size == 1) listOf(
                render("daily.hud.section"),
                goalLine("daily.hud.goal", parts.single()),
                render("daily.hud.info"),
                "",
            ) else listOf(
                render("daily.hud.section"),
                goalLine("daily.hud.goal-part", parts.first()),
                goalLine("daily.hud.goal", parts.last()),
                render("daily.hud.info"),
            )
            return QuestHudSnapshot(lines, context, render(if (state.quest.tokens > 0) "daily.hud.compact-rare" else "daily.hud.compact"), day)
        }

        private fun renderBoardRow(state: DailyQuestProgress, player: Player, text: RankLocale): String {
            val view = QuestTrackingView.of(state)
            val serializer = LegacyComponentSerializer.legacySection()
            val values = values(state, view, player, text)
            val key = (if (view.stepTextId == null) "daily.hud.board-goal" else "daily.hud.board-step") +
                (if (state.quest.tokens > 0) "-rare" else "")
            return serializer.serialize(text.render(key, player, values))
        }

        private fun renderReward(state: DailyQuestProgress, player: Player, text: RankLocale): String =
            LegacyComponentSerializer.legacySection().serialize(text.render(
                if (state.quest.tokens > 0) "daily.hud.reward-rare" else "daily.hud.reward",
                player,
                mapOf("money" to text.text(state.quest.money), "tokens" to text.text(state.quest.tokens)),
            ))

        private fun values(state: DailyQuestProgress, view: QuestTrackingView, player: Player, text: RankLocale): Map<String, net.kyori.adventure.text.Component> = mapOf(
            "quest-name" to hudName(view.textId, view, player, text),
            "step-name" to hudName(view.stepTextId ?: view.textId, view, player, text),
            "value" to text.text(view.value), "target" to text.text(view.target),
            "stage" to text.text(view.stepIndex?.plus(1) ?: 1),
            "stages" to text.text(state.quest.plan?.steps?.size ?: 1),
        )

        private fun hudName(id: String, view: QuestTrackingView, player: Player, text: RankLocale): net.kyori.adventure.text.Component {
            val name = PlainTextComponentSerializer.plainText().serialize(text.render("daily.$id.short-name", player))
            return text.text(questHudLabel(name, "${view.value}/${view.target}"))
        }
    }
}

/** Reserves space for the complete counter; custom long labels use a word-boundary fallback. */
internal fun questHudLabel(name: String, progress: String, maxCharacters: Int = 27): String {
    val normalized = name.trim().replace(Regex("\\s+"), " ")
    val budget = (maxCharacters - progress.length - 1).coerceAtLeast(1)
    if (normalized.length <= budget) return normalized
    if (budget == 1) return "…"
    val prefix = normalized.take(budget - 1)
    val boundary = prefix.lastIndexOf(' ').takeIf { it >= prefix.length / 2 }
    return (boundary?.let(prefix::take) ?: prefix).trimEnd() + "…"
}

/** Keeps the counter on the final scoreboard line and never truncates the localized goal. */
internal fun questGoalParts(goal: String, progress: String, maxCharacters: Int = 27): List<String> {
    val normalized = goal.trim().replace(Regex("\\s+"), " ")
    if ("$normalized $progress".length <= maxCharacters) return listOf(normalized)
    val words = normalized.split(' ')
    val split = (1 until words.size).minByOrNull { index ->
        val first = words.take(index).joinToString(" ")
        val second = words.drop(index).joinToString(" ")
        if (first.length > maxCharacters || "$second $progress".length > maxCharacters) Int.MAX_VALUE
        else kotlin.math.abs(first.length - "$second $progress".length)
    }
    if (split != null) {
        val first = words.take(split).joinToString(" ")
        val second = words.drop(split).joinToString(" ")
        if (first.length <= maxCharacters && "$second $progress".length <= maxCharacters) return listOf(first, second)
    }
    val boundary = normalized.lastIndexOf(' ', startIndex = maxCharacters.coerceAtMost(normalized.lastIndex))
        .takeIf { it > 0 } ?: maxCharacters.coerceAtMost(normalized.length)
    return listOf(normalized.take(boundary).trim(), normalized.drop(boundary).trim())
}
