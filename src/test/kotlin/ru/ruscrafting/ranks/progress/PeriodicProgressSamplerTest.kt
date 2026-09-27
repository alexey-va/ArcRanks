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

        PeriodicProgressSampler(server, { settings }, buffer, modifier, economy = null).sampleMinutes(2)
        buffer.flushAll().join()

        writes.values.flatten().map(ProgressMutation::metric) shouldContainExactlyInAnyOrder listOf(
            ProgressMetric.ACTIVE_MINUTES,
            ProgressMetric.ACTIVE_MINUTES,
        )
        writes.values.flatten().filterIsInstance<ProgressMutation.Add>().map(ProgressMutation.Add::delta) shouldBe listOf(2, 2)
    }
})

private fun creativePlayer(world: World, playerId: UUID): Player = mockk<Player>().also { player ->
    every { player.uniqueId } returns playerId
    every { player.gameMode } returns GameMode.CREATIVE
    every { player.world } returns world
    every { player.location } returns Location(world, 0.0, 64.0, 0.0)
    every { player.idleDuration } returns Duration.ZERO
}
