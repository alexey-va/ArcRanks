package ru.ruscrafting.ranks.progress

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import ru.ruscrafting.ranks.api.ClanMembership
import java.util.UUID

class ClanCombatParticipationTest : StringSpec({
    "elite encounter retains credited damage after a participant reconnects" {
        val id = UUID.randomUUID()
        val world = mockk<World>()
        val entity = mockk<Entity> { every { this@mockk.world } returns world; every { location } returns Location(world, 0.0, 0.0, 0.0) }
        val old = mockk<Player> { every { uniqueId } returns id; every { isOnline } returns false }
        val current = mockk<Player> {
            every { uniqueId } returns id; every { isOnline } returns true
            every { this@mockk.world } returns world; every { location } returns Location(world, 1.0, 0.0, 0.0)
        }
        eliteCombatParticipants(mapOf(old to 12.0), entity, { current }) { true } shouldBe setOf(id)
        eliteCombatParticipants(mapOf(old to 0.0), entity, { current }) { true } shouldBe emptySet()
        eliteCombatParticipants(mapOf(old to Double.NaN), entity, { current }) { true } shouldBe emptySet()
        eliteCombatParticipants(mapOf(old to 12.0), entity, { current }) { false } shouldBe emptySet()
        every { current.location } returns Location(world, 65.0, 0.0, 0.0)
        eliteCombatParticipants(mapOf(old to 12.0), entity, { current }) { true } shouldBe emptySet()
    }
    "dungeon contribution requires starting and finishing in the same clan" {
        val first = UUID.randomUUID(); val second = UUID.randomUUID(); val late = UUID.randomUUID()
        val original = ClanMembership("justteams:1", "First", setOf(first, second))
        val switched = ClanMembership("justteams:2", "Second", setOf(second, late))
        completedDungeonClanParticipants(mapOf(first to original, second to original), setOf(first, second, late)) {
            if (it == first) original else switched
        } shouldBe listOf(first to original)
        completedDungeonClanParticipants(mapOf(first to original, second to original), setOf(first)) { original }
            .map { it.first } shouldBe listOf(first)
    }
})
