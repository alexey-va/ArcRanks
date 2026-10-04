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
import org.bukkit.event.entity.EntityAirChangeEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityExhaustionEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.player.PlayerExpChangeEvent
import org.bukkit.event.player.PlayerItemDamageEvent
import org.bukkit.inventory.AnvilInventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.view.AnvilView
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

    "legacy configured fishing rod perks still respect strongest value and eligibility" {
        fixture("farming_angler", "farming_angler_master") {
            catalog = PerkCatalog(catalog.perks.map {
                when (it.id.value) {
                    "farming_angler" -> it.copy(effect = PerkEffectKind.FISHING_ROD_PRESERVATION, basisPoints = 3000)
                    "farming_angler_master" -> it.copy(effect = PerkEffectKind.FISHING_ROD_PRESERVATION, basisPoints = 5000)
                    else -> it
                }
            })
            fun damage(item: ItemStack = ItemStack(Material.FISHING_ROD)) = PlayerItemDamageEvent(player, item, 10)
            val vanilla = damage()
            listener.onWear(vanilla)
            vanilla.damage shouldBe 5 // The selected 50% perk wins over the 30% perk.

            val custom = damage(ItemStack(Material.FISHING_ROD).also {
                it.editMeta { meta -> meta.setCustomModelData(123) }
            })
            listener.onWear(custom)
            custom.damage shouldBe 10

            val cancelled = damage().also { it.isCancelled = true }
            listener.onWear(cancelled)
            cancelled.damage shouldBe 10

            settings = settings.copy(features = settings.features.copy(perks = false))
            val disabled = damage()
            listener.onWear(disabled)
            disabled.damage shouldBe 10

            settings = settings.copy(features = settings.features.copy(perks = true))
            settings = settings.copy(collection = settings.collection.copy(excludedWorlds = setOf(player.world.name)))
            val excluded = damage()
            listener.onWear(excluded)
            excluded.damage shouldBe 10
        }
    }

    "air preservation changes only nonnegative submerged air loss" {
        fixture("exploration_diver", "exploration_deep_diver") {
            fun airChange(amount: Int, underwater: Boolean = true, current: Int = 100) =
                EntityAirChangeEvent(mockk<Player>().also { target ->
                    every { target.gameMode } returns GameMode.SURVIVAL
                    every { target.world } returns player.world
                    every { target.uniqueId } returns player.uniqueId
                    every { target.isUnderWater } returns underwater
                    every { target.remainingAir } returns current
                }, amount)

            val loss = airChange(90)
            listener.onAirChange(loss)
            loss.amount shouldBe 95

            val recovery = airChange(120)
            listener.onAirChange(recovery)
            recovery.amount shouldBe 120

            val drowning = airChange(-20)
            listener.onAirChange(drowning)
            drowning.amount shouldBe -20

            val surface = airChange(90, underwater = false)
            listener.onAirChange(surface)
            surface.amount shouldBe 90

            val cancelled = airChange(90).also { it.isCancelled = true }
            listener.onAirChange(cancelled)
            cancelled.amount shouldBe 90

            settings = settings.copy(features = settings.features.copy(perks = false))
            val disabled = airChange(90)
            listener.onAirChange(disabled)
            disabled.amount shouldBe 90
        }
    }

    "nearby armor preservation requires an eligible visible companion and vanilla armor" {
        fixture("community_guardian") {
            fun damage(item: ItemStack = ItemStack(Material.DIAMOND_CHESTPLATE)) =
                PlayerItemDamageEvent(player, item, 10)

            val alone = damage()
            listener.onWear(alone)
            alone.damage shouldBe 10

            val companion = paper.addPlayer("ArmorCompanion")
            companion.teleport(player.location)
            companion.gameMode = GameMode.SPECTATOR
            val ineligible = damage()
            listener.onWear(ineligible)
            ineligible.damage shouldBe 10

            companion.gameMode = GameMode.SURVIVAL
            val plugin = paper.createSimplePlugin("ArmorPerkVisibilityTest")
            player.hidePlayer(plugin, companion)
            val hidden = damage()
            listener.onWear(hidden)
            hidden.damage shouldBe 10
            player.showPlayer(plugin, companion)

            val together = damage()
            listener.onWear(together)
            together.damage shouldBe 8

            val custom = damage(ItemStack(Material.DIAMOND_CHESTPLATE).also {
                it.editMeta { meta -> meta.setCustomModelData(123) }
            })
            listener.onWear(custom)
            custom.damage shouldBe 10

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

    "legacy configured food perk applies to all reasons and overlaps movement by strongest value" {
        fixture("farming_provisions", "exploration_mastery") {
            catalog = PerkCatalog(catalog.perks.map {
                if (it.id.value == "farming_provisions") it.copy(effect = PerkEffectKind.FOOD_EXHAUSTION_REDUCTION, basisPoints = 2000) else it
            })
            for (reason in EntityExhaustionEvent.ExhaustionReason.entries) {
                val event = EntityExhaustionEvent(player, reason, 10f)
                listener.onExhaustion(event)
                event.exhaustion shouldBe 8f
            }

            val cancelled = EntityExhaustionEvent(player, EntityExhaustionEvent.ExhaustionReason.ATTACK, 10f)
                .also { it.isCancelled = true }
            listener.onExhaustion(cancelled)
            cancelled.exhaustion shouldBe 10f
        }
    }

    "anvil reduction changes only affordable native combinations and stays idempotent" {
        fixture("industry_anvil", "trade_anvil") {
            val first = ItemStack(Material.DIAMOND_PICKAXE)
            val second = ItemStack(Material.DIAMOND_PICKAXE)
            val result = ItemStack(Material.DIAMOND_PICKAXE)
            val (combination, cost) = anvilEvent(player, first, second, result, 10)
            listener.onAnvilPrepare(combination)
            cost() shouldBe 8 // The selected 20% perk wins over the 15% perk.

            listener.onAnvilPrepare(PrepareAnvilEvent(combination.view, result.clone()))
            cost() shouldBe 8

            val (fractional, fractionalCost) = anvilEvent(player, first, second, result, 3)
            listener.onAnvilPrepare(fractional)
            fractionalCost() shouldBe 3 // Flooring avoids turning 20% into a stochastic 1/3 discount.

            val (renameOnly, renameCost) = anvilEvent(player, first, null, first.clone(), 5, renameText = "Named")
            listener.onAnvilPrepare(renameOnly)
            renameCost() shouldBe 5

            val (missingResult, missingResultCost) = anvilEvent(player, first, second, null, 10)
            listener.onAnvilPrepare(missingResult)
            missingResultCost() shouldBe 10

            val (tooExpensive, expensiveCost) = anvilEvent(player, first, second, result, 40)
            listener.onAnvilPrepare(tooExpensive)
            expensiveCost() shouldBe 40

            val (oneLevel, minimumCost) = anvilEvent(player, first, second, result, 1)
            listener.onAnvilPrepare(oneLevel)
            minimumCost() shouldBe 1

            fun custom(stack: ItemStack) = stack.clone().also {
                it.editMeta { meta -> meta.setCustomModelData(123) }
            }
            val (customFirst, customFirstCost) = anvilEvent(player, custom(first), second, result, 10)
            listener.onAnvilPrepare(customFirst)
            customFirstCost() shouldBe 10

            val (customSecond, customSecondCost) = anvilEvent(player, first, custom(second), result, 10)
            listener.onAnvilPrepare(customSecond)
            customSecondCost() shouldBe 10

            val (customResult, customResultCost) = anvilEvent(player, first, second, custom(result), 10)
            listener.onAnvilPrepare(customResult)
            customResultCost() shouldBe 10

            listener.onInventoryClose(InventoryCloseEvent(combination.view))
            val (afterClose, afterCloseCost) = anvilEvent(player, first, second, result, 10)
            listener.onAnvilPrepare(afterClose)
            afterCloseCost() shouldBe 8
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
    var catalog = PerkCatalogLoader(Config(root, "perks.yml")).load()
    val listener = GameplayPerkListener({ settings }, { catalog }, { selected }, { 0 })
}

private fun fixture(vararg ids: String, block: GameplayFixture.() -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        val player = paper.addPlayer("PerkPlayer")
        player.gameMode = GameMode.SURVIVAL
        GameplayFixture(paper, player, ids).block()
    }
}

private fun anvilEvent(
    player: Player,
    first: ItemStack,
    second: ItemStack?,
    result: ItemStack?,
    cost: Int,
    maximum: Int = 40,
    renameText: String? = "",
): Pair<PrepareAnvilEvent, () -> Int> {
    val inventory = mockk<AnvilInventory>()
    every { inventory.getItem(0) } returns first
    every { inventory.getItem(1) } returns second

    val view = mockk<AnvilView>()
    every { view.topInventory } returns inventory
    every { view.player } returns player
    every { view.renameText } returns renameText
    every { view.maximumRepairCost } returns maximum
    var currentCost = cost
    every { view.repairCost } answers { currentCost }
    every { view.repairCost = any() } answers { currentCost = firstArg() }

    return PrepareAnvilEvent(view, result) to { currentCost }
}
