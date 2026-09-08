package ru.ruscrafting.ranks.quest

import ru.arc.sql.SqlRuntime
import java.sql.Date
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture

data class TrackedQuest(val day: LocalDate, val questId: String)

data class QuestTrackingView(val textId: String, val value: Long, val target: Long, val stepTextId: String? = null,
    val objective: String = "", val stepIndex: Int? = null) {
    companion object {
        fun of(state: DailyQuestProgress): QuestTrackingView {
            val plan = state.quest.plan
            if (plan != null) {
                val unfinished = plan.steps.indices.filter { state.stepValues[it] < plan.steps[it].target }
                val step = if (plan.mode == QuestMode.CHAIN) unfinished.firstOrNull() else
                    unfinished.maxWithOrNull { left, right ->
                        (state.stepValues[left] * plan.steps[right].target).compareTo(state.stepValues[right] * plan.steps[left].target)
                    }
                if (step != null) return QuestTrackingView(state.quest.textId, state.stepValues[step],
                    plan.steps[step].target, plan.steps[step].textId, plan.steps[step].objective, step)
            }
            return QuestTrackingView(state.quest.textId, state.value, state.quest.target, objective = state.quest.objective)
        }
    }
}

interface QuestTrackingRepository {
    fun loadMode(playerId: UUID): CompletableFuture<QuestDisplayMode> = CompletableFuture.completedFuture(QuestDisplayMode.SCOREBOARD)
    fun saveMode(playerId: UUID, mode: QuestDisplayMode): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
    fun load(playerId: UUID): CompletableFuture<TrackedQuest?>
    fun save(playerId: UUID, selection: TrackedQuest): CompletableFuture<Unit>
    fun clear(playerId: UUID, expected: TrackedQuest): CompletableFuture<Unit>
}

class MySqlQuestTrackingRepository(private val runtime: SqlRuntime) : QuestTrackingRepository {
    override fun loadMode(playerId: UUID): CompletableFuture<QuestDisplayMode> = runtime.executor.read { connection ->
        connection.prepareStatement("SELECT display_mode FROM arc_ranks_quest_preferences WHERE player_uuid = ?").use {
            it.setString(1, playerId.toString())
            it.executeQuery().use { rows -> if (rows.next()) QuestDisplayMode.fromStored(rows.getString(1)) else QuestDisplayMode.SCOREBOARD }
        }
    }

    override fun saveMode(playerId: UUID, mode: QuestDisplayMode): CompletableFuture<Unit> = runtime.executor.write { connection ->
        connection.prepareStatement("""INSERT INTO arc_ranks_quest_preferences (player_uuid, display_mode) VALUES (?, ?)
            ON DUPLICATE KEY UPDATE display_mode = VALUES(display_mode)""").use {
            it.setString(1, playerId.toString()); it.setString(2, mode.name); it.executeUpdate()
        }
    }

    override fun load(playerId: UUID): CompletableFuture<TrackedQuest?> = runtime.executor.read { connection ->
        connection.prepareStatement("SELECT quest_day, quest_id FROM arc_ranks_quest_tracking WHERE player_uuid = ?").use {
            it.setString(1, playerId.toString())
            it.executeQuery().use { rows -> if (rows.next()) TrackedQuest(rows.getDate(1).toLocalDate(), rows.getString(2)) else null }
        }
    }

    override fun save(playerId: UUID, selection: TrackedQuest): CompletableFuture<Unit> = runtime.executor.write { connection ->
        connection.prepareStatement("""INSERT INTO arc_ranks_quest_tracking (player_uuid, quest_day, quest_id) VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE quest_day = VALUES(quest_day), quest_id = VALUES(quest_id)""").use {
            it.setString(1, playerId.toString()); it.setDate(2, Date.valueOf(selection.day)); it.setString(3, selection.questId); it.executeUpdate()
        }
    }

    override fun clear(playerId: UUID, expected: TrackedQuest): CompletableFuture<Unit> = runtime.executor.write { connection ->
        connection.prepareStatement("DELETE FROM arc_ranks_quest_tracking WHERE player_uuid = ? AND quest_day = ? AND quest_id = ?").use {
            it.setString(1, playerId.toString()); it.setDate(2, Date.valueOf(expected.day)); it.setString(3, expected.questId); it.executeUpdate()
        }
    }
}
