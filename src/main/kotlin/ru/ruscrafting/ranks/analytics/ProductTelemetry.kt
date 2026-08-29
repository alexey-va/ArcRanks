package ru.ruscrafting.ranks.analytics

import ru.ruscrafting.ranks.domain.RankId
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CompletableFuture

enum class ProductEvent {
    PLAYER_SEEN,
    PASSPORT_OPEN,
    CONTRACT_BOARD_OPEN,
    PERK_BOARD_OPEN,
    ANALYTICS_BOARD_OPEN,
    RECOMMENDATION_SHOWN,
    PROMOTION_ATTEMPT,
    PROMOTION_SUCCESS,
    PROMOTION_BLOCKED,
    CONTRACT_OFFERED,
    CONTRACT_ACCEPTED,
    CONTRACT_REROLLED,
    CONTRACT_CLAIMED,
    CONTRACT_EXPIRED,
    CONTRACT_REJECTED,
    PERK_SELECTED,
    PERK_REMOVED,
    PERK_REJECTED,
    PERK_BONUS_PROGRESS,
}

@JvmInline
value class ProductDimension(val value: String) {
    init {
        require(value.matches(SAFE_VALUE)) { "Unsafe product dimension: $value" }
    }

    companion object {
        private val SAFE_VALUE = Regex("[a-z0-9_.:-]{1,80}")
        val NONE = ProductDimension("none")
    }
}

data class ProductMetricKey(
    val event: ProductEvent,
    val dimension: ProductDimension,
)

enum class PlayerSignal {
    SEEN,
    PASSPORT_OPENED,
    CONTRACT_ACCEPTED,
    CONTRACT_COMPLETED,
    PERK_SELECTED,
}

data class PlayerDaySignal(
    val day: LocalDate,
    val playerId: UUID,
    val signals: Set<PlayerSignal>,
    val rankId: RankId?,
)

data class TelemetryBatch(
    val bucketStart: Instant,
    val serverId: String,
    val counters: Map<ProductMetricKey, Long>,
    val players: List<PlayerDaySignal>,
    val batchId: UUID = UUID.randomUUID(),
) {
    val isEmpty: Boolean
        get() = counters.isEmpty() && players.isEmpty()
}

data class TelemetryHealthSnapshot(
    val pendingMetricKeys: Int,
    val pendingPlayers: Int,
    val droppedMetricKeys: Long,
    val droppedPlayers: Long,
    val flushFailures: Long,
    val lastSuccessfulFlushEpochMillis: Long,
    val flushInProgress: Boolean,
)

class ProductTelemetry(
    private val serverId: String,
    private val clock: Clock,
    private val maximumMetricKeys: Int,
    private val maximumPlayers: Int,
    private val writer: (TelemetryBatch) -> CompletableFuture<Unit>,
) {
    private data class PlayerDayKey(val day: LocalDate, val playerId: UUID)

    private data class MutablePlayerSignal(
        val signals: MutableSet<PlayerSignal>,
        var rankId: RankId?,
    )

    private data class InFlight(
        val batch: TelemetryBatch,
        val completion: CompletableFuture<Unit>,
    )

    private val lock = Any()
    private val pendingCounters = linkedMapOf<ProductMetricKey, Long>()
    private val pendingPlayers = linkedMapOf<PlayerDayKey, MutablePlayerSignal>()
    private var retryBatch: TelemetryBatch? = null
    private var inFlight: InFlight? = null
    private var droppedMetricKeys = 0L
    private var droppedPlayers = 0L
    private var flushFailures = 0L
    private var lastSuccessfulFlushEpochMillis = 0L

    init {
        require(serverId.matches(Regex("[a-z0-9_-]{1,40}"))) { "Unsafe server id: $serverId" }
        require(maximumMetricKeys > 0) { "Metric key capacity must be positive" }
        require(maximumPlayers > 0) { "Player capacity must be positive" }
    }

    fun record(
        event: ProductEvent,
        dimension: ProductDimension = ProductDimension.NONE,
        amount: Long = 1,
    ): Boolean {
        require(amount > 0) { "Product event amount must be positive" }
        val key = ProductMetricKey(event, dimension)
        synchronized(lock) {
            if (!ownsMetricKey(key) && ownedMetricKeyCount() >= maximumMetricKeys) {
                droppedMetricKeys++
                return false
            }
            pendingCounters[key] = saturatedAdd(pendingCounters[key] ?: 0, amount)
            return true
        }
    }

    fun recordPlayer(
        playerId: UUID,
        signal: PlayerSignal,
        rankId: RankId? = null,
    ): Boolean {
        val key = PlayerDayKey(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC), playerId)
        synchronized(lock) {
            if (!ownsPlayer(key) && ownedPlayerCount() >= maximumPlayers) {
                droppedPlayers++
                return false
            }
            val existing = pendingPlayers.getOrPut(key) { MutablePlayerSignal(linkedSetOf(), rankId) }
            existing.signals += signal
            if (rankId != null) existing.rankId = rankId
            return true
        }
    }

    fun flush(): CompletableFuture<Unit> {
        synchronized(lock) {
            inFlight?.let { return it.completion }
            if (retryBatch == null && pendingCounters.isEmpty() && pendingPlayers.isEmpty()) {
                return CompletableFuture.completedFuture(Unit)
            }

            val batch = retryBatch?.also { retryBatch = null } ?: drainBatch()
            val completion = CompletableFuture<Unit>()
            val flight = InFlight(batch, completion)
            inFlight = flight
            runCatching { writer(batch) }
                .getOrElse { CompletableFuture.failedFuture(it) }
                .whenComplete { _, error -> completeFlush(flight, error) }
            return completion
        }
    }

    fun flushAll(): CompletableFuture<Unit> = flush().thenCompose {
        val hasQueuedWork = synchronized(lock) {
            retryBatch != null || pendingCounters.isNotEmpty() || pendingPlayers.isNotEmpty()
        }
        if (hasQueuedWork) flushAll() else CompletableFuture.completedFuture(Unit)
    }

    fun healthSnapshot(): TelemetryHealthSnapshot = synchronized(lock) {
        TelemetryHealthSnapshot(
            pendingMetricKeys = (pendingCounters.keys + retryBatch.orEmptyMetricKeys()).size,
            pendingPlayers = (pendingPlayers.keys + retryBatch.orEmptyPlayerKeys()).size,
            droppedMetricKeys = droppedMetricKeys,
            droppedPlayers = droppedPlayers,
            flushFailures = flushFailures,
            lastSuccessfulFlushEpochMillis = lastSuccessfulFlushEpochMillis,
            flushInProgress = inFlight != null,
        )
    }

    private fun drainBatch(): TelemetryBatch {
        val now = clock.instant()
        val batch = TelemetryBatch(
            bucketStart = now.truncatedTo(ChronoUnit.HOURS),
            serverId = serverId,
            counters = pendingCounters.toMap(),
            players = pendingPlayers.map { (key, value) ->
                PlayerDaySignal(key.day, key.playerId, value.signals.toSet(), value.rankId)
            },
        )
        pendingCounters.clear()
        pendingPlayers.clear()
        return batch
    }

    private fun completeFlush(flight: InFlight, error: Throwable?) {
        synchronized(lock) {
            if (inFlight !== flight) return
            if (error == null) {
                lastSuccessfulFlushEpochMillis = clock.millis()
            } else {
                flushFailures++
                retryBatch = flight.batch
            }
            inFlight = null
        }
        if (error == null) flight.completion.complete(Unit)
        else flight.completion.completeExceptionally(error)
    }

    private fun ownsMetricKey(key: ProductMetricKey): Boolean =
        key in pendingCounters || retryBatch?.counters?.containsKey(key) == true ||
            inFlight?.batch?.counters?.containsKey(key) == true

    private fun ownedMetricKeyCount(): Int =
        (pendingCounters.keys + retryBatch.orEmptyMetricKeys() + inFlight.orEmptyMetricKeys()).size

    private fun ownsPlayer(key: PlayerDayKey): Boolean =
        key in pendingPlayers || retryBatch.orEmptyPlayerKeys().contains(key) || inFlight.orEmptyPlayerKeys().contains(key)

    private fun ownedPlayerCount(): Int =
        (pendingPlayers.keys + retryBatch.orEmptyPlayerKeys() + inFlight.orEmptyPlayerKeys()).size

    private fun TelemetryBatch?.orEmptyMetricKeys(): Set<ProductMetricKey> =
        this?.counters?.keys.orEmpty()

    private fun TelemetryBatch?.orEmptyPlayerKeys(): Set<PlayerDayKey> =
        this?.players?.mapTo(linkedSetOf()) { PlayerDayKey(it.day, it.playerId) }.orEmpty()

    private fun InFlight?.orEmptyMetricKeys(): Set<ProductMetricKey> =
        this?.batch?.counters?.keys.orEmpty()

    private fun InFlight?.orEmptyPlayerKeys(): Set<PlayerDayKey> =
        this?.batch?.players?.mapTo(linkedSetOf()) { PlayerDayKey(it.day, it.playerId) }.orEmpty()

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
