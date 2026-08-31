package ru.ruscrafting.ranks.analytics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture

class AnalyticsServiceTest : StringSpec({
    "summary derives literal funnel rates and reuses the bounded cache" {
        var reads = 0
        val repository = object : AnalyticsRepository {
            override fun write(batch: TelemetryBatch) = CompletableFuture.completedFuture(Unit)

            override fun summary(days: Int, now: Instant): CompletableFuture<AnalyticsRawSummary> {
                reads++
                return CompletableFuture.completedFuture(
                    AnalyticsRawSummary(
                        uniqueSeenPlayers = 20,
                        passportPlayers = 10,
                        contractAcceptedPlayers = 5,
                        contractCompletedPlayers = 4,
                        perkSelectedPlayers = 2,
                        eventTotals = mapOf(
                            ProductEvent.PROMOTION_ATTEMPT to 8,
                            ProductEvent.PROMOTION_SUCCESS to 3,
                        ),
                        recommendationTotals = mapOf("path:farming" to 6, "active" to 2),
                    ),
                )
            }
        }
        val clock = Clock.fixed(Instant.parse("2026-08-29T18:42:00Z"), ZoneOffset.UTC)
        val service = AnalyticsService(repository, clock, Duration.ofSeconds(60))

        val first = service.summary(7).join()
        val second = service.summary(7).join()

        reads shouldBe 1
        first shouldBe second
        first.passportReach shouldBeExactly 0.5
        first.contractAcceptReach shouldBeExactly 0.25
        first.contractCompletionRate shouldBeExactly 0.8
        first.perkReach shouldBeExactly 0.1
        first.promotionSuccessRate shouldBeExactly 0.375
        first.topRecommendation shouldBe "path:farming"
    }

    "summary handles empty cohorts and validates supported windows" {
        val repository = object : AnalyticsRepository {
            override fun write(batch: TelemetryBatch) = CompletableFuture.completedFuture(Unit)
            override fun summary(days: Int, now: Instant) =
                CompletableFuture.completedFuture(AnalyticsRawSummary.EMPTY)
        }
        val service = AnalyticsService(
            repository,
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            Duration.ofSeconds(60),
        )

        val summary = service.summary(30).join()

        summary.passportReach shouldBeExactly 0.0
        summary.contractCompletionRate shouldBeExactly 0.0
        (runCatching { service.summary(3) }.exceptionOrNull() is IllegalArgumentException) shouldBe true
    }

    "dynamic tuning changes supported windows and cache lifetime and can invalidate cached work" {
        var reads = 0
        var now = Instant.parse("2026-08-29T18:42:00Z")
        var tuning = AnalyticsTuning(setOf(3), Duration.ofSeconds(60))
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = now
        }
        val repository = object : AnalyticsRepository {
            override fun write(batch: TelemetryBatch) = CompletableFuture.completedFuture(Unit)

            override fun summary(days: Int, now: Instant): CompletableFuture<AnalyticsRawSummary> {
                reads++
                return CompletableFuture.completedFuture(AnalyticsRawSummary.EMPTY)
            }
        }
        val service = AnalyticsService(repository, clock) { tuning }

        service.summary(3).join()
        service.summary(3).join()
        reads shouldBe 1

        tuning = AnalyticsTuning(setOf(5), Duration.ofSeconds(5))
        (runCatching { service.summary(3) }.exceptionOrNull() is IllegalArgumentException) shouldBe true
        service.summary(5).join()
        reads shouldBe 2

        now = now.plusSeconds(6)
        service.summary(5).join()
        reads shouldBe 3
        service.summary(5).join()
        reads shouldBe 3

        service.invalidateCache()
        service.summary(5).join()
        reads shouldBe 4
    }
})
