package ru.ruscrafting.ranks.quest

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
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
        "quest_line_1", "quest_line_2", "quest_line_3" -> lines.getOrElse(key.last().digitToInt() - 1) { "" }
        else -> null
    }

    companion object {
        fun emptyPlaceholder(key: String): String? = when (key) {
            "quest_active" -> "false"
            "quest_context" -> "none"
            "quest_compact", "quest_line_1", "quest_line_2", "quest_line_3" -> ""
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
            return QuestHudSnapshot(listOf(
                render(if (view.stepIndex != null && state.quest.plan?.mode == QuestMode.CHAIN) "daily.hud.chain" else "daily.hud.title"),
                render(if (view.stepIndex != null) "daily.hud.step" else "daily.hud.counter"),
                render("daily.next.$category"),
            ), context, render("daily.hud.compact"), day)
        }
    }
}
