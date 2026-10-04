package ru.ruscrafting.ranks.perk

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.entity.Wolf
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.text.RankLocale
import java.nio.file.Files
import java.util.UUID

class UtilityPerkListenerTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "refill moves one identical stack after successful consumption and preserves overflow inventory" {
        utilityFixture {
            player.inventory.setItem(0, ItemStack(Material.STONE, 1))
            player.inventory.setItem(10, ItemStack(Material.STONE, 37))
            player.inventory.setItem(11, ItemStack(Material.STONE, 12))
            val event = place()
            player.isOnline shouldBe true
            player.isDead shouldBe false
            event.canBuild() shouldBe true
            event.itemInHand.isSimilar(ItemStack(Material.STONE)) shouldBe true
            player.inventory.heldItemSlot shouldBe 0
            listener.onPlace(event)
            player.inventory.getItem(0)!!.amount shouldBe 1
            player.inventory.setItem(0, null)
            paper.performTicks(2)
            player.inventory.getItem(0)!!.amount shouldBe 37
            player.inventory.getItem(10) shouldBe null
            player.inventory.getItem(11)!!.amount shouldBe 12
        }
    }

    "refill respects late cancellation reload changed slot and replacement item" {
        listOf("cancel", "reload", "slot", "filled", "disabled").forEach { changed ->
            utilityFixture {
                player.inventory.setItem(0, ItemStack(Material.STONE))
                player.inventory.setItem(10, ItemStack(Material.STONE, 37))
                val event = place()
                listener.onPlace(event)
                player.inventory.setItem(0, null)
                when (changed) {
                    "cancel" -> event.isCancelled = true
                    "reload" -> epoch++
                    "slot" -> player.inventory.heldItemSlot = 1
                    "filled" -> player.inventory.setItem(0, ItemStack(Material.DIRT))
                    "disabled" -> selected = emptyList()
                }
                paper.performTicks(2)
                player.inventory.getItem(10)!!.amount shouldBe 37
                player.inventory.getItem(0)?.type shouldBe if (changed == "filled") Material.DIRT else null
            }
        }
    }

    "refill supports offhand but skips denied placement custom blocks and unfinished stack" {
        utilityFixture {
            player.inventory.setItem(10, ItemStack(Material.STONE, 37))
            val event = place(EquipmentSlot.OFF_HAND)
            player.inventory.setItemInOffHand(ItemStack(Material.STONE))
            listener.onPlace(event)
            player.inventory.setItemInOffHand(ItemStack.empty())
            paper.performTicks(2)
            player.inventory.itemInOffHand.amount shouldBe 37
            player.inventory.getItem(10) shouldBe null
        }
        listOf("denied", "custom", "stack").forEach { changed ->
            utilityFixture {
                player.inventory.setItem(10, ItemStack(Material.STONE, 37))
                val item = ItemStack(Material.STONE, if (changed == "stack") 2 else 1)
                if (changed == "custom") item.editMeta { it.setCustomModelData(123) }
                val event = place(item = item)
                if (changed == "denied") event.setBuild(false)
                listener.onPlace(event)
                paper.performTicks(2)
                player.inventory.getItem(10)!!.amount shouldBe 37
                player.inventory.getItem(0) shouldBe null
            }
        }
    }

    "farmland care affects only the equipped player's physical trampling" {
        utilityFixture {
            val block = player.location.block.also { it.type = Material.FARMLAND }
            fun interact(action: Action) = PlayerInteractEvent(player, action, null, block, BlockFace.UP)
            val step = interact(Action.PHYSICAL)
            listener.onTrample(step)
            step.isCancelled shouldBe true
            val click = interact(Action.RIGHT_CLICK_BLOCK)
            listener.onTrample(click)
            click.isCancelled shouldBe false
            selected = emptyList()
            val without = interact(Action.PHYSICAL)
            listener.onTrample(without)
            without.isCancelled shouldBe false
        }
    }

    "pet care protects only own tamed pets from ordinary and sweeping hits with sneak override" {
        utilityFixture {
            val pet = mockk<Wolf>()
            every { pet.isTamed } returns true
            every { pet.ownerUniqueId } returns player.uniqueId
            fun hit(cause: DamageCause = DamageCause.ENTITY_ATTACK): EntityDamageByEntityEvent {
                val event = mockk<EntityDamageByEntityEvent>()
                var cancelled = false
                every { event.isCancelled } answers { cancelled }
                every { event.isCancelled = any() } answers { cancelled = firstArg() }
                every { event.damage } returns 4.0
                every { event.cause } returns cause
                every { event.damager } returns player
                every { event.entity } returns pet
                return event
            }
            for (cause in listOf(DamageCause.ENTITY_ATTACK, DamageCause.ENTITY_SWEEP_ATTACK)) {
                val event = hit(cause)
                listener.onPetDamage(event)
                event.isCancelled shouldBe true
            }
            player.isSneaking = true
            val deliberate = hit()
            listener.onPetDamage(deliberate)
            deliberate.isCancelled shouldBe false
            player.isSneaking = false
            every { pet.ownerUniqueId } returns UUID.randomUUID()
            val other = hit()
            listener.onPetDamage(other)
            other.isCancelled shouldBe false
            every { pet.ownerUniqueId } returns player.uniqueId
            val projectile = hit(DamageCause.PROJECTILE)
            listener.onPetDamage(projectile)
            projectile.isCancelled shouldBe false
        }
    }
})

private class UtilityFixture(val paper: MockBukkitTestRuntime, val player: Player) : AutoCloseable {
    private val root = Files.createTempDirectory("utility-perks")
    private val settings = ArcRanksSettings.loadFresh(root) { "unused" }
    private val catalog = PerkCatalogLoader(Config(root, "perks.yml")).load()
    private val locale = RankLocale.fresh(root, { "ru" }, { false })
    private val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("UtilityPerks")))
    var epoch = 1L
    var selected = listOf("farming_angler", "farming_provisions", "building_craftsmanship").map(::PerkId)
    val listener = UtilityPerkListener({ settings }, { catalog }, { selected }, tasks, { epoch }, { locale })

    fun place(hand: EquipmentSlot = EquipmentSlot.HAND, item: ItemStack = ItemStack(Material.STONE)): BlockPlaceEvent {
        val block = player.location.block
        val old = block.state
        block.type = Material.STONE
        return BlockPlaceEvent(block, old, block.getRelative(BlockFace.DOWN), item, player, true, hand)
    }

    override fun close() = tasks.close()
}

private fun utilityFixture(block: UtilityFixture.() -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        val player = paper.addPlayer("UtilityPlayer").also { it.gameMode = GameMode.SURVIVAL }
        UtilityFixture(paper, player).use { it.block() }
    }
}
