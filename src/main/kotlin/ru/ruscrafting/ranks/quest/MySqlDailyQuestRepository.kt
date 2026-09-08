package ru.ruscrafting.ranks.quest

import ru.arc.sql.SqlRuntime
import ru.ruscrafting.ranks.contract.ContractRewardComponent
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.reward.RankReward
import ru.ruscrafting.ranks.reward.RankRewardRepository
import java.sql.Connection
import java.sql.Date
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Daily assignments are immutable SQL snapshots. The board owner lock serializes backends;
 * path bonuses and currency obligations commit with gameplay. Currency effects use the shared ledger.
 */
class MySqlDailyQuestRepository(
    private val runtime: SqlRuntime,
    private val catalog: () -> DailyQuestCatalog = { DailyQuestCatalog(mapOf("settler" to 3), DailyQuest.ALL, rareChancePercent = 0) },
    private val rank: (UUID) -> CompletableFuture<String> = { CompletableFuture.completedFuture("settler") },
    private val clock: Clock = Clock.systemUTC(),
    private val availability: (UUID) -> CompletableFuture<Set<String>> = { CompletableFuture.completedFuture(emptySet()) },
) : RankRewardRepository {
    fun board(playerId: UUID): CompletableFuture<DailyQuestBoard> {
        val day = DailyQuest.day(clock.instant())
        val config = catalog()
        return runtime.executor.read { readBoard(it, playerId, day) }.thenCompose { existing ->
            if (existing != null) CompletableFuture.completedFuture(existing)
            else rank(playerId).thenCompose { rankId ->
                availability(playerId).thenCompose { available ->
                    runtime.executor.transaction { connection -> assign(connection, playerId, day, rankId, config, available) }
                }
            }
        }
    }

    private fun assign(connection: Connection, playerId: UUID, day: LocalDate, rankId: String, config: DailyQuestCatalog, available: Set<String>): DailyQuestBoard {
        val inserted = connection.prepareStatement(
            "INSERT IGNORE INTO arc_ranks_daily_board (player_uuid, quest_day) VALUES (?, ?)",
        ).use {
            it.setString(1, playerId.toString()); it.setDate(2, Date.valueOf(day)); it.executeUpdate() == 1
        }
        val storedDay = lockDay(connection, playerId)
        if (!inserted && storedDay >= day) return checkNotNull(readBoard(connection, playerId, storedDay))
        val history = history(connection, playerId)
        val selected = config.select(playerId, day, rankId, history, completedOnce(connection, playerId), available)
        connection.prepareStatement("DELETE FROM arc_ranks_quest_history WHERE player_uuid = ? AND quest_day < ?").use {
            it.setString(1, playerId.toString()); it.setDate(2, Date.valueOf(day.minusDays(30))); it.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM arc_ranks_daily_goal WHERE player_uuid = ?").use {
            it.setString(1, playerId.toString()); it.executeUpdate()
        }
        connection.prepareStatement("UPDATE arc_ranks_daily_board SET quest_day = ?, rank_id = ?, scaling = ?, replacement_limit = ?, replacements = 0 WHERE player_uuid = ?").use {
            val scale = config.scalingByRank[rankId] ?: DailyQuestScaling()
            it.setDate(1, Date.valueOf(day)); it.setString(2, rankId)
            it.setString(3, "${scale.targetPercent},${scale.moneyPercent},${scale.bonusPercent},${scale.rareTokens ?: config.rareTokens}")
            it.setInt(4, config.replacementsPerDay); it.setString(5, playerId.toString()); it.executeUpdate()
        }
        selected.forEachIndexed { index, quest -> insertGoal(connection, playerId, day, index, quest) }
        return DailyQuestBoard(day, selected.map { DailyQuestProgress(it, 0) }, config.replacementsPerDay)
    }

    private fun insertGoal(connection: Connection, playerId: UUID, day: LocalDate, position: Int, quest: DailyQuest) {
        connection.prepareStatement(
            """INSERT INTO arc_ranks_daily_goal
            (player_uuid, position, quest_id, reward_id, metric, target, bonus, material, text_id, money, tokens, token_currency,
             objective, quest_plan, step_values, quest_family, availability_key, once_quest, scale_target, rare_eligible,
             challenge_suffix, challenge_percent, value)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)""",
        ).use {
            it.setString(1, playerId.toString()); it.setInt(2, position); it.setString(3, quest.id)
            it.setString(4, rewardId(playerId, day, quest.id)); it.setString(5, quest.metric.name)
            it.setLong(6, quest.target); it.setLong(7, quest.bonus); it.setString(8, quest.material)
            it.setString(9, quest.textId); it.setLong(10, quest.money); it.setLong(11, quest.tokens)
            it.setString(12, quest.tokenCurrency); it.setString(13, quest.objective)
            it.setString(14, quest.plan?.let(QuestPlanCodec::encode))
            it.setString(15, quest.plan?.let { plan -> QuestPlanCodec.encodeValues(plan.initialValues) })
            it.setString(16, quest.family); it.setString(17, quest.availability); it.setBoolean(18, quest.once)
            it.setBoolean(19, quest.scaleTarget); it.setBoolean(20, quest.rareEligible)
            it.setString(21, quest.challengeSuffix); it.setInt(22, quest.challengePercent); it.executeUpdate()
        }
        connection.prepareStatement("INSERT IGNORE INTO arc_ranks_quest_history (player_uuid, quest_day, quest_id) VALUES (?, ?, ?)").use {
            it.setString(1, playerId.toString()); it.setDate(2, Date.valueOf(day)); it.setString(3, quest.id); it.executeUpdate()
        }
    }

    private fun history(connection: Connection, playerId: UUID): Map<String, LocalDate> = connection.prepareStatement(
        "SELECT quest_id, MAX(quest_day) AS last_day FROM arc_ranks_quest_history WHERE player_uuid = ? GROUP BY quest_id",
    ).use {
        it.setString(1, playerId.toString())
        it.executeQuery().use { rows -> buildMap { while (rows.next()) put(rows.getString("quest_id"), rows.getDate("last_day").toLocalDate()) } }
    }

    private fun completedOnce(connection: Connection, playerId: UUID): Set<String> = connection.prepareStatement(
        "SELECT quest_id FROM arc_ranks_quest_once WHERE player_uuid = ?",
    ).use {
        it.setString(1, playerId.toString())
        it.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString("quest_id")) } }
    }

    fun replace(playerId: UUID, expectedDay: LocalDate, questId: String): CompletableFuture<QuestReplaceResult> {
        val config = catalog()
        return availability(playerId).thenCompose { available -> runtime.executor.transaction { connection ->
            val day = lockDay(connection, playerId)
            if (day != expectedDay || day != DailyQuest.day(clock.instant())) return@transaction QuestReplaceResult.STALE
            val board = checkNotNull(readBoard(connection, playerId, day))
            val index = board.quests.indexOfFirst { it.quest.id == questId }
            if (index < 0) return@transaction QuestReplaceResult.STALE
            val old = board.quests[index]
            if (old.completed) return@transaction QuestReplaceResult.COMPLETED
            val obsolete = old.quest.availability != null && old.quest.availability !in available
            if (config.replacementsPerDay == 0 || (!obsolete && board.replacementsLeft == 0)) return@transaction QuestReplaceResult.LIMIT
            val (rankId, scaling) = connection.prepareStatement("SELECT rank_id, scaling FROM arc_ranks_daily_board WHERE player_uuid = ?").use {
                it.setString(1, playerId.toString())
                it.executeQuery().use { rows ->
                    check(rows.next()); val fields = rows.getString("scaling").split(','); require(fields.size == 4)
                    rows.getString("rank_id") to DailyQuestScaling(fields[0].toInt(), fields[1].toInt(), fields[2].toInt(), fields[3].toLong())
                }
            }
            val history = history(connection, playerId)
            val excluded = completedOnce(connection, playerId) + history.filterValues { it == day }.keys + board.quests.map { it.quest.id }
            val other = board.quests.filterIndexed { position, _ -> position != index }
            val families = other.groupingBy { it.quest.family }.eachCount()
            val advanced = other.count { it.quest.plan != null }
            val eligible = config.pool.filter {
                it.id !in excluded && (old.quest.tokens == 0L || it.rareEligible) &&
                    (it.plan == null || advanced < config.advancedPerDay) &&
                    (it.availability == null || it.availability in available) &&
                    (it.minRank == null || config.countByRank.keys.indexOf(it.minRank) <= config.countByRank.keys.indexOf(rankId))
            }.let { candidates -> candidates.filter { (families[it.family] ?: 0) < config.maxPerFamily }.ifEmpty { candidates } }
            if (eligible.isEmpty()) return@transaction QuestReplaceResult.UNAVAILABLE
            val replacementCatalog = config.copy(scalingByRank = config.scalingByRank + (rankId to scaling), pool = eligible)
            val candidate = replacementCatalog.select(playerId, day, rankId, history, excluded, available,
                nonce = history.size, allowRare = false, countOverride = 1).firstOrNull() ?: return@transaction QuestReplaceResult.UNAVAILABLE
            val next = (if (old.quest.tokens > 0) replacementCatalog.rare(candidate, scaling) else candidate)
                .copy(money = old.quest.money, tokens = old.quest.tokens, tokenCurrency = old.quest.tokenCurrency)
            connection.prepareStatement("DELETE FROM arc_ranks_daily_goal WHERE player_uuid = ? AND quest_id = ?").use {
                it.setString(1, playerId.toString()); it.setString(2, questId); check(it.executeUpdate() == 1)
            }
            insertGoal(connection, playerId, day, index, next)
            if (!obsolete) connection.prepareStatement("UPDATE arc_ranks_daily_board SET replacements = replacements + 1 WHERE player_uuid = ?").use {
                it.setString(1, playerId.toString()); it.executeUpdate()
            }
            QuestReplaceResult.REPLACED
        } }
    }

    /** Caller has prepared this day before entering its gameplay transaction.
     * External grants never call this; bonuses never feed this method recursively.
     */
    fun advance(connection: Connection, playerId: UUID, day: LocalDate, objective: String, delta: Long): Map<ProgressMetric, Long> {
        if (lockDay(connection, playerId) != day) return emptyMap()
        val board = checkNotNull(readBoard(connection, playerId, day))
        val bonuses = mutableMapOf<ProgressMetric, Long>()
        board.quests.filter { !it.completed }.forEach { state ->
            val quest = state.quest
            val steps = quest.plan?.advance(state.stepValues, objective, delta)
            val next = if (quest.plan != null) quest.plan.progress(checkNotNull(steps))
                else if (quest.matchesObjective(objective)) quest.advance(state.value, delta) else state.value
            val challenge = if (quest.challengeSuffix != null && quest.matchesObjective(objective) && objective.endsWith(":" + quest.challengeSuffix))
                quest.advance(state.challengeValue, delta) else state.challengeValue
            if (next == state.value && steps == state.stepValues && challenge == state.challengeValue) return@forEach
            if (quest.plan == null && next == state.value && challenge == state.challengeValue) return@forEach
            connection.prepareStatement("UPDATE arc_ranks_daily_goal SET value = ?, step_values = ?, challenge_value = ? WHERE player_uuid = ? AND quest_id = ?").use {
                it.setLong(1, next); it.setString(2, steps?.let(QuestPlanCodec::encodeValues)); it.setLong(3, challenge)
                it.setString(4, playerId.toString()); it.setString(5, quest.id); check(it.executeUpdate() == 1)
            }
            if (next == quest.target) {
                if (quest.once) {
                    val fresh = connection.prepareStatement("INSERT IGNORE INTO arc_ranks_quest_once (player_uuid, quest_id) VALUES (?, ?)").use {
                        it.setString(1, playerId.toString()); it.setString(2, quest.id); it.executeUpdate() == 1
                    }
                    if (!fresh) return@forEach
                }
                bonuses[quest.metric] = Math.addExact(bonuses[quest.metric] ?: 0, quest.bonus)
                val extraMoney = if (quest.challengeSuffix != null && challenge >= quest.target) (quest.money * quest.challengePercent + 99) / 100 else 0
                connection.prepareStatement(
                    """INSERT INTO arc_ranks_daily_reward (reward_id, player_uuid, money, tokens, token_currency, state)
                    VALUES (?, ?, ?, ?, ?, 'PENDING')""",
                ).use {
                    it.setString(1, rewardId(playerId, day, quest.id)); it.setString(2, playerId.toString())
                    it.setLong(3, quest.money + extraMoney); it.setLong(4, quest.tokens); it.setString(5, quest.tokenCurrency)
                    it.executeUpdate()
                }
            }
        }
        return bonuses
    }

    fun lockDay(connection: Connection, playerId: UUID): LocalDate = connection.prepareStatement(
        "SELECT quest_day FROM arc_ranks_daily_board WHERE player_uuid = ? FOR UPDATE",
    ).use {
        it.setString(1, playerId.toString())
        it.executeQuery().use { rows -> check(rows.next()); rows.getDate("quest_day").toLocalDate() }
    }

    private fun readBoard(connection: Connection, playerId: UUID, day: LocalDate): DailyQuestBoard? {
        // One joined statement prevents observing a day marker and goals from different commits.
        val goals = mutableListOf<DailyQuestProgress>()
        var found = false
        var replacementsLeft = 0
        connection.prepareStatement(
            """SELECT g.*, r.state AS reward_state, b.replacement_limit, b.replacements FROM arc_ranks_daily_board b
            LEFT JOIN arc_ranks_daily_goal g ON g.player_uuid = b.player_uuid
            LEFT JOIN arc_ranks_daily_reward r ON r.reward_id = g.reward_id
            WHERE b.player_uuid = ? AND b.quest_day = ? ORDER BY g.position""",
        ).use {
            it.setString(1, playerId.toString()); it.setDate(2, Date.valueOf(day))
            it.executeQuery().use { rows ->
                while (rows.next()) {
                    found = true
                    replacementsLeft = (rows.getInt("replacement_limit") - rows.getInt("replacements")).coerceAtLeast(0)
                    val id = rows.getString("quest_id") ?: continue
                    val plan = rows.getString("quest_plan")?.let(QuestPlanCodec::decode)
                    val quest = DailyQuest(id, ProgressMetric.valueOf(rows.getString("metric")),
                        rows.getLong("target"), rows.getLong("bonus"), rows.getString("material"),
                        rows.getString("text_id"), rows.getLong("money"), rows.getLong("tokens"), rows.getString("token_currency"),
                        rows.getString("objective"), plan = plan, family = rows.getString("quest_family"),
                        availability = rows.getString("availability_key"), once = rows.getBoolean("once_quest"),
                        scaleTarget = rows.getBoolean("scale_target"), rareEligible = rows.getBoolean("rare_eligible"),
                        challengeSuffix = rows.getString("challenge_suffix"), challengePercent = rows.getInt("challenge_percent"))
                    goals += DailyQuestProgress(quest, rows.getLong("value"), rows.getString("reward_state")?.let(DailyRewardState::valueOf),
                        if (plan == null) emptyList() else QuestPlanCodec.decodeValues(checkNotNull(rows.getString("step_values")), plan),
                        rows.getLong("challenge_value"))
                }
            }
        }
        return if (found) DailyQuestBoard(day, goals, replacementsLeft) else null
    }

    override fun pendingRewards(playerId: UUID): CompletableFuture<List<RankReward>> = runtime.executor.read { connection ->
        val rewards = mutableListOf<RankReward>()
        connection.prepareStatement(
            "SELECT * FROM arc_ranks_daily_reward WHERE player_uuid = ? AND state = 'PENDING' ORDER BY created_at, reward_id LIMIT 64",
        ).use {
            it.setString(1, playerId.toString())
            it.executeQuery().use { rows ->
                while (rows.next()) rewards += RankReward(rows.getString("reward_id"), "daily", buildList {
                    val money = rows.getLong("money"); val tokens = rows.getLong("tokens")
                    if (money > 0) add(ContractRewardComponent.Money(money))
                    if (tokens > 0) add(ContractRewardComponent.Tokens(tokens, rows.getString("token_currency")))
                })
            }
        }
        rewards
    }

    override fun markRewardGranted(playerId: UUID, rewardId: String) = mark(playerId, rewardId, DailyRewardState.GRANTED, null)
    override fun markRewardRecovery(playerId: UUID, rewardId: String, failureCode: String) = mark(playerId, rewardId, DailyRewardState.RECOVERY, failureCode)

    private fun mark(playerId: UUID, id: String, state: DailyRewardState, code: String?): CompletableFuture<Boolean> = runtime.executor.write { connection ->
        connection.prepareStatement(
            "UPDATE arc_ranks_daily_reward SET state = ?, failure_code = ? WHERE player_uuid = ? AND reward_id = ? AND state = 'PENDING'",
        ).use {
            it.setString(1, state.name); it.setString(2, code?.take(64)); it.setString(3, playerId.toString())
            it.setString(4, id); it.executeUpdate() == 1
        }
    }

    private fun rewardId(playerId: UUID, day: LocalDate, questId: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$playerId:$day:$questId".toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }
}
