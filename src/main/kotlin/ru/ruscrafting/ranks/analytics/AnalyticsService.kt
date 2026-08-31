package ru.ruscrafting.ranks.analytics

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture

interface AnalyticsRepository {
    fun write(batch: TelemetryBatch): CompletableFuture<Unit>
    fun summary(days: Int, now: Instant): CompletableFuture<AnalyticsRawSummary>
}

data class AnalyticsRawSummary(
    val uniqueSeenPlayers: Long,
    val passportPlayers: Long,
    val contractAcceptedPlayers: Long,
    val contractCompletedPlayers: Long,
    val perkSelectedPlayers: Long,
    val eventTotals: Map<ProductEvent, Long>,
    val recommendationTotals: Map<String, Long>,
) {
    companion object {
        val EMPTY = AnalyticsRawSummary(0, 0, 0, 0, 0, emptyMap(), emptyMap())
    }
}

data class AnalyticsSummary(
    val days: Int,
    val uniqueSeenPlayers: Long,
    val passportPlayers: Long,
    val contractAcceptedPlayers: Long,
    val contractCompletedPlayers: Long,
    val perkSelectedPlayers: Long,
    val promotionAttempts: Long,
    val promotionSuccesses: Long,
    val passportReach: Double,
    val contractAcceptReach: Double,
    val contractCompletionRate: Double,
    val perkReach: Double,
    val promotionSuccessRate: Double,
    val topRecommendation: String?,
)

class AnalyticsTuning(
    supportedWindows: Set<Int>,
    val cacheDuration: Duration,
) {
    val supportedWindows: Set<Int> = supportedWindows.toSet()

    init {
        require(this.supportedWindows.isNotEmpty()) { "Analytics must support at least one summary window" }
        require(this.supportedWindows.all { it > 0 }) { "Analytics windows must be positive" }
        require(!cacheDuration.isNegative && !cacheDuration.isZero) {
            "Analytics cache duration must be positive"
        }
    }
}

class AnalyticsService(
    private val repository: AnalyticsRepository,
    private val clock: Clock,
    private val tuningProvider: () -> AnalyticsTuning,
) {
    constructor(
        repository: AnalyticsRepository,
        clock: Clock,
        cacheDuration: Duration,
    ) : this(repository, clock, fixedTuning(cacheDuration))

    private data class CachedSummary(
        val expiresAt: Instant,
        val future: CompletableFuture<AnalyticsSummary>,
    )

    private val lock = Any()
    private val cache = mutableMapOf<Int, CachedSummary>()

    fun summary(days: Int): CompletableFuture<AnalyticsSummary> {
        synchronized(lock) {
            val tuning = tuningProvider()
            require(days in tuning.supportedWindows) { "Unsupported analytics window: $days" }
            val now = clock.instant()
            cache[days]?.takeIf { now.isBefore(it.expiresAt) }?.let { return it.future }

            val future = repository.summary(days, now).thenApply { raw -> raw.toSummary(days) }
            val cached = CachedSummary(now.plus(tuning.cacheDuration), future)
            cache[days] = cached
            future.whenComplete { _, error ->
                if (error != null) synchronized(lock) {
                    if (cache[days] === cached) cache.remove(days)
                }
            }
            return future
        }
    }

    fun invalidateCache() {
        synchronized(lock) {
            cache.clear()
        }
    }

    private fun AnalyticsRawSummary.toSummary(days: Int): AnalyticsSummary {
        val attempts = eventTotals[ProductEvent.PROMOTION_ATTEMPT] ?: 0
        val successes = eventTotals[ProductEvent.PROMOTION_SUCCESS] ?: 0
        return AnalyticsSummary(
            days = days,
            uniqueSeenPlayers = uniqueSeenPlayers,
            passportPlayers = passportPlayers,
            contractAcceptedPlayers = contractAcceptedPlayers,
            contractCompletedPlayers = contractCompletedPlayers,
            perkSelectedPlayers = perkSelectedPlayers,
            promotionAttempts = attempts,
            promotionSuccesses = successes,
            passportReach = ratio(passportPlayers, uniqueSeenPlayers),
            contractAcceptReach = ratio(contractAcceptedPlayers, uniqueSeenPlayers),
            contractCompletionRate = ratio(contractCompletedPlayers, contractAcceptedPlayers),
            perkReach = ratio(perkSelectedPlayers, uniqueSeenPlayers),
            promotionSuccessRate = ratio(successes, attempts),
            topRecommendation = recommendationTotals.entries
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .firstOrNull()
                ?.key,
        )
    }

    private fun ratio(numerator: Long, denominator: Long): Double =
        if (denominator == 0L) 0.0 else numerator.toDouble() / denominator.toDouble()

    private companion object {
        val DEFAULT_SUPPORTED_WINDOWS = setOf(7, 14, 30)

        fun fixedTuning(cacheDuration: Duration): () -> AnalyticsTuning {
            val tuning = AnalyticsTuning(DEFAULT_SUPPORTED_WINDOWS, cacheDuration)
            return { tuning }
        }
    }
}
