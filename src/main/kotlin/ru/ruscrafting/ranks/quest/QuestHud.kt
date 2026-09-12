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
data class QuestHudSnapshot(val lines: List<String>, val context: String, val compact: String, val day: java.time.LocalDate) {
    fun placeholder(key: String): String? = when (key) {
        "quest_active" -> "true"
        "quest_context" -> context
        "quest_compact" -> compact
        "quest_line_1", "quest_line_2", "quest_line_3", "quest_line_4" -> lines.getOrElse(key.last().digitToInt() - 1) { "" }
        else -> null
    }

    companion object {
        fun emptyPlaceholder(key: String): String? = when (key) {
            "quest_active" -> "false"
            "quest_context" -> "none"
            "quest_compact", "quest_line_1", "quest_line_2", "quest_line_3", "quest_line_4" -> ""
            else -> null
        }

        fun render(state: DailyQuestProgress, player: Player, text: RankLocale, day: java.time.LocalDate): QuestHudSnapshot {
            val view = QuestTrackingView.of(state)
            val serializer = LegacyComponentSerializer.legacySection()
            val values = mapOf(
                "quest-name" to text.render("daily.${view.textId}.name", player),
                "step-name" to text.render("daily.${view.stepTextId ?: view.textId}.name", player),
                "value" to text.text(view.value), "target" to text.text(view.target),
                "stage" to text.text(view.stepIndex?.plus(1) ?: 1),
                "stages" to text.text(state.quest.plan?.steps?.size ?: 1),
            )
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
            return QuestHudSnapshot(lines, context, render("daily.hud.compact"), day)
        }
    }
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
