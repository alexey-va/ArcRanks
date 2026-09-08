package ru.ruscrafting.ranks.quest

import ru.ruscrafting.ranks.domain.ProgressMetric
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Optional daily objectives with separately typed path, coin and token rewards. */
data class DailyQuest(val id: String, val metric: ProgressMetric, val target: Long, val bonus: Long, val material: String, val textId: String = id, val money: Long = 50, val tokens: Long = 0, val tokenCurrency: String = "tokens", val objective: String = "path.$textId",
    val plan: QuestPlan? = null,
    val family: String = objective.substringBefore(':'),
    val minRank: String? = null,
    val availability: String? = null,
    val once: Boolean = false,
    val scaleTarget: Boolean = true,
    val rareEligible: Boolean = true,
    val challengeSuffix: String? = null,
    val challengePercent: Int = 0,
) {
    init {
        require(family.matches(Regex("[a-z0-9_.-]{1,40}")))
        require(challengePercent in 0..100)
        require(challengeSuffix == null || challengeSuffix.matches(Regex("[a-z0-9_-]{1,32}")))
        require(availability == null || availability.matches(Regex("[a-z0-9_.:-]{1,96}")))
        require(id.matches(Regex("[a-z0-9_-]{1,32}")))
        require(objective.matches(Regex("[a-z0-9_.:-]{1,96}")))
        require(money in 0..1_000_000 && tokens in 0..1000)
        require(tokenCurrency.matches(Regex("[A-Za-z0-9_-]{1,16}")))
        require(target in 1..1_000_000_000 && bonus in 1..1_000_000_000)
    }
    fun matchesObjective(eventObjective: String): Boolean =
        objective == eventObjective || eventObjective.startsWith("$objective:")

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

enum class DailyRewardState { PENDING, GRANTED, RECOVERY }

data class DailyQuestProgress(
    val quest: DailyQuest, val value: Long, val rewardState: DailyRewardState? = null,
    val stepValues: List<Long> = quest.plan?.steps?.map { 0L }.orEmpty(),
    val challengeValue: Long = 0,
) {
    val completed: Boolean get() = value >= quest.target
}

data class DailyQuestBoard(
    val day: LocalDate, val quests: List<DailyQuestProgress>,
    val replacementsLeft: Int = 0,
    val unavailableQuestIds: Set<String> = emptySet(),
)

enum class QuestReplaceResult { REPLACED, STALE, COMPLETED, LIMIT, UNAVAILABLE }
