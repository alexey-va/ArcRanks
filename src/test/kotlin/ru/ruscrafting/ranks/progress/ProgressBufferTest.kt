package ru.ruscrafting.ranks.progress

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import ru.ruscrafting.ranks.domain.ProgressMetric
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ProgressBufferTest : StringSpec({
    "material-specific objectives survive a large pending batch" {
        val player = UUID.randomUUID()
        val writes = mutableListOf<List<ProgressMutation>>()
        val buffer = ProgressBuffer(maximumEntries = 8) { _, batch ->
            writes += batch
            CompletableFuture.completedFuture(Unit)
        }
        (1..256).forEach { index ->
            buffer.recordCounter(player, ProgressMetric.PRODUCTION_ACTIONS, 1, mapOf("craft:item_$index" to 1L)) shouldBe true
        }
        buffer.flush(player).join()
        val mutation = writes.single().single() as ProgressMutation.Add
        mutation.delta shouldBe 256
        mutation.questDeltas.size shouldBe 256
        mutation.questDeltas.values.sum() shouldBe 256
    }

    "counter writes add and maximum writes keep the highest value" {
        val player = UUID.randomUUID()
        val writes = mutableListOf<List<ProgressMutation>>()
        val buffer = ProgressBuffer(maximumEntries = 8) { _, batch ->
            writes += batch
            CompletableFuture.completedFuture(Unit)
        }

        buffer.recordCounter(player, ProgressMetric.CROPS_HARVESTED, 2) shouldBe true
        buffer.recordCounter(player, ProgressMetric.CROPS_HARVESTED, 3) shouldBe true
        buffer.recordMaximum(player, ProgressMetric.WEALTH_PEAK, 100) shouldBe true
        buffer.recordMaximum(player, ProgressMetric.WEALTH_PEAK, 80) shouldBe true
        buffer.flush(player).join()

        writes.single().shouldContainExactlyInAnyOrder(
            ProgressMutation.Add(ProgressMetric.CROPS_HARVESTED, 5),
            ProgressMutation.Maximum(ProgressMetric.WEALTH_PEAK, 100),
        )
        buffer.pendingCount() shouldBe 0
    }

    "capacity rejects only a new player metric entry" {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val buffer = ProgressBuffer(maximumEntries = 1) { _, _ -> CompletableFuture.completedFuture(Unit) }

        buffer.recordCounter(first, ProgressMetric.BLOCKS_PLACED, 1) shouldBe true
        buffer.recordCounter(first, ProgressMetric.BLOCKS_PLACED, 1) shouldBe true
        buffer.recordCounter(second, ProgressMetric.BLOCKS_PLACED, 1) shouldBe false
        buffer.pendingCount() shouldBe 1
        buffer.rejectedCount() shouldBe 1L
    }

    "lowered dynamic capacity preserves owned entries and accepts their updates" {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val third = UUID.randomUUID()
        var capacity = 2
        val delivered = mutableMapOf<UUID, List<ProgressMutation>>()
        val buffer = ProgressBuffer({ capacity }) { playerId, mutations ->
            delivered[playerId] = mutations
            CompletableFuture.completedFuture(Unit)
        }

        buffer.recordCounter(first, ProgressMetric.BLOCKS_PLACED, 1) shouldBe true
        buffer.recordCounter(second, ProgressMetric.BLOCKS_PLACED, 2) shouldBe true

        capacity = 1
        buffer.recordCounter(first, ProgressMetric.BLOCKS_PLACED, 3) shouldBe true
        buffer.recordCounter(second, ProgressMetric.BLOCKS_PLACED, 4) shouldBe true
        buffer.recordCounter(third, ProgressMetric.BLOCKS_PLACED, 1) shouldBe false
        buffer.pendingCount() shouldBe 2
        buffer.flushAll().join()

        delivered[first] shouldBe listOf(ProgressMutation.Add(ProgressMetric.BLOCKS_PLACED, 4))
        delivered[second] shouldBe listOf(ProgressMutation.Add(ProgressMetric.BLOCKS_PLACED, 6))
        buffer.pendingCount() shouldBe 0
    }

    "failed flush restores the exact mutations" {
        val player = UUID.randomUUID()
        var fail = true
        val delivered = mutableListOf<List<ProgressMutation>>()
        val buffer = ProgressBuffer(maximumEntries = 8) { _, batch ->
            if (fail) CompletableFuture.failedFuture(IllegalStateException("storage down"))
            else CompletableFuture.completedFuture(Unit).also { delivered += batch }
        }

        buffer.recordCounter(player, ProgressMetric.PRODUCTION_ACTIONS, 4)
        runCatching { buffer.flush(player).join() }
        buffer.pendingCount() shouldBe 1

        fail = false
        buffer.flush(player).join()
        delivered.single() shouldBe listOf(ProgressMutation.Add(ProgressMetric.PRODUCTION_ACTIONS, 4))
        buffer.pendingCount() shouldBe 0
    }

    "failed flush retries merged quest deltas without losing per-objective amounts" {
        val player = UUID.randomUUID()
        var fail = true
        val delivered = mutableListOf<List<ProgressMutation>>()
        val buffer = ProgressBuffer(maximumEntries = 8) { _, batch ->
            if (fail) CompletableFuture.failedFuture(IllegalStateException("storage down"))
            else CompletableFuture.completedFuture(Unit).also { delivered += batch }
        }

        buffer.recordCounter(
            player,
            ProgressMetric.CROPS_HARVESTED,
            4,
            mapOf("path.farming" to 2L, "path.farming.special" to 1L),
        ) shouldBe true
        buffer.recordCounter(
            player,
            ProgressMetric.CROPS_HARVESTED,
            3,
            mapOf("path.farming" to 5L, "path.industry" to 2L),
        ) shouldBe true

        runCatching { buffer.flush(player).join() }
        buffer.pendingCount() shouldBe 1

        fail = false
        buffer.flush(player).join()
        delivered.single() shouldBe listOf(
            ProgressMutation.Add(
                ProgressMetric.CROPS_HARVESTED,
                7,
                mapOf("path.farming" to 7L, "path.farming.special" to 1L, "path.industry" to 2L),
            ),
        )
        buffer.pendingCount() shouldBe 0
    }

    "writes arriving during a flush remain pending for the next flush" {
        val player = UUID.randomUUID()
        val firstCompletion = CompletableFuture<Unit>()
        val batches = mutableListOf<List<ProgressMutation>>()
        val buffer = ProgressBuffer(maximumEntries = 8) { _, batch ->
            batches += batch
            if (batches.size == 1) firstCompletion else CompletableFuture.completedFuture(Unit)
        }

        buffer.recordCounter(player, ProgressMetric.TRAVEL_BLOCKS, 10)
        val firstFlush = buffer.flush(player)
        buffer.recordCounter(player, ProgressMetric.TRAVEL_BLOCKS, 3)
        firstCompletion.complete(Unit)
        firstFlush.join()
        buffer.flush(player).join()

        batches shouldBe listOf(
            listOf(ProgressMutation.Add(ProgressMetric.TRAVEL_BLOCKS, 10)),
            listOf(ProgressMutation.Add(ProgressMetric.TRAVEL_BLOCKS, 3)),
        )
    }

    "flush is a barrier for writes accepted while an earlier batch is in flight" {
        val player = UUID.randomUUID()
        val firstCompletion = CompletableFuture<Unit>()
        val batches = mutableListOf<List<ProgressMutation>>()
        val buffer = ProgressBuffer(maximumEntries = 8) { _, batch ->
            batches += batch
            if (batches.size == 1) firstCompletion else CompletableFuture.completedFuture(Unit)
        }

        buffer.recordCounter(player, ProgressMetric.TRAVEL_BLOCKS, 10)
        buffer.flush(player)
        buffer.recordCounter(player, ProgressMetric.TRAVEL_BLOCKS, 3)
        val barrier = buffer.flush(player)

        barrier.isDone shouldBe false
        firstCompletion.complete(Unit)
        barrier.join()

        batches shouldBe listOf(
            listOf(ProgressMutation.Add(ProgressMetric.TRAVEL_BLOCKS, 10)),
            listOf(ProgressMutation.Add(ProgressMetric.TRAVEL_BLOCKS, 3)),
        )
        buffer.pendingCount() shouldBe 0
    }

    "flush barrier is bounded and does not wait for writes accepted after the call" {
        val player = UUID.randomUUID()
        val firstCompletion = CompletableFuture<Unit>()
        val secondCompletion = CompletableFuture<Unit>()
        val batches = mutableListOf<List<ProgressMutation>>()
        val buffer = ProgressBuffer(maximumEntries = 8) { _, batch ->
            batches += batch
            when (batches.size) {
                1 -> firstCompletion
                2 -> secondCompletion
                else -> CompletableFuture.completedFuture(Unit)
            }
        }

        buffer.recordCounter(player, ProgressMetric.TRAVEL_BLOCKS, 10)
        buffer.flush(player)
        buffer.recordCounter(player, ProgressMetric.TRAVEL_BLOCKS, 3)
        val barrier = buffer.flush(player)
        firstCompletion.complete(Unit)
        batches.size shouldBe 2

        buffer.recordCounter(player, ProgressMetric.TRAVEL_BLOCKS, 4)
        secondCompletion.complete(Unit)
        barrier.join()

        barrier.isDone shouldBe true
        buffer.pendingCount() shouldBe 1
        buffer.flush(player).join()
        batches.last() shouldBe listOf(ProgressMutation.Add(ProgressMetric.TRAVEL_BLOCKS, 4))
    }

    "flushAll waits for an in-flight batch even when no mutation is pending" {
        val player = UUID.randomUUID()
        val completion = CompletableFuture<Unit>()
        val buffer = ProgressBuffer(maximumEntries = 8) { _, _ -> completion }

        buffer.recordCounter(player, ProgressMetric.BLOCKS_PLACED, 2)
        buffer.flush(player)
        val barrier = buffer.flushAll()

        barrier.isDone shouldBe false
        completion.complete(Unit)
        barrier.join()
        barrier.isDone shouldBe true
        buffer.pendingCount() shouldBe 0
    }
})
