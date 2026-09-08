package ru.ruscrafting.ranks.progress

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.ruscrafting.ranks.api.RankQuestApi
import ru.ruscrafting.ranks.quest.MySqlDailyQuestRepository
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** Main-thread provider reads with bounded social refreshes; unknown never means unlinked. */
class QuestAvailability(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val social: SocialQuestIntegration,
) : Listener, AutoCloseable {
    @Volatile private var closed = false
    private val waiting = ConcurrentHashMap<CompletableFuture<*>, () -> Unit>()
    @Volatile var votesEnabled = false
    private val contracts = ContractQuestAvailability(plugin)
    private val refreshing = mutableMapOf<UUID, CompletableFuture<Unit>>()
    private lateinit var repository: MySqlDailyQuestRepository
    private lateinit var quests: RankQuestApi

    /** Only new assignments/replacements ask for eligibility; stored boards never do. */
    fun forAssignment(playerId: UUID): CompletableFuture<Set<String>> = onMain(emptySet()) {
        val fixed = contracts.available() + if (votesEnabled) setOf("vote.enabled") else emptySet()
        social.status(playerId).thenApply { statuses ->
            fixed + statuses.filterValues { !it }.keys.map { "$it.unlinked" }
        }
    }

    fun install(repository: MySqlDailyQuestRepository, quests: RankQuestApi) {
        this.repository = repository
        this.quests = quests
        plugin.server.pluginManager.registerEvents(this, plugin)
        tasks.runTimer(20, 1200) {
            plugin.server.onlinePlayers.forEachIndexed { index, player ->
                // Spread requests; the shared Redis bridge permits four concurrent requests.
                tasks.runLater(index * 20L) { if (player.isOnline) refresh(player.uniqueId) }
            }
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        tasks.runLater(20) { if (event.player.isOnline) refresh(event.player.uniqueId) }
    }

    fun refresh(playerId: UUID): CompletableFuture<Unit> = onMain(Unit) {
        refreshing[playerId]?.let { return@onMain it }
        val future = repository.existingBoard(playerId).thenCompose { board ->
            val pending = board?.quests.orEmpty().filter {
                !it.completed && it.quest.once && it.quest.objective.startsWith("account.")
            }
            if (pending.isEmpty() || closed) return@thenCompose CompletableFuture.completedFuture(Unit)
            onMain(emptyMap<String, Boolean>()) { social.status(playerId) }.thenCompose { statuses ->
                pending.filter { statuses[it.quest.objective.removePrefix("account.")] == true }
                    .fold(CompletableFuture.completedFuture(Unit)) { prior, state ->
                        prior.thenCompose {
                            quests.record("social_link", "${state.quest.objective}:$playerId", playerId,
                                state.quest.objective, 1).thenApply { Unit }
                        }
                    }
            }
        }
        refreshing[playerId] = future
        future.whenCompleteSync(tasks) { _, failure ->
            refreshing.remove(playerId, future)
            if (failure != null) plugin.logger.warning("Could not refresh daily social quests: ${failure.javaClass.simpleName}")
        }
        future
    }

    override fun close() {
        closed = true
        waiting.values.forEach { it() }
        waiting.clear()
    }

    private fun <T> onMain(fallback: T, action: () -> CompletableFuture<T>): CompletableFuture<T> {
        if (closed) return CompletableFuture.completedFuture(fallback)
        val result = CompletableFuture<T>()
        waiting[result] = { result.complete(fallback) }
        result.whenComplete { _, _ -> waiting.remove(result) }
        if (closed) result.complete(fallback)
        val scheduled = tasks.runSync {
            if (result.isDone) return@runSync
            try {
                action().whenComplete { value, failure ->
                    if (failure == null) result.complete(value) else result.completeExceptionally(failure)
                }
            } catch (failure: Exception) { result.completeExceptionally(failure) }
        }
        if (scheduled == null) result.completeExceptionally(IllegalStateException("Quest lifecycle is closed"))
        return result
    }
}
