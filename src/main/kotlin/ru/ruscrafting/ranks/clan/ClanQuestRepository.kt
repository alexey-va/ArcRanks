package ru.ruscrafting.ranks.clan

import ru.arc.sql.SqlRuntime
import ru.ruscrafting.ranks.api.ClanMembership
import ru.ruscrafting.ranks.quest.QuestPlanCodec
import ru.ruscrafting.ranks.storage.retryingTransaction
import java.sql.Connection
import java.sql.Date
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** JDBC is worker-only through the existing SqlRuntime; advance/prepare join their caller's transaction.
 * Lock every clan/week board before any player/week binding. Old cycles remain addressable for delayed work.
 */
class ClanQuestRepository(
    private val runtime: SqlRuntime,
    private val catalog: () -> ClanQuestCatalog = { ClanQuestCatalog.defaults() },
    private val clock: Clock = Clock.systemUTC(),
) {
    fun board(membership: ClanMembership, playerId: UUID): CompletableFuture<ClanQuestBoard> {
        require(playerId in membership.memberIds()) { "Player is absent from the captured clan membership" }
        val week = ClanQuestCycle.week(clock.instant())
        val config = catalog()
        return runtime.executor.retryingTransaction { connection ->
            lockBoard(connection, membership, week, config)
            val quests = readQuests(connection, membership.clanId(), week).map { stored ->
                val state = stored.state
                ClanQuestProgress(state.quest, state.values,
                    state.contributions[playerId] ?: state.quest.plan.initialValues, state.contributors, stored.completed)
            }
            val renown = connection.prepareStatement(
                "SELECT COALESCE(SUM(renown), 0) AS renown FROM arc_ranks_clan_goal WHERE clan_id = ? AND completed = TRUE",
            ).use {
                it.setString(1, membership.clanId())
                it.executeQuery().use { rows -> check(rows.next()); rows.getLong("renown") }
            }
            ClanQuestBoard(membership.clanId(), membership.name(), week, quests, renown,
                readBinding(connection, playerId, week))
        }
    }

    /** Call once before a multi-action transaction can acquire its first player binding. */
    fun prepare(connection: Connection, memberships: List<Pair<ClanMembership, LocalDate>>) {
        val config = catalog()
        memberships.distinctBy { (membership, week) -> week to membership.clanId() }
            .sortedWith(compareBy({ it.second }, { it.first.clanId() }))
            .forEach { (membership, week) -> lockBoard(connection, membership, week, config) }
    }

    fun advance(
        connection: Connection,
        playerId: UUID,
        membership: ClanMembership,
        objective: String,
        amount: Long,
        week: LocalDate,
    ) {
        require(playerId in membership.memberIds()) { "Player is absent from the captured clan membership" }
        require(objective.matches(Regex("[a-z0-9_.:-]{1,96}")) && amount in 1..1_000_000_000L)
        if (objective in ClanQuestDefinition.GROUP_OBJECTIVES) return
        prepare(connection, listOf(membership to week))
        val bound = lockBinding(connection, playerId, week)
        if (bound != null && bound != membership.clanId()) return
        val changed = readQuests(connection, membership.clanId(), week).mapNotNull { stored ->
            if (stored.completed) null else {
                val next = stored.state.advance(playerId, objective, amount)
                if (next == stored.state) null else stored to next
            }
        }
        if (changed.isEmpty()) return
        bind(connection, playerId, week, membership.clanId())
        changed.forEach { (stored, next) -> persist(connection, membership.clanId(), week, stored, next) }
    }

    /** Captured same-clan participants are credited together once, including across servers/retries. */
    fun recordGroup(
        source: String,
        eventId: String,
        memberships: List<Pair<UUID, ClanMembership>>,
        objective: String,
        instant: Instant,
    ): CompletableFuture<Int> {
        require(source.matches(Regex("[a-z0-9_.-]{1,40}"))) { "Unsafe clan group source" }
        require(eventId.matches(Regex("[A-Za-z0-9:_.-]{1,120}"))) { "Unsafe clan group event id" }
        require(objective in ClanQuestDefinition.GROUP_OBJECTIVES) { "Unsupported clan group objective" }
        require(memberships.size <= 1_000 && memberships.map { it.first }.distinct().size == memberships.size) {
            "Group participants must contain at most 1000 distinct players"
        }
        require(memberships.all { (playerId, membership) -> playerId in membership.memberIds() }) {
            "Group player is absent from the captured clan membership"
        }
        val groups = memberships.groupBy { it.second.clanId() }.filterValues { it.size >= 2 }.toSortedMap()
        if (groups.isEmpty()) return CompletableFuture.completedFuture(0)
        val week = ClanQuestCycle.week(instant)
        return runtime.executor.retryingTransaction { connection ->
            prepare(connection, groups.values.map { it.first().second to week })
            val bindings = groups.values.flatten().sortedBy { it.first.toString() }.associate { (playerId, _) ->
                playerId to lockBinding(connection, playerId, week)
            }
            var credited = 0
            groups.forEach { (clanId, members) ->
                val eligible = members.map { it.first }.filter { bindings[it] == null || bindings[it] == clanId }.toSet()
                if (eligible.size < 2 || !insertGroupEvent(connection, clanId, source, eventId, week, objective)) return@forEach
                val changed = readQuests(connection, clanId, week).mapNotNull { stored ->
                    if (stored.completed) null else {
                        val next = stored.state.advanceGroup(eligible, objective)
                        if (next == stored.state) null else stored to next
                    }
                }
                if (changed.isNotEmpty()) {
                    eligible.forEach { bind(connection, it, week, clanId) }
                    changed.forEach { (stored, next) -> persist(connection, clanId, week, stored, next) }
                    credited++
                }
            }
            credited
        }
    }

    private fun lockBoard(connection: Connection, membership: ClanMembership, week: LocalDate, config: ClanQuestCatalog) {
        ClanQuestCycle.validate(week)
        val inserted = connection.prepareStatement(
            "INSERT IGNORE INTO arc_ranks_clan_board (clan_id, cycle_start) VALUES (?, ?)",
        ).use {
            it.setString(1, membership.clanId()); it.setDate(2, Date.valueOf(week)); it.executeUpdate() == 1
        }
        connection.prepareStatement("SELECT clan_id FROM arc_ranks_clan_board WHERE clan_id = ? AND cycle_start = ? FOR UPDATE").use {
            it.setString(1, membership.clanId()); it.setDate(2, Date.valueOf(week))
            it.executeQuery().use { rows -> check(rows.next()) }
        }
        if (!inserted) return
        connection.prepareStatement("""INSERT INTO arc_ranks_clan_goal
            (clan_id, cycle_start, position, quest_id, text_id, material, quest_plan, step_values,
             minimum_contributors, cap_percent, renown, completed) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE)""").use { goals ->
            config.select(membership.clanId(), week).forEachIndexed { position, quest ->
                goals.setString(1, membership.clanId()); goals.setDate(2, Date.valueOf(week)); goals.setInt(3, position)
                goals.setString(4, quest.id); goals.setString(5, quest.textId); goals.setString(6, quest.material)
                goals.setString(7, QuestPlanCodec.encode(quest.plan)); goals.setString(8, QuestPlanCodec.encodeValues(quest.plan.initialValues))
                goals.setInt(9, quest.minContributors); goals.setInt(10, quest.capPercent); goals.setLong(11, quest.renown)
                goals.addBatch()
            }
            goals.executeBatch()
        }
    }

    private data class StoredQuest(val position: Int, val state: ClanQuestState, val completed: Boolean)

    private fun readQuests(connection: Connection, clanId: String, week: LocalDate): List<StoredQuest> {
        // Current reads matter when a caller established a REPEATABLE READ snapshot before taking the board lock.
        val contributions = connection.prepareStatement("""SELECT position, player_uuid, step_values
            FROM arc_ranks_clan_contribution WHERE clan_id = ? AND cycle_start = ? ORDER BY position, player_uuid FOR UPDATE""").use {
            it.setString(1, clanId); it.setDate(2, Date.valueOf(week))
            it.executeQuery().use { rows -> buildList {
                while (rows.next()) add(Triple(rows.getInt("position"), UUID.fromString(rows.getString("player_uuid")), rows.getString("step_values")))
            } }
        }.groupBy { it.first }
        return connection.prepareStatement("SELECT * FROM arc_ranks_clan_goal WHERE clan_id = ? AND cycle_start = ? ORDER BY position FOR UPDATE").use {
            it.setString(1, clanId); it.setDate(2, Date.valueOf(week))
            it.executeQuery().use { rows -> buildList {
                while (rows.next()) {
                    val plan = QuestPlanCodec.decode(rows.getString("quest_plan"))
                    val quest = ClanQuestDefinition(rows.getString("quest_id"), rows.getString("text_id"), plan,
                        rows.getString("material"), rows.getInt("minimum_contributors"), rows.getInt("cap_percent"), rows.getLong("renown"))
                    val position = rows.getInt("position")
                    val memberValues = contributions[position].orEmpty().associate { (_, playerId, values) ->
                        playerId to QuestPlanCodec.decodeValues(values, plan)
                    }
                    add(StoredQuest(position, ClanQuestState(quest,
                        QuestPlanCodec.decodeValues(rows.getString("step_values"), plan), memberValues), rows.getBoolean("completed")))
                }
            } }
        }
    }

    private fun lockBinding(connection: Connection, playerId: UUID, week: LocalDate): String? {
        connection.prepareStatement("INSERT IGNORE INTO arc_ranks_clan_member_week (player_uuid, cycle_start) VALUES (?, ?)").use {
            it.setString(1, playerId.toString()); it.setDate(2, Date.valueOf(week)); it.executeUpdate()
        }
        return readBinding(connection, playerId, week, lock = true)
    }

    private fun readBinding(connection: Connection, playerId: UUID, week: LocalDate, lock: Boolean = false): String? =
        connection.prepareStatement("SELECT clan_id FROM arc_ranks_clan_member_week WHERE player_uuid = ? AND cycle_start = ?" +
            if (lock) " FOR UPDATE" else "").use {
            it.setString(1, playerId.toString()); it.setDate(2, Date.valueOf(week))
            it.executeQuery().use { rows -> if (rows.next()) rows.getString("clan_id") else null }
        }

    private fun bind(connection: Connection, playerId: UUID, week: LocalDate, clanId: String) {
        connection.prepareStatement("UPDATE arc_ranks_clan_member_week SET clan_id = ? WHERE player_uuid = ? AND cycle_start = ? AND clan_id IS NULL").use {
            it.setString(1, clanId); it.setString(2, playerId.toString()); it.setDate(3, Date.valueOf(week)); it.executeUpdate()
        }
    }

    private fun insertGroupEvent(connection: Connection, clanId: String, source: String, eventId: String, week: LocalDate, objective: String): Boolean =
        connection.prepareStatement("INSERT IGNORE INTO arc_ranks_clan_group_event (clan_id, source, event_id, cycle_start, objective) VALUES (?, ?, ?, ?, ?)").use {
            it.setString(1, clanId); it.setString(2, source); it.setString(3, eventId)
            it.setDate(4, Date.valueOf(week)); it.setString(5, objective); it.executeUpdate() == 1
        }

    private fun persist(connection: Connection, clanId: String, week: LocalDate, previous: StoredQuest, next: ClanQuestState) {
        connection.prepareStatement("UPDATE arc_ranks_clan_goal SET step_values = ?, completed = ? WHERE clan_id = ? AND cycle_start = ? AND position = ?").use {
            it.setString(1, QuestPlanCodec.encodeValues(next.values)); it.setBoolean(2, next.completed)
            it.setString(3, clanId); it.setDate(4, Date.valueOf(week)); it.setInt(5, previous.position); check(it.executeUpdate() == 1)
        }
        connection.prepareStatement("""INSERT INTO arc_ranks_clan_contribution
            (clan_id, cycle_start, position, player_uuid, step_values) VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE step_values = VALUES(step_values)""").use { contributions ->
            next.contributions.filter { (playerId, values) -> previous.state.contributions[playerId] != values }.forEach { (playerId, values) ->
                contributions.setString(1, clanId); contributions.setDate(2, Date.valueOf(week)); contributions.setInt(3, previous.position)
                contributions.setString(4, playerId.toString()); contributions.setString(5, QuestPlanCodec.encodeValues(values)); contributions.addBatch()
            }
            contributions.executeBatch()
        }
    }
}
