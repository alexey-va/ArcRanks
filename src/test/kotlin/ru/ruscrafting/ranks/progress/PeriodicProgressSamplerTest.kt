package ru.ruscrafting.ranks.progress

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.Player
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.perk.FractionalProgressBonus
import ru.ruscrafting.ranks.perk.PerkCatalog
import ru.ruscrafting.ranks.perk.PerkProgressModifier
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler

class PeriodicProgressSamplerTest : StringSpec({
    "active creative players receive active minutes without community or wealth progress" {
        val world = mockk<World>()
        val worldId = UUID.randomUUID()
        every { world.name } returns "world"
        every { world.uid } returns worldId
        val first = creativePlayer(world, UUID.randomUUID())
        val second = creativePlayer(world, UUID.randomUUID())
        val server = mockk<Server>()
        every { server.onlinePlayers } returns listOf(first, second)
        every { server.getPlayer(first.uniqueId) } returns first
        every { server.getPlayer(second.uniqueId) } returns second
        val settings = ArcRanksSettings.loadFresh(Files.createTempDirectory("arcranks-creative-sampling")) {
            "test-secret"
        }
        val writes = mutableMapOf<UUID, List<ProgressMutation>>()
        val buffer = ProgressBuffer(maximumEntries = 16) { playerId, mutations ->
            writes[playerId] = mutations
            CompletableFuture.completedFuture(Unit)
        }
        val modifier = PerkProgressModifier(
            buffer = buffer,
            catalog = mockk<PerkCatalog>(relaxed = true),
            fractionalBonus = FractionalProgressBonus(),
            selectedPerks = { emptyList() },
        )

        val scheduler = TestTaskScheduler()
        val tasks = LifecycleTaskScope(scheduler)
        val work = BudgetedTaskQueue(tasks, maximumSteps = 1)
        val pendingWorker = ArrayDeque<Runnable>()
        PeriodicProgressSampler(server, { settings }, buffer, modifier, null, tasks, work,
            Executor(pendingWorker::addLast), { throw it }).sampleMinutes(2)
        scheduler.tick(8)
        writes.size shouldBe 0
        pendingWorker.removeFirst().run()
        scheduler.tick(8)
        buffer.flushAll().join()

        writes.values.flatten().map(ProgressMutation::metric) shouldContainExactlyInAnyOrder listOf(
            ProgressMetric.ACTIVE_MINUTES,
            ProgressMetric.ACTIVE_MINUTES,
        )
        writes.values.flatten().filterIsInstance<ProgressMutation.Add>().map(ProgressMutation.Add::delta) shouldBe listOf(2, 2)
        work.close()
        tasks.close()
    }

    "immutable neighbor planning preserves worlds and exact three dimensional distance" {
        val world = UUID.randomUUID()
        val first = RankSamplePlayer(UUID.randomUUID(), world, -1.0, 64.0, 0.0, true, null)
        val near = RankSamplePlayer(UUID.randomUUID(), world, 2.0, 64.0, 0.0, true, null)
        val high = RankSamplePlayer(UUID.randomUUID(), world, -1.0, 68.0, 0.0, true, null)
        val foreign = near.copy(id = UUID.randomUUID(), world = UUID.randomUUID())
        val creative = near.copy(id = UUID.randomUUID(), gameplay = false)
        communityNeighbors(listOf(first, near, high, foreign, creative), 3.0) shouldBe mapOf(
            first.id to 1, near.id to 1, high.id to 0, foreign.id to 0,
        )
    }
})

private fun creativePlayer(world: World, playerId: UUID): Player = mockk<Player>().also { player ->
    every { player.uniqueId } returns playerId
    every { player.isOnline } returns true
    every { player.gameMode } returns GameMode.CREATIVE
    every { player.world } returns world
    every { player.location } returns Location(world, 0.0, 64.0, 0.0)
    every { player.idleDuration } returns Duration.ZERO
}
