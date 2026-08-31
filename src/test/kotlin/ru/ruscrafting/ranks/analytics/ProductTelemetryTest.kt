package ru.ruscrafting.ranks.analytics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.ruscrafting.ranks.domain.RankId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ProductTelemetryTest : StringSpec({
    val now = Instant.parse("2026-08-29T18:42:00Z")
    val clock = Clock.fixed(now, ZoneOffset.UTC)

    "recording is memory-only until one coalesced flush" {
        val writes = mutableListOf<TelemetryBatch>()
        val telemetry = ProductTelemetry("survival", clock, 8, 8) { batch ->
            writes += batch
            CompletableFuture.completedFuture(Unit)
        }
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000001")

        telemetry.record(ProductEvent.PASSPORT_OPEN, ProductDimension("rank:citizen"))
        telemetry.record(ProductEvent.PASSPORT_OPEN, ProductDimension("rank:citizen"), 2)
        telemetry.recordPlayer(playerId, PlayerSignal.PASSPORT_OPENED, RankId("citizen"))
        telemetry.recordPlayer(playerId, PlayerSignal.CONTRACT_ACCEPTED, RankId("citizen"))

        writes shouldHaveSize 0
        telemetry.flush().join()

        writes shouldHaveSize 1
        writes.single().counters.shouldContainExactly(
            mapOf(ProductMetricKey(ProductEvent.PASSPORT_OPEN, ProductDimension("rank:citizen")) to 3L),
        )
        writes.single().players.single().signals shouldBe
            setOf(PlayerSignal.PASSPORT_OPENED, PlayerSignal.CONTRACT_ACCEPTED)
        writes.single().players.single().rankId shouldBe RankId("citizen")
        telemetry.healthSnapshot().pendingMetricKeys shouldBe 0
        telemetry.healthSnapshot().pendingPlayers shouldBe 0
    }

    "capacity rejects only new ownership and keeps an existing key writable" {
        val telemetry = ProductTelemetry("survival", clock, 1, 1) { CompletableFuture.completedFuture(Unit) }
        val firstPlayer = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val secondPlayer = UUID.fromString("00000000-0000-0000-0000-000000000002")

        telemetry.record(ProductEvent.PASSPORT_OPEN, ProductDimension.NONE) shouldBe true
        telemetry.record(ProductEvent.PASSPORT_OPEN, ProductDimension.NONE) shouldBe true
        telemetry.record(ProductEvent.PERK_BOARD_OPEN, ProductDimension.NONE) shouldBe false
        telemetry.recordPlayer(firstPlayer, PlayerSignal.SEEN, RankId("settler")) shouldBe true
        telemetry.recordPlayer(firstPlayer, PlayerSignal.PERK_SELECTED, RankId("settler")) shouldBe true
        telemetry.recordPlayer(secondPlayer, PlayerSignal.SEEN, RankId("settler")) shouldBe false

        telemetry.healthSnapshot().droppedMetricKeys shouldBe 1
        telemetry.healthSnapshot().droppedPlayers shouldBe 1
    }

    "dynamic disablement drops no capacity and re-enables collection" {
        var tuning = ProductTelemetryTuning(
            enabled = false,
            maximumMetricKeys = 1,
            maximumPlayers = 1,
        )
        val writes = mutableListOf<TelemetryBatch>()
        val telemetry = ProductTelemetry("survival", clock, { tuning }) { batch ->
            writes += batch
            CompletableFuture.completedFuture(Unit)
        }
        val playerId = UUID.fromString("00000000-0000-0000-0000-000000000001")

        telemetry.record(ProductEvent.PASSPORT_OPEN) shouldBe false
        telemetry.recordPlayer(playerId, PlayerSignal.SEEN) shouldBe false
        telemetry.healthSnapshot().droppedMetricKeys shouldBe 0
        telemetry.healthSnapshot().droppedPlayers shouldBe 0

        tuning = tuning.copy(enabled = true)
        telemetry.record(ProductEvent.PASSPORT_OPEN) shouldBe true
        telemetry.recordPlayer(playerId, PlayerSignal.SEEN) shouldBe true
        tuning = tuning.copy(enabled = false)
        telemetry.flush().join()

        writes.single().counters.values.single() shouldBe 1
        writes.single().players.single().playerId shouldBe playerId
    }

    "lowered dynamic limits preserve every owned key while rejecting new ownership" {
        var tuning = ProductTelemetryTuning(
            enabled = true,
            maximumMetricKeys = 2,
            maximumPlayers = 2,
        )
        val writes = mutableListOf<TelemetryBatch>()
        val telemetry = ProductTelemetry("survival", clock, { tuning }) { batch ->
            writes += batch
            CompletableFuture.completedFuture(Unit)
        }
        val firstPlayer = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val secondPlayer = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val thirdPlayer = UUID.fromString("00000000-0000-0000-0000-000000000003")

        telemetry.record(ProductEvent.PASSPORT_OPEN) shouldBe true
        telemetry.record(ProductEvent.PERK_BOARD_OPEN) shouldBe true
        telemetry.recordPlayer(firstPlayer, PlayerSignal.SEEN) shouldBe true
        telemetry.recordPlayer(secondPlayer, PlayerSignal.SEEN) shouldBe true

        tuning = tuning.copy(maximumMetricKeys = 1, maximumPlayers = 1)
        telemetry.record(ProductEvent.PASSPORT_OPEN, amount = 2) shouldBe true
        telemetry.record(ProductEvent.PERK_BOARD_OPEN, amount = 3) shouldBe true
        telemetry.record(ProductEvent.CONTRACT_BOARD_OPEN) shouldBe false
        telemetry.recordPlayer(firstPlayer, PlayerSignal.PERK_SELECTED) shouldBe true
        telemetry.recordPlayer(secondPlayer, PlayerSignal.PERK_SELECTED) shouldBe true
        telemetry.recordPlayer(thirdPlayer, PlayerSignal.SEEN) shouldBe false
        telemetry.flush().join()

        writes.single().counters.values.toSet() shouldBe setOf(3L, 4L)
        writes.single().players.map { it.playerId }.toSet() shouldBe setOf(firstPlayer, secondPlayer)
        telemetry.healthSnapshot().droppedMetricKeys shouldBe 1
        telemetry.healthSnapshot().droppedPlayers shouldBe 1
    }

    "failed flush restores the exact batch for retry" {
        val writes = mutableListOf<TelemetryBatch>()
        var fail = true
        val telemetry = ProductTelemetry("spawn", clock, 8, 8) { batch ->
            writes += batch
            if (fail) CompletableFuture.failedFuture(IllegalStateException("database unavailable"))
            else CompletableFuture.completedFuture(Unit)
        }
        telemetry.record(ProductEvent.CONTRACT_ACCEPTED, ProductDimension("path:farming"), 4)

        runCatching { telemetry.flush().join() }
        telemetry.record(ProductEvent.PASSPORT_OPEN, ProductDimension.NONE, 2)
        telemetry.healthSnapshot().pendingMetricKeys shouldBe 2
        telemetry.healthSnapshot().flushFailures shouldBe 1

        fail = false
        telemetry.flush().join()

        writes shouldHaveSize 2
        writes[1].batchId shouldBe writes[0].batchId
        writes[1].counters shouldBe writes[0].counters
        telemetry.healthSnapshot().pendingMetricKeys shouldBe 1
        telemetry.flush().join()
        writes shouldHaveSize 3
        writes[2].counters.shouldContainExactly(
            mapOf(ProductMetricKey(ProductEvent.PASSPORT_OPEN, ProductDimension.NONE) to 2L),
        )
        telemetry.healthSnapshot().lastSuccessfulFlushEpochMillis shouldBe now.toEpochMilli()
    }

    "shutdown flush drains events queued behind an in-flight batch" {
        val writes = mutableListOf<TelemetryBatch>()
        val firstWrite = CompletableFuture<Unit>()
        val telemetry = ProductTelemetry("spawn", clock, 8, 8) { batch ->
            writes += batch
            if (writes.size == 1) firstWrite else CompletableFuture.completedFuture(Unit)
        }

        telemetry.record(ProductEvent.PASSPORT_OPEN, ProductDimension.NONE)
        telemetry.flush()
        telemetry.record(ProductEvent.PERK_BOARD_OPEN, ProductDimension.NONE)

        val drained = telemetry.flushAll()
        drained.isDone shouldBe false
        firstWrite.complete(Unit)
        drained.join()

        writes shouldHaveSize 2
        writes[1].counters.shouldContainExactly(
            mapOf(ProductMetricKey(ProductEvent.PERK_BOARD_OPEN, ProductDimension.NONE) to 1L),
        )
        telemetry.healthSnapshot().pendingMetricKeys shouldBe 0
    }
})
