package ru.ruscrafting.ranks.quest

import ru.ruscrafting.ranks.domain.ProgressMetric
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Three optional daily goals; rewards use rank-path units, never money or tokens. */
data class DailyQuest(val id: String, val metric: ProgressMetric, val target: Long, val bonus: Long, val material: String) {
    fun advance(current: Long, delta: Long): Long {
        require(current in 0..target && delta > 0)
        return current + minOf(delta, target - current)
    }

    fun completionBonus(previous: Long, next: Long): Long =
        if (previous < target && next >= target) bonus else 0

    companion object {
        val ALL = listOf(
            DailyQuest("farming", ProgressMetric.CROPS_HARVESTED, 100, 10, "WHEAT"),
            DailyQuest("industry", ProgressMetric.PRODUCTION_ACTIONS, 100, 10, "CRAFTING_TABLE"),
            DailyQuest("exploration", ProgressMetric.TRAVEL_BLOCKS, 2000, 200, "COMPASS"),
        )
        fun day(instant: Instant): LocalDate = LocalDate.ofInstant(instant, ZoneOffset.UTC)
    }
}

data class DailyQuestProgress(val quest: DailyQuest, val value: Long) {
    val completed: Boolean get() = value >= quest.target
}

data class DailyQuestBoard(val day: LocalDate, val quests: List<DailyQuestProgress>)
