package ru.ruscrafting.ranks.quest

import ru.arc.config.Config
import ru.ruscrafting.ranks.domain.ProgressMetric
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/** Configurable bounded pool, stable daily selection and per-rank allowance. */
data class DailyQuestCatalog(val countByRank: Map<String, Int>, val pool: List<DailyQuest>, val rareChancePercent: Int = 25, val rareMoney: Long = 150, val rareTokens: Long = 1) {
    init {
        require(rareChancePercent in 0..100 && rareMoney in 0..1_000_000 && rareTokens in 1..1000)
        require(pool.size in 1..128 && pool.map { it.id }.distinct().size == pool.size)
        require(countByRank.isNotEmpty() && countByRank.values.all { it in 1..minOf(21, pool.size) }) {
            "Each rank daily count must be 1..min(21, quest pool size)"
        }
    }

    fun select(playerId: UUID, day: LocalDate, rankId: String): List<DailyQuest> {
        val count = requireNotNull(countByRank[rankId]) { "Daily quest count missing for rank $rankId" }
        // Distribute the first choices across paths before selecting additional goals in a path.
        val groups = pool.groupBy { it.metric }.values.map { goals ->
            goals.sortedBy { score("$playerId:$day:${it.id}") }
        }.sortedBy { goals -> score("$playerId:$day:${goals.first().metric}") }
        val selected = (0 until groups.maxOf { it.size }).flatMap { index -> groups.mapNotNull { it.getOrNull(index) } }.take(count)
        val roll = score("$playerId:$day:rare").take(8).toLong(16) % 100
        if (roll >= rareChancePercent) return selected
        val rareIndex = (score("$playerId:$day:rare-slot").take(8).toLong(16) % selected.size).toInt()
        return selected.mapIndexed { index, quest ->
            if (index == rareIndex) quest.copy(target = (quest.target * 2).coerceAtMost(1_000_000_000), money = rareMoney, tokens = rareTokens)
            else quest
        }
    }

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
            val pool = config.keys("quests").map { id ->
                val path = config.string("quests.$id.path")
                val (metric, material) = requireNotNull(PATHS[path]) { "Unknown daily quest path $path" }
                DailyQuest(id, metric, config.long("quests.$id.target"), config.long("quests.$id.bonus"), material, money = config.long("quests.$id.money", config.long("rewards.money")), tokenCurrency = config.string("rewards.token-currency"), objective = config.string("quests.$id.objective"), textId = config.string("quests.$id.text"))
            }
            return DailyQuestCatalog(counts, pool, config.int("rewards.rare-chance-percent"), config.long("rewards.rare-money"), config.long("rewards.rare-tokens"))
        }
        private fun score(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
