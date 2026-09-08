package ru.ruscrafting.ranks.quest

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Short-lived, best-effort observations for explaining why a collector did not
 * produce a quest action. This is deliberately not persisted with quest state.
 */
class QuestProgressDiagnostics(
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = DEFAULT_TTL,
) {
    init {
        require(!ttl.isNegative && !ttl.isZero) { "Diagnostics TTL must be positive" }
    }

    private val observations = LinkedHashMap<UUID, PlayerObservations>()
    private var sequence = 0L
    private var lastCleanup: Instant? = null

    @Synchronized
    fun rejected(playerId: UUID, objective: String, reason: QuestProgressRejectReason) {
        val now = clock.instant()
        maybeCleanup(now)
        val playerObservations = observations[playerId] ?: run {
            if (observations.size >= MAX_PLAYERS) {
                observations.entries.iterator().let { iterator ->
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
            }
            PlayerObservations().also { observations[playerId] = it }
        }
        playerObservations.rejected.remove(objective)
        playerObservations.accepted.remove(objective)
        evictOldestIfFull(playerObservations)
        playerObservations.rejected[objective] = TimedRejection(QuestProgressObservation(objective, reason, now), nextSequence())
    }

    /** Clears the observation for this exact accepted event objective. */
    @Synchronized
    fun accepted(playerId: UUID, objective: String) {
        val now = clock.instant()
        maybeCleanup(now)
        val playerObservations = observations[playerId] ?: return
        evictOldestIfFull(playerObservations, replacingAccepted = objective)
        playerObservations.accepted[objective] = TimedAcceptance(objective, now, nextSequence())
    }

    /**
     * Returns the newest still-valid observation for an exact objective or a
     * colon-prefixed concrete objective (for example, harvest:wheat matches
     * harvest). A specific objective never matches another specific objective.
     */
    @Synchronized
    fun latest(playerId: UUID, objective: String): String? {
        val now = clock.instant()
        maybeCleanup(now)
        val playerObservations = observations[playerId]
        val observation = playerObservations
            ?.rejected
            ?.values
            ?.asSequence()
            ?.filter { it.objective == objective || it.objective.startsWith("$objective:") }
            ?.filter { isLive(it.observation.at, now) }
            ?.maxByOrNull(TimedRejection::sequence)
        val accepted = playerObservations
            ?.accepted
            ?.values
            ?.asSequence()
            ?.filter { it.objective == objective || it.objective.startsWith("$objective:") }
            ?.filter { isLive(it.at, now) }
            ?.maxByOrNull(TimedAcceptance::sequence)
        return observation
            ?.takeUnless { accepted != null && accepted.sequence > it.sequence }
            ?.observation
            ?.reason
            ?.key
    }

    @Synchronized
    fun clear(playerId: UUID) {
        observations.remove(playerId)
    }

    private fun removeExpired(now: Instant) {
        observations.entries.removeIf { (_, playerObservations) ->
            playerObservations.rejected.entries.removeIf { (_, observation) -> !isLive(observation.observation.at, now) }
            playerObservations.accepted.entries.removeIf { (_, acceptance) -> !isLive(acceptance.at, now) }
            playerObservations.isEmpty
        }
    }

    private fun maybeCleanup(now: Instant) {
        val previous = lastCleanup
        if (previous != null && now.isBefore(previous.plus(CLEANUP_INTERVAL))) return
        removeExpired(now)
        lastCleanup = now
    }

    private fun isLive(at: Instant, now: Instant): Boolean = now.isBefore(at.plus(ttl))

    private fun evictOldestIfFull(playerObservations: PlayerObservations, replacingAccepted: String? = null) {
        val existing = playerObservations.rejected.containsKey(replacingAccepted) ||
            playerObservations.accepted.containsKey(replacingAccepted)
        if (existing || playerObservations.size < MAX_OBJECTIVES_PER_PLAYER) return
        val oldestRejection = playerObservations.rejected.minByOrNull { it.value.sequence }
        val oldestAcceptance = playerObservations.accepted.minByOrNull { it.value.sequence }
        if (oldestRejection != null && (oldestAcceptance == null || oldestRejection.value.sequence < oldestAcceptance.value.sequence)) {
            playerObservations.rejected.remove(oldestRejection.key)
        } else if (oldestAcceptance != null) {
            playerObservations.accepted.remove(oldestAcceptance.key)
        }
    }

    private fun nextSequence(): Long = Math.addExact(sequence, 1L).also { sequence = it }

    private data class PlayerObservations(
        val rejected: LinkedHashMap<String, TimedRejection> = LinkedHashMap(),
        val accepted: LinkedHashMap<String, TimedAcceptance> = LinkedHashMap(),
    ) {
        val size: Int get() = rejected.size + accepted.size
        val isEmpty: Boolean get() = rejected.isEmpty() && accepted.isEmpty()
    }

    private data class TimedRejection(val observation: QuestProgressObservation, val sequence: Long) {
        val objective: String get() = observation.objective
    }

    private data class TimedAcceptance(val objective: String, val at: Instant, val sequence: Long)

    private companion object {
        const val MAX_PLAYERS = 256
        const val MAX_OBJECTIVES_PER_PLAYER = 32
        val CLEANUP_INTERVAL: Duration = Duration.ofSeconds(1)
        val DEFAULT_TTL: Duration = Duration.ofSeconds(30)
    }
}

enum class QuestProgressRejectReason(val key: String) {
    SOURCE_DISABLED("source_disabled"),
    CONTEXT_INELIGIBLE("context_ineligible"),
    MATERIAL_FILTERED("material_filtered"),
    NOT_MATURE("not_mature"),
    DUPLICATE_POSITION("duplicate_position"),
    BUFFER_FULL("buffer_full"),
}

data class QuestProgressObservation(
    val objective: String,
    val reason: QuestProgressRejectReason,
    val at: Instant,
)
