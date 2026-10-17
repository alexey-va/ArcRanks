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
    WEEKLY_KIT_OPEN,
    WEEKLY_KIT_CLAIM,
    WEEKLY_KIT_REJECTED,
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

data class ProductTelemetryTuning(
    val enabled: Boolean,
    val maximumMetricKeys: Int,
    val maximumPlayers: Int,
) {
    init {
        require(maximumMetricKeys > 0) { "Metric key capacity must be positive" }
        require(maximumPlayers > 0) { "Player capacity must be positive" }
    }
}

class ProductTelemetry(
    private val serverId: String,
    private val clock: Clock,
    private val tuningProvider: () -> ProductTelemetryTuning,
    private val writer: (TelemetryBatch) -> CompletableFuture<Unit>,
) {
    constructor(
        serverId: String,
        clock: Clock,
        maximumMetricKeys: Int,
        maximumPlayers: Int,
        writer: (TelemetryBatch) -> CompletableFuture<Unit>,
    ) : this(
        serverId,
        clock,
        fixedTuning(maximumMetricKeys, maximumPlayers),
        writer,
    )

    private data class PlayerDayKey(val day: LocalDate, val playerId: UUID)

    private data class MutablePlayerSignal(
        val signals: MutableSet<PlayerSignal>,
        var rankId: RankId?,
    )

    private class InFlight(val completion: CompletableFuture<Unit>) {
        lateinit var batch: TelemetryBatch
    }

    private val lock = Any()
    private var pendingCounters = linkedMapOf<ProductMetricKey, Long>()
    private var pendingPlayers = linkedMapOf<PlayerDayKey, MutablePlayerSignal>()
    private val ownedMetricKeys = hashSetOf<ProductMetricKey>()
    private val ownedPlayers = hashSetOf<PlayerDayKey>()
    private var retryBatch: TelemetryBatch? = null
    private var inFlight: InFlight? = null
    private var droppedMetricKeys = 0L
    private var droppedPlayers = 0L
    private var flushFailures = 0L
    private var lastSuccessfulFlushEpochMillis = 0L

    init {
        require(serverId.matches(Regex("[a-z0-9_-]{1,40}"))) { "Unsafe server id: $serverId" }
    }

    fun record(
        event: ProductEvent,
        dimension: ProductDimension = ProductDimension.NONE,
        amount: Long = 1,
    ): Boolean {
        require(amount > 0) { "Product event amount must be positive" }
        val key = ProductMetricKey(event, dimension)
        synchronized(lock) {
            val tuning = tuningProvider()
            if (!tuning.enabled) return false
            if (key !in ownedMetricKeys && ownedMetricKeys.size >= tuning.maximumMetricKeys) {
                droppedMetricKeys++
                return false
            }
            ownedMetricKeys += key
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
            val tuning = tuningProvider()
            if (!tuning.enabled) return false
            if (key !in ownedPlayers && ownedPlayers.size >= tuning.maximumPlayers) {
                droppedPlayers++
                return false
            }
            ownedPlayers += key
            val existing = pendingPlayers.getOrPut(key) { MutablePlayerSignal(linkedSetOf(), rankId) }
            existing.signals += signal
            if (rankId != null) existing.rankId = rankId
            return true
        }
    }

    /** Worker-only aggregation; gameplay admission holds only short bookkeeping locks. */
    fun flush(): CompletableFuture<Unit> {
        val flight: InFlight
        val retry: TelemetryBatch?
        val counters: Map<ProductMetricKey, Long>
        val players: Map<PlayerDayKey, MutablePlayerSignal>
        synchronized(lock) {
            inFlight?.let { return it.completion }
            if (retryBatch == null && pendingCounters.isEmpty() && pendingPlayers.isEmpty()) {
                return CompletableFuture.completedFuture(Unit)
            }

            retry = retryBatch.also { retryBatch = null }
            counters = if (retry == null) pendingCounters.also { pendingCounters = linkedMapOf() } else emptyMap()
            players = if (retry == null) pendingPlayers.also { pendingPlayers = linkedMapOf() } else emptyMap()
            flight = InFlight(CompletableFuture())
            inFlight = flight
        }
        val now = clock.instant()
        flight.batch = retry ?: TelemetryBatch(
            bucketStart = now.truncatedTo(ChronoUnit.HOURS),
            serverId = serverId,
            counters = counters,
            players = players.map { (key, value) ->
                PlayerDaySignal(key.day, key.playerId, value.signals.toSet(), value.rankId)
            },
        )
        runCatching { writer(flight.batch) }
            .getOrElse { CompletableFuture.failedFuture(it) }
            .whenComplete { _, error -> completeFlush(flight, error) }
        return flight.completion
    }

    fun flushAll(): CompletableFuture<Unit> = flush().thenCompose {
        val hasQueuedWork = synchronized(lock) {
            retryBatch != null || pendingCounters.isNotEmpty() || pendingPlayers.isNotEmpty()
        }
        if (hasQueuedWork) flushAll() else CompletableFuture.completedFuture(Unit)
    }

    fun healthSnapshot(): TelemetryHealthSnapshot = synchronized(lock) {
        TelemetryHealthSnapshot(
            pendingMetricKeys = if (retryBatch == null) pendingCounters.size else ownedMetricKeys.size,
            pendingPlayers = if (retryBatch == null) pendingPlayers.size else ownedPlayers.size,
            droppedMetricKeys = droppedMetricKeys,
            droppedPlayers = droppedPlayers,
            flushFailures = flushFailures,
            lastSuccessfulFlushEpochMillis = lastSuccessfulFlushEpochMillis,
            flushInProgress = inFlight != null,
        )
    }

    private fun completeFlush(flight: InFlight, error: Throwable?) {
        if (error == null) {
            // Release one key at a time: a large worker batch never monopolizes
            // the monitor used by Paper event producers.
            flight.batch.counters.keys.forEach { key ->
                synchronized(lock) { if (key !in pendingCounters) ownedMetricKeys -= key }
            }
            flight.batch.players.forEach { player ->
                val key = PlayerDayKey(player.day, player.playerId)
                synchronized(lock) { if (key !in pendingPlayers) ownedPlayers -= key }
            }
        }
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

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private companion object {
        fun fixedTuning(maximumMetricKeys: Int, maximumPlayers: Int): () -> ProductTelemetryTuning {
            val tuning = ProductTelemetryTuning(
                enabled = true,
                maximumMetricKeys = maximumMetricKeys,
                maximumPlayers = maximumPlayers,
            )
            return { tuning }
        }
    }
}
