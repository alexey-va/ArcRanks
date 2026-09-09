package ru.ruscrafting.ranks.perk

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.data.Ageable
import org.bukkit.entity.ExperienceOrb
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityExhaustionEvent
import org.bukkit.event.player.PlayerExpChangeEvent
import org.bukkit.event.player.PlayerItemDamageEvent
import org.bukkit.inventory.ItemStack
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.config.ArcRanksSettings
import java.nio.file.Files

class GameplayPerkListenerTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "harvest changes only one actual mature vanilla drop and honours cancellation and disabled state" {
        fixture("farming_mastery") {
            val crop = player.location.block
            crop.type = Material.WHEAT
            val data = crop.blockData as Ageable
            data.age = data.maximumAge
            crop.blockData = data
            var stack = ItemStack(Material.WHEAT, 2)
            val drop = mockk<Item>()
            every { drop.itemStack } answers { stack }
            every { drop.itemStack = any() } answers { stack = firstArg() }
            // MockBukkit's generic BlockState drops Ageable data. Paper guarantees the pre-break snapshot.
            fun event(): BlockDropItemEvent {
                val state = mockk<org.bukkit.block.BlockState>()
                every { state.type } returns Material.WHEAT
                every { state.blockData } returns data
                return BlockDropItemEvent(crop, state, player, mutableListOf(drop))
            }
            stack.maxStackSize shouldBe 64
            listener.onCropDrop(event())
            stack.amount shouldBe 3
            listener.onCropDrop(event().also { it.isCancelled = true })
            stack.amount shouldBe 3
            data.age = 0
            crop.blockData = data
            listener.onCropDrop(event())
            stack.amount shouldBe 3
            data.age = data.maximumAge
            crop.blockData = data
            stack = ItemStack(Material.WHEAT, 2).also { item ->
                item.editMeta { it.displayName(net.kyori.adventure.text.Component.text("Custom crop")) }
            }
            listener.onCropDrop(event())
            stack.amount shouldBe 2
            stack = ItemStack(Material.WHEAT, 2)
            settings = settings.copy(features = settings.features.copy(perks = false))
            listener.onCropDrop(event())
            stack.amount shouldBe 2
        }
    }

    "native event dispatch preserves denied harvests and empty drop events" {
        fixture("farming_mastery") {
            paper.server.pluginManager.registerEvents(listener, paper.createSimplePlugin("GameplayPerksTest"))
            val crop = player.location.block
            crop.type = Material.WHEAT
            val event = BlockDropItemEvent(crop, crop.state, player, mutableListOf())
            event.isCancelled = true
            paper.server.pluginManager.callEvent(event)
            event.items.size shouldBe 0
            event.isCancelled shouldBe true
        }
    }

    "wear saves real durability only on matching vanilla tools in eligible context" {
        fixture("industry_mastery") {
            fun damage(type: Material = Material.DIAMOND_PICKAXE) = PlayerItemDamageEvent(player, ItemStack(type), 10)
            val good = damage()
            listener.onWear(good)
            good.damage shouldBe 8
            val wrongTool = damage(Material.DIAMOND_SWORD)
            listener.onWear(wrongTool)
            wrongTool.damage shouldBe 10
            val cancelled = damage().also { it.isCancelled = true }
            listener.onWear(cancelled)
            cancelled.damage shouldBe 10
            val custom = damage().also { it.item.editMeta { meta -> meta.setCustomModelData(123) } }
            listener.onWear(custom)
            custom.damage shouldBe 10
            player.gameMode = GameMode.CREATIVE
            val creative = damage()
            listener.onWear(creative)
            creative.damage shouldBe 10
            player.gameMode = GameMode.SURVIVAL
            settings = settings.copy(collection = settings.collection.copy(excludedWorlds = setOf(player.world.name)))
            val excluded = damage()
            listener.onWear(excluded)
            excluded.damage shouldBe 10
        }
    }

    "movement saves exhaustion without boosting healing or removing hunger effects" {
        fixture("exploration_mastery") {
            for (reason in EntityExhaustionEvent.ExhaustionReason.entries) {
                val event = EntityExhaustionEvent(player, reason, 10f)
                listener.onExhaustion(event)
                event.exhaustion shouldBe if (reason.name in setOf("SPRINT", "SWIM", "JUMP", "JUMP_SPRINT")) 8f else 10f
            }
        }
    }

    "XP bonuses use natural source and current selection without death or bottle recycling" {
        fixture("trade_mastery") {
            fun event(reason: ExperienceOrb.SpawnReason): PlayerExpChangeEvent {
                val source = mockk<ExperienceOrb>()
                every { source.spawnReason } returns reason
                return PlayerExpChangeEvent(player, source, 10)
            }
            for (reason in ExperienceOrb.SpawnReason.entries) {
                val xp = event(reason)
                listener.onExperience(xp)
                xp.amount shouldBe if (reason == ExperienceOrb.SpawnReason.VILLAGER_TRADE) 13 else 10
            }
            selected = setOf(PerkId("industry_contract"))
            val smelt = event(ExperienceOrb.SpawnReason.FURNACE)
            listener.onExperience(smelt)
            smelt.amount shouldBe 12 // deterministic roll=0 rounds the 1.5 bonus up
            selected = emptySet()
            val deselected = event(ExperienceOrb.SpawnReason.FURNACE)
            listener.onExperience(deselected)
            deselected.amount shouldBe 10
            val noSource = PlayerExpChangeEvent(player, 10)
            listener.onExperience(noSource)
            noSource.amount shouldBe 10
        }
    }

    "fall protection is scoped and cancelled damage stays cancelled" {
        fixture("building_contract", "exploration_contract") {
            fun damage(cause: EntityDamageEvent.DamageCause) = mockk<EntityDamageEvent>(relaxed = true).also {
                var value = 10.0
                every { it.entity } returns player
                every { it.cause } returns cause
                every { it.damage } answers { value }
                every { it.damage = any() } answers { value = firstArg() }
            }
            val fall = damage(EntityDamageEvent.DamageCause.FALL)
            listener.onDamage(fall)
            fall.damage shouldBe 7.0 // strongest 30%, never 20+30%
            val lava = damage(EntityDamageEvent.DamageCause.LAVA)
            listener.onDamage(lava)
            lava.damage shouldBe 10.0
            val cancelled = damage(EntityDamageEvent.DamageCause.FALL)
            every { cancelled.isCancelled } returns true
            listener.onDamage(cancelled)
            cancelled.damage shouldBe 10.0
        }
    }

    "community needs a visible companion and protects from monsters but never PvP" {
        fixture("community_mastery", "community_contract") {
            val source = mockk<ExperienceOrb>()
            every { source.spawnReason } returns ExperienceOrb.SpawnReason.ENTITY_DEATH
            val alone = PlayerExpChangeEvent(player, source, 100)
            listener.onExperience(alone)
            alone.amount shouldBe 100
            val companion = paper.addPlayer("Companion")
            companion.gameMode = GameMode.SURVIVAL
            companion.teleport(player.location)
            val together = PlayerExpChangeEvent(player, source, 100)
            listener.onExperience(together)
            together.amount shouldBe 110
            fun hit(attacker: org.bukkit.entity.Entity) = mockk<org.bukkit.event.entity.EntityDamageByEntityEvent>(relaxed = true).also {
                var value = 10.0
                every { it.entity } returns player
                every { it.damager } returns attacker
                every { it.cause } returns EntityDamageEvent.DamageCause.ENTITY_ATTACK
                every { it.damage } answers { value }
                every { it.damage = any() } answers { value = firstArg() }
            }
            val monster = hit(mockk<org.bukkit.entity.Zombie>())
            listener.onDamage(monster)
            monster.damage shouldBe 9.0
            val pvp = hit(companion)
            listener.onDamage(pvp)
            pvp.damage shouldBe 10.0
            companion.gameMode = GameMode.SPECTATOR
            val spectator = PlayerExpChangeEvent(player, source, 100)
            listener.onExperience(spectator)
            spectator.amount shouldBe 100
        }
    }

    "fractional rolls are unbiased for tiny actions and safe at int limit" {
        (0 until 10_000).sumOf { GameplayPerkListener.scaledUnits(1, 500, it) } shouldBe 500
        (0 until 10_000).sumOf { GameplayPerkListener.scaledUnits(7, 1500, it) } shouldBe 10_500
        GameplayPerkListener.scaledUnits(Int.MAX_VALUE, 5000, 9999) shouldBe 1_073_741_823
    }
})

private class GameplayFixture(val paper: MockBukkitTestRuntime, val player: Player, ids: Array<out String>) {
    private val root = Files.createTempDirectory("arcranks-gameplay")
    var settings = ArcRanksSettings.load(root) { "unused-test-password" }
    var selected = ids.map { PerkId(it) }.toSet()
    private val catalog = PerkCatalogLoader(Config(root, "perks.yml")).load()
    val listener = GameplayPerkListener({ settings }, { catalog }, { selected }, { 0 })
}

private fun fixture(vararg ids: String, block: GameplayFixture.() -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        val player = paper.addPlayer("PerkPlayer")
        player.gameMode = GameMode.SURVIVAL
        GameplayFixture(paper, player, ids).block()
    }
}
