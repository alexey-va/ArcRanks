package ru.ruscrafting.ranks.quest

import ru.arc.sql.SqlRuntime
import ru.ruscrafting.ranks.domain.ProgressMetric
import java.sql.Connection
import java.sql.Date
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Feature state shares the existing SQL runtime and the gameplay-progress transaction.
 * Row locks serialize concurrent backends. Completion and its rank bonus commit together.
 * One row per player/goal is retained; a new UTC day replaces only daily state.
 */
class MySqlDailyQuestRepository(private val runtime: SqlRuntime, private val clock: Clock = Clock.systemUTC()) {
    fun board(playerId: UUID): CompletableFuture<DailyQuestBoard> = runtime.executor.read { connection ->
        val day = DailyQuest.day(clock.instant())
        val values = mutableMapOf<String, Long>()
        connection.prepareStatement(
            "SELECT quest_id, value FROM arc_ranks_daily_quest WHERE player_uuid = ? AND quest_day = ?",
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.setDate(2, Date.valueOf(day))
            statement.executeQuery().use { rows ->
                while (rows.next()) values[rows.getString("quest_id")] = rows.getLong("value")
            }
        }
        DailyQuestBoard(day, DailyQuest.ALL.map { DailyQuestProgress(it, values[it.id] ?: 0) })
    }

    /** Called only for locally collected gameplay, never admin grants or contract rewards.
     * Day is defined by persistence time, matching the existing coalesced progress pipeline.
     */
    fun advance(connection: Connection, playerId: UUID, metric: ProgressMetric, delta: Long): Long {
        val quest = DailyQuest.ALL.firstOrNull { it.metric == metric } ?: return 0
        val day = DailyQuest.day(clock.instant())
        connection.prepareStatement(
            "INSERT IGNORE INTO arc_ranks_daily_quest (player_uuid, quest_id, quest_day, value) VALUES (?, ?, ?, 0)",
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.setString(2, quest.id)
            statement.setDate(3, Date.valueOf(day))
            statement.executeUpdate()
        }
        val previous = connection.prepareStatement(
            "SELECT quest_day, value FROM arc_ranks_daily_quest WHERE player_uuid = ? AND quest_id = ? FOR UPDATE",
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.setString(2, quest.id)
            statement.executeQuery().use { rows ->
                check(rows.next())
                val storedDay = rows.getDate("quest_day").toLocalDate()
                // A lagging backend clock must never roll a newer day backwards.
                if (storedDay > day) return 0
                if (storedDay == day) rows.getLong("value").coerceIn(0, quest.target) else 0L
            }
        }
        if (previous == quest.target) return 0
        val next = quest.advance(previous, delta)
        connection.prepareStatement(
            "UPDATE arc_ranks_daily_quest SET quest_day = ?, value = ? WHERE player_uuid = ? AND quest_id = ?",
        ).use { statement ->
            statement.setDate(1, Date.valueOf(day))
            statement.setLong(2, next)
            statement.setString(3, playerId.toString())
            statement.setString(4, quest.id)
            check(statement.executeUpdate() == 1)
        }
        return quest.completionBonus(previous, next)
    }
}
