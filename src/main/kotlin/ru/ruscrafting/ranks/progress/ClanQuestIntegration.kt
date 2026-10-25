package ru.ruscrafting.ranks.progress

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.ruscrafting.ranks.api.ClanMembership
import ru.ruscrafting.ranks.api.ClanMembershipProvider
import ru.ruscrafting.ranks.clan.ClanQuestBoard
import ru.ruscrafting.ranks.clan.ClanQuestRepository
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/** Native membership is captured before an observation enters an asynchronous queue. */
class ClanQuestIntegration(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val repository: ClanQuestRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun membership(playerId: UUID): ClanMembership? {
        check(Bukkit.isPrimaryThread()) { "Clan membership requires the gameplay thread" }
        return plugin.server.servicesManager.load(ClanMembershipProvider::class.java)?.find(playerId)
    }

    fun board(playerId: UUID): CompletableFuture<ClanQuestBoard?> =
        onMain {
            checkNotNull(plugin.server.servicesManager.load(ClanMembershipProvider::class.java)) {
                "Clan membership provider is unavailable"
            }.find(playerId)
        }.thenCompose { captured ->
            if (captured == null) CompletableFuture.completedFuture(null)
            else repository.board(captured, playerId)
                .thenCompose { board -> onMain {
                    board.takeIf { membership(playerId)?.clanId() == captured.clanId() }
                } }
        }

    fun recordGroup(source: String, eventId: String, participants: Set<UUID>, objective: String) {
        val snapshots = participants.mapNotNull { id -> membership(id)?.let { id to it } }
        recordCapturedGroup(source, eventId, snapshots, objective, clock.instant())
    }

    fun recordCapturedGroup(source: String, eventId: String, participants: List<Pair<UUID, ClanMembership>>,
                            objective: String, instant: Instant, attempt: Int = 0): CompletableFuture<Int> {
        if (participants.size < 2) return CompletableFuture.completedFuture(0)
        val result = CompletableFuture<Int>()
        repository.recordGroup(source, eventId, participants, objective, instant).whenCompleteSync(tasks) { count, failure ->
            if (failure != null) {
                if (attempt < 3) {
                    if (tasks.runLater(100L * (attempt + 1)) {
                        recordCapturedGroup(source, eventId, participants, objective, instant, attempt + 1)
                            .whenComplete { credited, error -> if (error == null) result.complete(credited) else result.completeExceptionally(error) }
                    } == null) result.cancel(false)
                } else plugin.logger.log(Level.WARNING,
                    "Could not retain clan quest activity source=$source event=$eventId objective=$objective", failure)
                if (attempt >= 3) result.completeExceptionally(failure)
            } else {
                result.complete(count)
            }
        }
        return result
    }

    private fun <T> onMain(read: () -> T): CompletableFuture<T> {
        if (Bukkit.isPrimaryThread()) return runCatching { CompletableFuture.completedFuture(read()) }
            .getOrElse { CompletableFuture.failedFuture(it) }
        val result = CompletableFuture<T>()
        if (tasks.runSync {
            runCatching(read).fold(result::complete, result::completeExceptionally)
        } == null) result.cancel(false)
        return result
    }
}
