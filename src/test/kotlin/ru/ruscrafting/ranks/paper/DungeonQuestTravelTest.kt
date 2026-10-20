package ru.ruscrafting.ranks.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Player
import ru.arc.dungeons.api.ArcDungeonNavigation
import ru.ruscrafting.ranks.config.DungeonQuestDestination
import ru.ruscrafting.ranks.config.QuestTravelLocation

class DungeonQuestTravelTest : StringSpec({
    val destination = DungeonQuestDestination("spawn", "em_adventurers_guild", 306.5, 78.0, 204.5, -90f, 0f)
    val destinations = mapOf("em_id_the_mines" to destination)

    "a mechanic destination uses cross-server navigation and rejects unrelated stale buttons" {
        val player = mockk<Player>(relaxed = true)
        val navigation = mockk<ArcDungeonNavigation>()
        val locations = mapOf("workshop" to QuestTravelLocation(setOf("furniture.craft"), destination))
        every { player.server.servicesManager.load(ArcDungeonNavigation::class.java) } returns navigation
        every { navigation.teleport(player, "spawn", "em_adventurers_guild", 306.5, 78.0, 204.5, -90f, 0f) } returns true
        travelToQuestLocation(player, "furniture.craft", "workshop", locations) { false } shouldBe true
        travelToQuestLocation(player, "harvest", "workshop", locations) { error("wrong objective") } shouldBe false
        travelToQuestLocation(player, "furniture.craft", "deleted", locations) { error("deleted location") } shouldBe false
        verify(exactly = 1) { navigation.teleport(any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { player.performCommand(any()) }
    }

    "specific quests use the configured cross-server entrance without a local EliteMobs runtime" {
        val player = mockk<Player>(relaxed = true)
        val navigation = mockk<ArcDungeonNavigation>()
        every { player.server.pluginManager.isPluginEnabled("EliteMobs") } returns false
        every { player.server.servicesManager.load(ArcDungeonNavigation::class.java) } returns navigation
        every { navigation.teleport(player, "spawn", "em_adventurers_guild", 306.5, 78.0, 204.5, -90f, 0f) } returns true

        travelToDungeonEntrance(player, "dungeon.complete:em_id_the_mines", destinations) shouldBe true

        verify(exactly = 1) { navigation.teleport(player, "spawn", "em_adventurers_guild", 306.5, 78.0, 204.5, -90f, 0f) }
        verify(exactly = 0) { player.performCommand(any()) }
        verify(exactly = 0) { player.world }
    }

    "generic dungeon goals keep the existing portal route and its native guard" {
        val player = mockk<Player>(relaxed = true)
        every { player.performCommand("dungeon tp") } returns true

        travelToDungeonEntrance(player, "dungeon.complete", destinations) { error("native command owns this guard") } shouldBe true

        verify(exactly = 1) { player.performCommand("dungeon tp") }
        verify(exactly = 0) { player.server.servicesManager }
    }

    "unknown and malformed specific destinations fail without a generic hub fallback" {
        val player = mockk<Player>(relaxed = true)

        listOf("dungeon.complete:unknown", "dungeon.complete:em_id_the_mines say unsafe", "travel").forEach { objective ->
            travelToDungeonEntrance(player, objective, destinations) { error("unconfigured travel must stop first") } shouldBe false
        }

        verify(exactly = 0) { player.performCommand(any()) }
        verify(exactly = 0) { player.server.servicesManager }
    }

    "players inside an instance retain the existing leave-first response" {
        val player = mockk<Player>(relaxed = true)
        every { player.performCommand("dungeon tp") } returns true

        travelToDungeonEntrance(player, "dungeon.complete:em_id_the_mines", destinations) { true } shouldBe true

        verify(exactly = 1) { player.performCommand("dungeon tp") }
        verify(exactly = 0) { player.server.servicesManager }
    }

    "missing or rejecting typed navigation reports failed admission" {
        val player = mockk<Player>(relaxed = true)
        every { player.server.servicesManager.load(ArcDungeonNavigation::class.java) } returns null
        travelToDungeonEntrance(player, "dungeon.complete:em_id_the_mines", destinations) { false } shouldBe false

        val navigation = mockk<ArcDungeonNavigation>()
        every { player.server.servicesManager.load(ArcDungeonNavigation::class.java) } returns navigation
        every { navigation.teleport(any(), any(), any(), any(), any(), any(), any(), any()) } returns false
        travelToDungeonEntrance(player, "dungeon.complete:em_id_the_mines", destinations) { false } shouldBe false

        verify(exactly = 0) { player.performCommand(any()) }
    }
})
