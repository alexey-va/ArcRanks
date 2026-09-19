package ru.ruscrafting.ranks.service

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.ruscrafting.ranks.config.PromotionMode
import ru.ruscrafting.ranks.config.RankReminderSettings
import ru.ruscrafting.ranks.domain.NextStep
import ru.ruscrafting.ranks.domain.RankEvaluation
import ru.ruscrafting.ranks.domain.RankEligibility
import ru.ruscrafting.ranks.domain.RankId
import ru.ruscrafting.ranks.quest.QuestDisplayMode
import ru.ruscrafting.ranks.text.RankLocale
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import java.util.logging.Logger

/** Sends a bounded, authoritative rank/quest reminder to a player. */
class RankReminderService(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val players: RankPlayerService,
    private val flush: (UUID) -> CompletableFuture<Unit>,
    private val settings: () -> RankReminderSettings,
    private val promotionMode: () -> PromotionMode,
    private val locale: () -> RankLocale,
    private val questDisplayMode: (UUID) -> QuestDisplayMode,
    private val clock: Clock = Clock.systemUTC(),
    private val logger: Logger = plugin.logger,
) : Listener, AutoCloseable {
    private val schedule = RankReminderSchedule(clock, settings)
    private val composer = RankReminderMessageComposer(locale, questDisplayMode)
    private val reportedFailures = mutableSetOf<UUID>()
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.onlinePlayers.forEach(::handleJoin)
        checkNotNull(tasks.runTimer(CHECK_PERIOD_TICKS, CHECK_PERIOD_TICKS, ::checkDue)) {
            "Could not schedule rank reminder checks"
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) = handleJoin(event.player)

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        schedule.quit(event.player.uniqueId)
        reportedFailures.remove(event.player.uniqueId)
    }

    /** Invalidates in-flight loads after a committed configuration generation changes. */
    fun onReload() {
        schedule.reload()
        reportedFailures.clear()
    }

    override fun close() {
        installed = false
        schedule.close()
        reportedFailures.clear()
    }

    internal fun handleJoin(player: Player) {
        if (player.isOnline) schedule.join(player.uniqueId)
    }

    internal fun checkDue() {
        if (promotionMode() != PromotionMode.ACTIVE) return
        schedule.due().forEach { request ->
            val player = plugin.server.getPlayer(request.playerId)
            if (player == null || !player.isOnline) {
                schedule.cancel(request)
                return@forEach
            }
            val load = try {
                flush(request.playerId).thenCompose { players.load(request.playerId) }
            } catch (failure: Throwable) {
                completeFailure(request, failure)
                return@forEach
            }
            load.whenCompleteSync(tasks) { snapshot, failure ->
                if (!player.isOnline) {
                    schedule.cancel(request)
                    return@whenCompleteSync
                }
                if (failure != null || snapshot == null) {
                    completeFailure(request, failure ?: IllegalStateException("rank snapshot was null"))
                    return@whenCompleteSync
                }
                val completion = schedule.complete(
                    request,
                    observation = snapshot.evaluation?.let { evaluation ->
                        RankReminderObservation(
                            nextRankId = evaluation.nextRank?.id,
                            ready = evaluation.eligibility == RankEligibility.READY,
                            preserveReadyRank = evaluation.eligibility == RankEligibility.INSUFFICIENT_AVAILABLE_PATHS,
                        )
                    },
                    success = true,
                )
                reportedFailures.remove(request.playerId)
                if (!completion.accepted || !completion.notify) return@whenCompleteSync
                runCatching { composer.compose(player, snapshot)?.let { message -> player.sendMessage(message) } }
                    .onFailure { renderFailure ->
                        logger.log(
                            Level.WARNING,
                            "Could not render rank reminder for ${request.playerId}",
                            renderFailure,
                        )
                    }
            }
        }
    }

    private fun completeFailure(request: RankReminderSchedule.Request, failure: Throwable) {
        val completion = schedule.complete(request, observation = null, success = false)
        if (!completion.accepted) return
        if (reportedFailures.add(request.playerId)) {
            logger.log(
                Level.WARNING,
                "Could not load rank reminder state for ${request.playerId}; retry is bounded",
                failure,
            )
        }
    }

    private companion object {
        const val CHECK_PERIOD_TICKS = 1_200L
    }
}

/** Main-thread state machine; it performs no I/O and owns join/quit/reload identity fencing. */
internal class RankReminderSchedule(
    private val clock: Clock,
    private val settings: () -> RankReminderSettings,
) : AutoCloseable {
    internal class Request(
        val playerId: UUID,
        internal val session: Session,
        internal val generation: Long,
    )

    internal class Session(
        val playerId: UUID,
        var nextCheckAtMillis: Long,
    ) {
        var inFlight = false
        var lastMessageAtMillis: Long? = null
        var lastReadyRank: RankId? = null
    }

    private val sessions = mutableMapOf<UUID, Session>()
    private var generation = 0L

    fun join(playerId: UUID) {
        sessions[playerId] = Session(playerId, clock.millis() + firstDelayMillis())
    }

    fun quit(playerId: UUID) {
        sessions.remove(playerId)
    }

    fun due(): List<Request> {
        val now = clock.millis()
        return sessions.values.asSequence()
            .filter { !it.inFlight && now >= it.nextCheckAtMillis }
            .onEach { it.inFlight = true }
            .map { Request(it.playerId, it, generation) }
            .toList()
    }

    data class Completion(val accepted: Boolean, val notify: Boolean)

    fun complete(
        request: Request,
        observation: RankReminderObservation?,
        success: Boolean,
    ): Completion {
        val session = sessions[request.playerId]
        if (session !== request.session || request.generation != generation || !session.inFlight) {
            return Completion(accepted = false, notify = false)
        }
        session.inFlight = false
        val now = clock.millis()
        session.nextCheckAtMillis = now + CHECK_PERIOD_SECONDS * MILLIS_PER_SECOND
        if (!success || observation == null || observation.nextRankId == null) {
            return Completion(accepted = true, notify = false)
        }
        val notify = if (observation.ready) {
            val readyTransition = session.lastReadyRank != observation.nextRankId
            session.lastReadyRank = observation.nextRankId
            readyTransition || session.lastMessageAtMillis == null ||
                now - session.lastMessageAtMillis!! >= cooldownMillis()
        } else {
            if (!observation.preserveReadyRank) session.lastReadyRank = null
            session.lastMessageAtMillis == null || now - session.lastMessageAtMillis!! >= cooldownMillis()
        }
        if (notify) session.lastMessageAtMillis = now
        return Completion(accepted = true, notify = notify)
    }

    fun cancel(request: Request) {
        val session = sessions[request.playerId]
        if (session !== request.session || request.generation != generation) return
        session.inFlight = false
        session.nextCheckAtMillis = clock.millis() + firstDelayMillis()
    }

    fun reload() {
        generation++
        val nextCheck = clock.millis() + firstDelayMillis()
        sessions.values.forEach {
            it.inFlight = false
            it.nextCheckAtMillis = nextCheck
        }
    }

    override fun close() {
        generation++
        sessions.clear()
    }

    private fun firstDelayMillis(): Long = settings().firstDelaySeconds * MILLIS_PER_SECOND

    private fun cooldownMillis(): Long = settings().cooldownSeconds * MILLIS_PER_SECOND

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val CHECK_PERIOD_SECONDS = 60L
    }
}

internal data class RankReminderObservation(
    val nextRankId: RankId?,
    val ready: Boolean,
    val preserveReadyRank: Boolean = false,
)

internal class RankReminderMessageComposer(
    private val locale: () -> RankLocale,
    private val questDisplayMode: (UUID) -> QuestDisplayMode,
) {
    fun compose(player: Player, snapshot: RankPlayerSnapshot): Component? {
        val evaluation = snapshot.evaluation ?: return null
        val next = evaluation.nextRank ?: return null
        val text = locale()
        val nextRank = text.render(next.displayNameKey, player)
        return when (evaluation.eligibility) {
            RankEligibility.READY -> text.render(
                "reminders.rank.ready",
                player,
                mapOf("next-rank" to nextRank, "action" to command("/rank", "/rank")),
            )
            ru.ruscrafting.ranks.domain.RankEligibility.TOP_RANK -> null
            else -> {
                val values = mapOf(
                    "next-rank" to nextRank,
                    "recommendation" to recommendation(text, player, evaluation),
                    "action" to command("/rank", "/rank"),
                    "quests-action" to command("/rank quests", "/rank quests"),
                )
                text.render(
                    if (questDisplayMode(player.uniqueId) == QuestDisplayMode.OFF) {
                        "reminders.rank.progress-no-quests"
                    } else {
                        "reminders.rank.progress"
                    },
                    player,
                    values,
                )
            }
        }
    }

    private fun recommendation(text: RankLocale, player: Player, evaluation: RankEvaluation): Component =
        when (val step = evaluation.recommendation) {
            is NextStep.ActiveMinutes -> text.render(
                "reminders.recommendation.active",
                player,
                mapOf("remaining" to text.renderDurationMinutes(step.remaining, player)),
            )
            is NextStep.PathGoal -> text.render(
                "reminders.recommendation.path",
                player,
                mapOf(
                    "path" to text.render("paths.${step.path.name.lowercase()}.name", player),
                    "remaining" to text.text(step.remaining),
                ),
            )
            null -> text.render(
                "reminders.recommendation.open",
                player,
                mapOf("action" to command("/rank", "/rank")),
            )
        }

    private fun command(label: String, command: String): Component =
        Component.text(label).clickEvent(ClickEvent.runCommand(command))
}
