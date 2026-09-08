package ru.ruscrafting.ranks.quest

import ru.arc.config.Config
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.domain.SpecializationPath
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/** Configurable bounded pool, stable daily selection and per-rank allowance. */
data class DailyQuestCatalog(
    val countByRank: Map<String, Int>,
    val pool: List<DailyQuest>,
    val rareChancePercent: Int = 25,
    val rareMoney: Long = 150,
    val rareTokens: Long = 1,
    val scalingByRank: Map<String, DailyQuestScaling> = emptyMap(),
    val historyDays: Int = 3,
    val maxPerFamily: Int = 2,
    val advancedPerDay: Int = 3,
    val replacementsPerDay: Int = 2,
    val focusPercent: Int = 35,
    val trackingEnabled: Boolean = true,
    val trackingIntervalSeconds: Int = 3,

) {
    init {
        require(historyDays in 0..30 && maxPerFamily in 1..21 && advancedPerDay in 0..21 && replacementsPerDay in 0..10)
        require(focusPercent in 0..100) { "Selection focus percent must be between 0 and 100" }
        require(trackingIntervalSeconds in 1..30) { "Tracking interval must be between 1 and 30 seconds" }
        require(pool.all { it.minRank == null || it.minRank in countByRank })
        require(rareChancePercent in 0..100 && rareMoney in 0..1_000_000 && rareTokens in 1..1000)
        scalingByRank.values.forEach { scaling ->
            pool.forEach { quest ->
                val scaled = scaling.apply(quest)
                if (scaled.rareEligible && scaled.scaleTarget) scaled.plan?.scaled(200)
                require(scaled.target <= 500_000_000) { "Scaled target must leave room for rare difficulty" }
            }
            require(scaling.money(rareMoney) <= 1_000_000)
        }
        require(pool.size in 1..128 && pool.map { it.id }.distinct().size == pool.size)
        require(countByRank.isNotEmpty() && countByRank.values.all { it in 1..21 }) {
            "Each rank daily count must be 1..21"
        }
    }

    fun select(
        playerId: UUID, day: LocalDate, rankId: String,
        recent: Map<String, LocalDate> = emptyMap(), excluded: Set<String> = emptySet(),
        available: Set<String> = emptySet(), nonce: Int = 0,
        allowRare: Boolean = true, countOverride: Int? = null,
        focus: SpecializationPath? = null,
    ): List<DailyQuest> {
        val count = countOverride ?: requireNotNull(countByRank[rankId]) { "Daily quest count missing for rank $rankId" }
        val ranks = countByRank.keys.toList()
        val rankIndex = ranks.indexOf(rankId)
        require(rankIndex >= 0)
        val candidates = pool.filter {
            it.id !in excluded && (it.availability == null || it.availability in available) &&
                (it.minRank == null || ranks.indexOf(it.minRank) <= rankIndex)
        }.toMutableList()
        val isFocus: (DailyQuest) -> Boolean = focus?.let { path -> { quest: DailyQuest -> path.owns(quest.metric) } }
            ?: { _: DailyQuest -> false }
        val focusCandidates = candidates.count(isFocus)
        val nonFocusAvailable = candidates.any { !isFocus(it) }
        val maxFocus = if (focusPercent > 0 && nonFocusAvailable && count > 1) count - 1 else count
        val focusQuota = minOf(count * focusPercent / 100, maxFocus, focusCandidates)
        val chosen = mutableListOf<DailyQuest>()
        while (chosen.size < count && candidates.isNotEmpty()) {
            val familyCounts = chosen.groupingBy { it.family }.eachCount()
            val pathCounts = chosen.groupingBy { it.metric }.eachCount()
            val advancedCount = chosen.count { it.plan != null }
            val preferred = candidates.filter { (familyCounts[it.family] ?: 0) < maxPerFamily &&
                (it.plan == null || advancedCount < advancedPerDay) }
            val remaining = preferred.ifEmpty { candidates.filter { it.plan == null || advancedCount < advancedPerDay } }
            if (remaining.isEmpty()) break
            val focusRemaining = focusQuota - chosen.count(isFocus)
            val chosenFocus = chosen.count(isFocus)
            val nonFocusRemaining = remaining.any { !isFocus(it) }
            val scoped = when {
                focusRemaining > 0 -> remaining.filter(isFocus).ifEmpty { remaining }
                chosenFocus >= maxFocus && nonFocusRemaining -> remaining.filterNot(isFocus)
                else -> remaining
            }
            val next = scoped.minWith(compareBy<DailyQuest>(
                { if (it.once) 0 else 1 },
                { if (it.plan != null && advancedCount < advancedPerDay) 0 else 1 },
                { recent[it.id]?.takeIf { seen -> seen >= day.minusDays(historyDays.toLong()) }?.toEpochDay() ?: Long.MIN_VALUE },
                { pathCounts[it.metric] ?: 0 },
                { familyCounts[it.family] ?: 0 },
                { score("$playerId:$day:$nonce:${it.id}") },
            ))
            chosen += next
            candidates.remove(next)
        }
        val scaling = scalingByRank[rankId] ?: DailyQuestScaling()
        val selected = chosen.map(scaling::apply)
        val eligibleRare = selected.indices.filter { selected[it].rareEligible }
        val roll = score("$playerId:$day:rare").take(8).toLong(16) % 100
        if (!allowRare || roll >= rareChancePercent || eligibleRare.isEmpty()) return selected
        val rareIndex = eligibleRare[(score("$playerId:$day:rare-slot").take(8).toLong(16) % eligibleRare.size).toInt()]
        return selected.mapIndexed { index, quest -> if (index == rareIndex) rare(quest, scaling) else quest }
    }

    fun rare(quest: DailyQuest, scaling: DailyQuestScaling): DailyQuest = quest.copy(
        target = if (quest.plan == null && quest.scaleTarget) quest.target * 2 else quest.target,
        plan = quest.plan?.let { if (quest.scaleTarget) it.scaled(200) else it },
        money = scaling.money(rareMoney), tokens = scaling.rareTokens ?: rareTokens,
    )

    companion object {
        private val PATHS = mapOf(
            "farming" to (ProgressMetric.CROPS_HARVESTED to "WHEAT"),
            "industry" to (ProgressMetric.PRODUCTION_ACTIONS to "CRAFTING_TABLE"),
            "exploration" to (ProgressMetric.TRAVEL_BLOCKS to "COMPASS"),
            "building" to (ProgressMetric.BLOCKS_PLACED to "BRICKS"),
            "community" to (ProgressMetric.COMMUNITY_MINUTES to "CAMPFIRE"),
            "trade" to (ProgressMetric.TRADE_ACTIONS to "EMERALD"),
        )
        fun load(config: Config, rankIds: Set<String>): DailyQuestCatalog {
            val counts = config.keys("count-by-rank").associateWith { config.int("count-by-rank.$it") }
            require(counts.keys == rankIds) { "daily-quests.yml count-by-rank must cover exactly the configured ranks" }
            val pool = config.keys("quests").filter { id ->
                config.boolean("quests.$id.enabled", true) &&
                    config.boolean("features.${config.string("quests.$id.feature", "ordinary")}", true)
            }.map { id ->
                val path = config.string("quests.$id.path")
                val (metric, material) = requireNotNull(PATHS[path]) { "Unknown daily quest path $path" }
                val mode = config.string("quests.$id.mode", "counter").uppercase(java.util.Locale.ROOT)
                val plan = if (mode == "COUNTER") null else QuestPlan(
                    QuestMode.valueOf(mode),
                    config.keys("quests.$id.steps").sortedBy { it.toInt() }.map { step ->
                        val key = "quests.$id.steps.$step"
                        QuestStep(config.string("$key.objective"), config.long("$key.target"), config.string("$key.text"))
                    },
                    config.int("quests.$id.required-distinct", config.keys("quests.$id.steps").size),
                )
                DailyQuest(id, metric, config.long("quests.$id.target"), config.long("quests.$id.bonus"), material, money = config.long("quests.$id.money", config.long("rewards.money")), tokenCurrency = config.string("rewards.token-currency"), objective = config.string("quests.$id.objective"), textId = config.string("quests.$id.text"),
                    plan = plan, family = config.string("quests.$id.family", config.string("quests.$id.objective").substringBefore(':')),
                    minRank = config.string("quests.$id.min-rank", "").ifBlank { null },
                    availability = config.string("quests.$id.availability", "").ifBlank { null },
                    once = config.boolean("quests.$id.once", false), scaleTarget = config.boolean("quests.$id.scale-target", true),
                    rareEligible = config.boolean("quests.$id.rare-eligible", true),
                    challengeSuffix = config.string("quests.$id.challenge-suffix", "").ifBlank { null },
                    challengePercent = if (config.boolean("features.challenges", true)) config.int("quests.$id.challenge-percent", 0) else 0,
                ).let { if (plan == null) it else it.copy(target = plan.target) }
            }
            val scaling = config.keys("scaling-by-rank").associateWith { id ->
                val key = "scaling-by-rank.$id"
                DailyQuestScaling(config.int("$key.target-percent"), config.int("$key.money-percent"),
                    config.int("$key.bonus-percent"), config.long("$key.rare-tokens"))
            }
            require(scaling.isEmpty() || scaling.keys == rankIds) { "Daily scaling must cover exactly the configured ranks" }
            return DailyQuestCatalog(counts, pool, config.int("rewards.rare-chance-percent"),
                config.long("rewards.rare-money"), config.long("rewards.rare-tokens"), scaling,
                config.int("selection.history-days", 3), config.int("selection.max-per-family", 2),
                config.int("selection.advanced-per-day", 3), if (config.boolean("features.replacements", true)) config.int("selection.replacements-per-day", 2) else 0,
                config.int("selection.focus-percent", 35),
                config.boolean("tracking.enabled", true), config.int("tracking.interval-seconds", 3))
        }
        private fun score(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
