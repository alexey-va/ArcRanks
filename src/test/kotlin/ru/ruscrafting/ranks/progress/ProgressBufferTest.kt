package ru.ruscrafting.ranks.progress

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import ru.ruscrafting.ranks.domain.ProgressMetric
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ProgressBufferTest : StringSpec({
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
})
