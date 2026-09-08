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
) : RankRewardRepository {
    fun board(playerId: UUID): CompletableFuture<DailyQuestBoard> {
        val day = DailyQuest.day(clock.instant())
        val config = catalog()
        return runtime.executor.read { readBoard(it, playerId, day) }.thenCompose { existing ->
            if (existing != null) CompletableFuture.completedFuture(existing)
            else rank(playerId).thenCompose { rankId ->
                val selected = config.select(playerId, day, rankId)
                runtime.executor.transaction { connection -> assign(connection, playerId, day, selected) }
            }
        }
    }

    private fun assign(connection: Connection, playerId: UUID, day: LocalDate, selected: List<DailyQuest>): DailyQuestBoard {
        val inserted = connection.prepareStatement(
            "INSERT IGNORE INTO arc_ranks_daily_board (player_uuid, quest_day) VALUES (?, ?)",
        ).use {
            it.setString(1, playerId.toString()); it.setDate(2, Date.valueOf(day)); it.executeUpdate() == 1
        }
        val storedDay = lockDay(connection, playerId)
        if (!inserted && storedDay >= day) return checkNotNull(readBoard(connection, playerId, storedDay))
        connection.prepareStatement("DELETE FROM arc_ranks_daily_goal WHERE player_uuid = ?").use {
            it.setString(1, playerId.toString()); it.executeUpdate()
        }
        connection.prepareStatement("UPDATE arc_ranks_daily_board SET quest_day = ? WHERE player_uuid = ?").use {
            it.setDate(1, Date.valueOf(day)); it.setString(2, playerId.toString()); it.executeUpdate()
        }
        selected.forEachIndexed { index, quest ->
            connection.prepareStatement(
                """INSERT INTO arc_ranks_daily_goal
                (player_uuid, position, quest_id, reward_id, metric, target, bonus, material, text_id, money, tokens, token_currency, objective, value)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)""",
            ).use {
                it.setString(1, playerId.toString()); it.setInt(2, index); it.setString(3, quest.id)
                it.setString(4, rewardId(playerId, day, quest.id)); it.setString(5, quest.metric.name)
                it.setLong(6, quest.target); it.setLong(7, quest.bonus); it.setString(8, quest.material)
                it.setString(9, quest.textId); it.setLong(10, quest.money); it.setLong(11, quest.tokens)
                it.setString(12, quest.tokenCurrency); it.setString(13, quest.objective); it.executeUpdate()
            }
        }
        return DailyQuestBoard(day, selected.map { DailyQuestProgress(it, 0) })
    }

    /** Caller has prepared this day before entering its gameplay transaction.
     * External grants never call this; bonuses never feed this method recursively.
     */
    fun advance(connection: Connection, playerId: UUID, day: LocalDate, objective: String, delta: Long): Map<ProgressMetric, Long> {
        if (lockDay(connection, playerId) != day) return emptyMap()
        val board = checkNotNull(readBoard(connection, playerId, day))
        val bonuses = mutableMapOf<ProgressMetric, Long>()
        board.quests.filter { it.quest.matchesObjective(objective) && !it.completed }.forEach { state ->
            val quest = state.quest
            val next = quest.advance(state.value, delta)
            connection.prepareStatement("UPDATE arc_ranks_daily_goal SET value = ? WHERE player_uuid = ? AND quest_id = ?").use {
                it.setLong(1, next); it.setString(2, playerId.toString()); it.setString(3, quest.id)
                check(it.executeUpdate() == 1)
            }
            if (next == quest.target) {
                bonuses[quest.metric] = Math.addExact(bonuses[quest.metric] ?: 0, quest.bonus)
                connection.prepareStatement(
                    """INSERT INTO arc_ranks_daily_reward (reward_id, player_uuid, money, tokens, token_currency, state)
                    VALUES (?, ?, ?, ?, ?, 'PENDING')""",
                ).use {
                    it.setString(1, rewardId(playerId, day, quest.id)); it.setString(2, playerId.toString())
                    it.setLong(3, quest.money); it.setLong(4, quest.tokens); it.setString(5, quest.tokenCurrency)
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
        connection.prepareStatement(
            """SELECT g.*, r.state AS reward_state FROM arc_ranks_daily_board b
            JOIN arc_ranks_daily_goal g ON g.player_uuid = b.player_uuid
            LEFT JOIN arc_ranks_daily_reward r ON r.reward_id = g.reward_id
            WHERE b.player_uuid = ? AND b.quest_day = ? ORDER BY g.position""",
        ).use {
            it.setString(1, playerId.toString()); it.setDate(2, Date.valueOf(day))
            it.executeQuery().use { rows ->
                while (rows.next()) goals += DailyQuestProgress(
                    DailyQuest(rows.getString("quest_id"), ProgressMetric.valueOf(rows.getString("metric")),
                        rows.getLong("target"), rows.getLong("bonus"), rows.getString("material"),
                        rows.getString("text_id"), rows.getLong("money"), rows.getLong("tokens"), rows.getString("token_currency"), rows.getString("objective")),
                    rows.getLong("value"), rows.getString("reward_state")?.let(DailyRewardState::valueOf),
                )
            }
        }
        return goals.takeIf { it.isNotEmpty() }?.let { DailyQuestBoard(day, it) }
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
