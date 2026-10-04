package ru.ruscrafting.ranks.perk

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.data.Ageable
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.player.PlayerAttemptPickupItemEvent
import org.bukkit.inventory.ItemStack
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.ranks.config.ArcRanksSettings
import java.nio.file.Files
import java.util.UUID

class HarvestPerkListenerTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "replant waits for the final drop outcome and consumes one matching seed" {
        harvestFixture {
            val (crop, event) = breakCrop(Material.WHEAT)
            player.inventory.setItem(8, ItemStack(Material.WHEAT_SEEDS, 2))
            var syntheticPlacements = 0
            val probe = object : Listener {
                @EventHandler
                fun onPlace(@Suppress("UNUSED_PARAMETER") event: BlockPlaceEvent) {
                    syntheticPlacements++
                }
            }
            paper.server.pluginManager.registerEvents(probe, paper.createSimplePlugin("HarvestPlacementProbe"))

            listener.onBlockDrop(event)
            crop.type shouldBe Material.AIR
            paper.performTicks(4)
            crop.type shouldBe Material.AIR
            paper.performTicks(1)

            crop.type shouldBe Material.WHEAT
            (crop.blockData as Ageable).age shouldBe 0
            player.inventory.getItem(8)!!.amount shouldBe 1
            syntheticPlacements shouldBe 0
        }
    }

    "replant skips late cancellation sneak missing seeds invalid farmland and denied placement" {
        listOf("cancel", "sneak", "seed", "farmland", "protection").forEach { reason ->
            harvestFixture {
                val (crop, event) = breakCrop(Material.CARROTS)
                player.inventory.setItem(8, ItemStack(Material.CARROT, 2))
                listener.onBlockDrop(event)
                when (reason) {
                    "cancel" -> event.isCancelled = true
                    "sneak" -> player.isSneaking = true
                    "seed" -> player.inventory.setItem(8, null)
                    "farmland" -> crop.getRelative(BlockFace.DOWN).type = Material.DIRT
                    "protection" -> canPlantAllowed = false
                }
                paper.performTicks(5)
                crop.type shouldBe Material.AIR
                if (reason == "seed") player.inventory.getItem(8) shouldBe null
                else player.inventory.getItem(8)!!.amount shouldBe 2
            }
        }
    }

    "replant requires a mature supported vanilla crop and respects generation and eligibility" {
        listOf("unripe", "unsupported", "reload", "disabled").forEach { reason ->
            harvestFixture {
                val cropType = if (reason == "unsupported") Material.NETHER_WART else Material.POTATOES
                val (crop, event) = breakCrop(cropType, mature = reason != "unripe")
                player.inventory.setItem(8, ItemStack(Material.POTATO, 2))
                listener.onBlockDrop(event)
                when (reason) {
                    "reload" -> epoch++
                    "disabled" -> selected = emptySet()
                }
                paper.performTicks(5)
                crop.type shouldBe Material.AIR
                player.inventory.getItem(8)!!.amount shouldBe 2
            }
        }
    }

    "pickup runs after the native delay, dispatches the pickup event and leaves overflow in place" {
        harvestFixture {
            for (slot in 0 until 36) player.inventory.setItem(slot, ItemStack(Material.DIRT, 64))
            player.inventory.setItem(0, ItemStack(Material.COBBLESTONE, 62))
            val (block, state) = brokenBlock(Material.STONE)
            val item = drop(block, ItemStack(Material.COBBLESTONE, 4))
            item.pickupDelay = 0
            var pickupEvents = 0
            var reportedRemaining = -1
            val probe = object : Listener {
                @EventHandler(priority = EventPriority.MONITOR)
                fun onPickup(event: EntityPickupItemEvent) {
                    if (event.entity.uniqueId == player.uniqueId && event.item.uniqueId == item.uniqueId) {
                        pickupEvents++
                        reportedRemaining = event.remaining
                    }
                }
            }
            paper.server.pluginManager.registerEvents(probe, paper.createSimplePlugin("HarvestPickupProbe"))
            val event = BlockDropItemEvent(block, state, player, mutableListOf(item))

            listener.onBlockDrop(event)
            player.inventory.getItem(0)!!.amount shouldBe 62
            paper.performTicks(9)
            player.inventory.getItem(0)!!.amount shouldBe 62
            paper.performTicks(1)

            player.inventory.getItem(0)!!.amount shouldBe 64
            item.itemStack.amount shouldBe 2
            pickupEvents shouldBe 1
            reportedRemaining shouldBe 2
        }
    }

    "pickup skips cancelled drops, cancelled pickup attempts, changed stacks, custom items and foreign ownership" {
        listOf("cancel-drop", "cancel-attempt", "cancel-pickup", "changed", "custom", "owner", "delay", "reload", "disabled").forEach { reason ->
            harvestFixture {
                val (block, state) = brokenBlock(Material.STONE)
                val contents = ItemStack(Material.COBBLESTONE, 2)
                if (reason == "custom") contents.editMeta { it.setCustomModelData(19) }
                val item = drop(block, contents)
                item.pickupDelay = 0
                if (reason == "delay") item.pickupDelay = 20
                if (reason == "owner") item.owner = UUID.randomUUID()
                val event = BlockDropItemEvent(block, state, player, mutableListOf(item))
                val probe = object : Listener {
                    @EventHandler
                    fun onAttempt(event: PlayerAttemptPickupItemEvent) {
                        if (event.item.uniqueId == item.uniqueId && reason == "cancel-attempt") event.isCancelled = true
                    }

                    @EventHandler
                    fun onPickup(event: EntityPickupItemEvent) {
                        if (event.item.uniqueId != item.uniqueId) return
                        if (reason == "cancel-pickup") event.isCancelled = true
                        if (reason == "changed") event.item.itemStack = ItemStack(Material.DIRT, 3)
                        if (reason == "reload") epoch++
                        if (reason == "disabled") selected = emptySet()
                    }
                }
                paper.server.pluginManager.registerEvents(probe, paper.createSimplePlugin("HarvestPickupGuard"))
                listener.onBlockDrop(event)
                when (reason) {
                    "cancel-drop" -> event.isCancelled = true
                }
                paper.performTicks(10)
                player.inventory.storageContents.filterNotNull().sumOf { it.amount } shouldBe 0
                if (reason == "changed") item.itemStack.type shouldBe Material.DIRT
                else item.itemStack.amount shouldBe 2
            }
        }
    }

    "pickup excludes drops from a tile-state container and honors disabled perks" {
        harvestFixture {
            val (block, state) = brokenBlock(Material.CHEST)
            val item = drop(block, ItemStack(Material.CHEST))
            item.pickupDelay = 0
            val event = BlockDropItemEvent(block, state, player, mutableListOf(item))
            listener.onBlockDrop(event)
            paper.performTicks(10)
            player.inventory.storageContents.filterNotNull().shouldBe(emptyList())

            selected = emptySet()
            val (stone, stoneState) = brokenBlock(Material.STONE)
            val stoneDrop = drop(stone, ItemStack(Material.COBBLESTONE))
            stoneDrop.pickupDelay = 0
            listener.onBlockDrop(BlockDropItemEvent(stone, stoneState, player, mutableListOf(stoneDrop)))
            paper.performTicks(10)
            player.inventory.storageContents.filterNotNull().shouldBe(emptyList())
        }
    }
})

private class HarvestFixture(val paper: MockBukkitTestRuntime, val player: Player) : AutoCloseable {
    private val root = Files.createTempDirectory("arcranks-harvest")
    var settings = ArcRanksSettings.loadFresh(root) { "unused" }
    private val catalog = PerkCatalogLoader(Config(root, "perks.yml")).load()
    private val taskPlugin = paper.createSimplePlugin("HarvestPerks")
    private val tasks = LifecycleTaskScope(BukkitTaskScheduler(taskPlugin))
    var epoch = 1L
    var canPlantAllowed = true
    var selected: Set<PerkId> = catalog.perks
        .filter { it.effect == PerkEffectKind.CROP_REPLANT || it.effect == PerkEffectKind.BLOCK_DROP_PICKUP }
        .map(PerkDefinition::id)
        .toSet()
    val listener = HarvestPerkListener(
        { settings }, { catalog }, { selected }, tasks, { epoch }, { _, _, _ -> canPlantAllowed },
    )

    init {
        paper.server.pluginManager.registerEvents(listener, taskPlugin)
    }

    fun breakCrop(material: Material, mature: Boolean = true): Pair<Block, BlockDropItemEvent> {
        val block = player.location.block.getRelative(BlockFace.UP)
        block.world.loadChunk(block.x shr 4, block.z shr 4)
        block.getRelative(BlockFace.DOWN).type = Material.FARMLAND
        block.type = material
        val ageable = block.blockData as Ageable
        ageable.age = if (mature) ageable.maximumAge else 0
        block.blockData = ageable
        // MockBukkit has no native block-support implementation; stub only that Paper boundary.
        val planted = spyk(ageable.clone())
        every { planted.isSupported(any<org.bukkit.Location>()) } returns true
        val original = spyk(ageable.clone())
        every { original.clone() } returns planted
        val state = mockk<BlockState>()
        every { state.type } returns material
        every { state.blockData } returns original
        block.type = Material.AIR
        return block to BlockDropItemEvent(block, state, player, mutableListOf())
    }

    fun brokenBlock(material: Material): Pair<Block, BlockState> {
        val block = player.location.block.getRelative(BlockFace.UP)
        block.type = material
        val state = block.state
        block.type = Material.AIR
        return block to state
    }

    // Item ownership/player-pickup APIs are unimplemented in MockBukkit 4.116.3.
    fun drop(block: Block, stack: ItemStack): Item {
        var contents = stack.clone()
        var delay = 0
        var ownerId: UUID? = null
        var removed = false
        return mockk<Item>().also { item ->
            every { item.uniqueId } returns UUID.randomUUID()
            every { item.world } returns block.world
            every { item.isValid } answers { !removed }
            every { item.isDead } answers { removed }
            every { item.canPlayerPickup() } returns true
            every { item.owner } answers { ownerId }
            every { item.owner = any() } answers { ownerId = firstArg() }
            every { item.thrower } returns null
            every { item.pickupDelay } answers { delay }
            every { item.pickupDelay = any() } answers { delay = firstArg() }
            every { item.itemStack } answers { contents.clone() }
            every { item.itemStack = any() } answers { contents = firstArg<ItemStack>().clone() }
            every { item.remove() } answers { removed = true }
        }
    }

    override fun close() = tasks.close()
}

private fun harvestFixture(block: HarvestFixture.() -> Unit) {
    failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open().use { paper ->
            val player = object : org.mockbukkit.mockbukkit.entity.PlayerMock(paper.server, "HarvestPlayer") {
                override fun getCanPickupItems(): Boolean = true
            }
            paper.server.addPlayer(player)
            player.gameMode = GameMode.SURVIVAL
            HarvestFixture(paper, player).use { it.block() }
        }
    }
}
