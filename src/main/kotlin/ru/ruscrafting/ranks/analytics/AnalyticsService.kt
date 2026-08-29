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

class AnalyticsService(
    private val repository: AnalyticsRepository,
    private val clock: Clock,
    private val cacheDuration: Duration,
) {
    private data class CachedSummary(
        val expiresAt: Instant,
        val future: CompletableFuture<AnalyticsSummary>,
    )

    private val lock = Any()
    private val cache = mutableMapOf<Int, CachedSummary>()

    init {
        require(!cacheDuration.isNegative && !cacheDuration.isZero) { "Analytics cache duration must be positive" }
    }

    fun summary(days: Int): CompletableFuture<AnalyticsSummary> {
        require(days in SUPPORTED_WINDOWS) { "Unsupported analytics window: $days" }
        val now = clock.instant()
        synchronized(lock) {
            cache[days]?.takeIf { now.isBefore(it.expiresAt) }?.let { return it.future }

            val future = repository.summary(days, now).thenApply { raw -> raw.toSummary(days) }
            val cached = CachedSummary(now.plus(cacheDuration), future)
            cache[days] = cached
            future.whenComplete { _, error ->
                if (error != null) synchronized(lock) {
                    if (cache[days] === cached) cache.remove(days)
                }
            }
            return future
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
        val SUPPORTED_WINDOWS = setOf(7, 14, 30)
    }
}
