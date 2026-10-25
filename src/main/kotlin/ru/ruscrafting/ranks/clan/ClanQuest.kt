package ru.ruscrafting.ranks.clan

import ru.ruscrafting.ranks.quest.QuestMode
import ru.ruscrafting.ranks.quest.QuestPlan
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.UUID

object ClanQuestCycle {
    fun week(instant: Instant): LocalDate = LocalDate.ofInstant(instant, ZoneOffset.UTC)
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun validate(week: LocalDate) {
        require(week.dayOfWeek == DayOfWeek.MONDAY) { "Clan quest week must start on Monday UTC" }
    }
}

/** Only internal, nonredeemable renown is awarded. No currency, item or rank-path reward exists here. */
data class ClanQuestDefinition(
    val id: String,
    val textId: String,
    val plan: QuestPlan,
    val material: String,
    val minContributors: Int = 2,
    val capPercent: Int = 60,
    val renown: Long = 1,
) {
    init {
        require(id.matches(Regex("[a-z0-9_-]{1,32}"))) { "Unsafe clan quest id" }
        require(textId.matches(Regex("[a-z0-9_-]{1,32}"))) { "Unsafe clan quest text id" }
        require(material.matches(Regex("[A-Z0-9_]{1,64}"))) { "Unsafe clan quest material" }
        require(plan.mode == QuestMode.ALL) { "Clan quests must use ALL plans" }
        require(minContributors == 2) { "Clan quests require two contributors" }
        require(capPercent in 1..60) { "A member may contribute at most 60 percent of each step" }
        require(renown == 1L) { "Clan projects award exactly one nonredeemable renown" }
        require(plan.steps.all { step ->
            step.objective in GROUP_OBJECTIVES || step.objective == "decorate" ||
                listOf("harvest:", "craft:", "smelt:", "build:").any(step.objective::startsWith)
        }) { "Unsupported clan quest objective" }
        require(plan.steps.none { it.objective in GROUP_OBJECTIVES } || plan.steps.all { it.objective in GROUP_OBJECTIVES }) {
            "A grouped clan project may contain only group objectives"
        }
        require(groupActivity || plan.steps.all { it.target >= 2 }) {
            "Numeric clan steps need targets of at least two so the contribution cap remains achievable"
        }
    }

    val groupActivity: Boolean get() = plan.steps.all { it.objective in GROUP_OBJECTIVES }

    fun contributionCap(stepIndex: Int): Long =
        (plan.steps[stepIndex].target * capPercent / 100).coerceAtLeast(1)

    companion object {
        val GROUP_OBJECTIVES = setOf("clan.elite_kill", "clan.boss_kill", "clan.arena_match", "clan.dungeon_run")
    }
}

data class ClanQuestProgress(
    val quest: ClanQuestDefinition,
    val values: List<Long>,
    val myContributions: List<Long>,
    val contributors: Int,
    val completed: Boolean,
)

data class ClanQuestBoard(
    val clanId: String,
    val clanName: String,
    val week: LocalDate,
    val quests: List<ClanQuestProgress>,
    val renown: Long,
    val boundClanId: String?,
) {
    val contributionAllowed: Boolean get() = boundClanId == null || boundClanId == clanId
    val completedCount: Int get() = quests.count(ClanQuestProgress::completed)
    val totalCompletedCount: Long get() = renown
}

/** Pure shared-plan accounting; numeric caps and group attendance use different units. */
internal data class ClanQuestState(
    val quest: ClanQuestDefinition,
    val values: List<Long> = quest.plan.initialValues,
    val contributions: Map<UUID, List<Long>> = emptyMap(),
) {
    val contributors: Int get() = contributions.values.count { member -> member.any { it > 0 } }
    val completed: Boolean get() = quest.plan.completed(values) && contributors >= quest.minContributors

    fun advance(playerId: UUID, objective: String, amount: Long): ClanQuestState {
        require(amount > 0) { "Clan quest amount must be positive" }
        if (completed || quest.groupActivity) return this
        val own = contributions[playerId] ?: quest.plan.initialValues
        val proposed = quest.plan.advance(values, objective, amount)
        val accepted = values.indices.map { index ->
            minOf(proposed[index] - values[index], (quest.contributionCap(index) - own[index]).coerceAtLeast(0))
        }
        if (accepted.all { it == 0L }) return this
        return copy(
            values = values.indices.map { values[it] + accepted[it] },
            contributions = contributions + (playerId to own.indices.map { own[it] + accepted[it] }),
        )
    }

    fun advanceGroup(playerIds: Set<UUID>, objective: String): ClanQuestState {
        if (completed || !quest.groupActivity || playerIds.size < 2) return this
        val next = quest.plan.advance(values, objective, 1)
        if (next == values) return this
        return copy(values = next, contributions = contributions + playerIds.associateWith { playerId ->
            val own = contributions[playerId] ?: quest.plan.initialValues
            own.indices.map { own[it] + (next[it] - values[it]) }
        })
    }
}
