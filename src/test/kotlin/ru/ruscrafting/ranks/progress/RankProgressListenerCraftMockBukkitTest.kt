package ru.ruscrafting.ranks.progress

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.papermc.paper.event.inventory.ItemCraftedEvent
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.CraftingInventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.Recipe
import ru.arc.config.Config
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.perk.FractionalProgressBonus
import ru.ruscrafting.ranks.perk.PerkCatalogLoader
import ru.ruscrafting.ranks.perk.PerkId
import ru.ruscrafting.ranks.perk.PerkProgressModifier
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

class RankProgressListenerCraftMockBukkitTest : StringSpec({
    "four bread outputs from one shift click and later crafts in the same view all reach the buffer" {
        CraftProgressFixture().use { fixture ->
            fixture.click(InventoryAction.MOVE_TO_OTHER_INVENTORY)
            // Paper runs each recipe in a Shift craft separately, without another CraftItemEvent.
            repeat(4) { fixture.crafted(Material.BREAD, 1) }
            fixture.flush().single().also {
                it.metric shouldBe ProgressMetric.PRODUCTION_ACTIONS
                it.delta shouldBe 44L // Four configured 10-point actions, with the existing 10% perk.
                it.questDeltas shouldBe mapOf("craft:bread" to 4L)
                it.questActions.sumOf(QuestAction::amount) shouldBe 4L
            }

            repeat(2) {
                fixture.click(InventoryAction.PICKUP_ALL)
                fixture.crafted(Material.BREAD, 1)
            }
            fixture.flush().single().questDeltas shouldBe mapOf("craft:bread" to 2L)
        }
    }

    "multi-item output counts items once when a partial shift transfer emits its remainder again" {
        CraftProgressFixture().use { fixture ->
            fixture.crafted(Material.OAK_PLANKS, 4)
            // Native quickMoveStack first emits the complete recipe, then onTake emits
            // the three items still in the result slot before dropping that remainder.
            fixture.remainingResult = ItemStack(Material.OAK_PLANKS, 3)
            fixture.crafted(Material.OAK_PLANKS, 4)
            fixture.crafted(Material.OAK_PLANKS, 3)

            fixture.flush().single().also {
                it.delta shouldBe 22L
                it.questDeltas shouldBe mapOf("craft:oak_planks" to 8L)
                it.questActions.sumOf(QuestAction::amount) shouldBe 8L
            }
        }
    }

    "cancelled and inventory-full clicks without a completed output never award progress" {
        CraftProgressFixture().use { fixture ->
            fixture.player.inventory.storageContents = Array(36) { ItemStack(Material.STONE, 64) }
            fixture.remainingResult = ItemStack(Material.BREAD)
            fixture.click(InventoryAction.MOVE_TO_OTHER_INVENTORY)
            fixture.click(InventoryAction.PICKUP_ALL, cancelled = true)
            fixture.click(InventoryAction.NOTHING)
            fixture.crafted(Material.AIR, 0)

            fixture.flush() shouldBe emptyList()
        }
    }

    "completed outputs retain source material world and game-mode eligibility gates" {
        CraftProgressFixture().use { fixture ->
            val allowed = fixture.settings
            val collection = allowed.collection
            fixture.settings = allowed.copy(collection = collection.copy(
                crafting = collection.crafting.copy(source = collection.crafting.source.copy(enabled = false)),
            ))
            fixture.crafted(Material.BREAD, 1)
            fixture.settings = allowed.copy(collection = collection.copy(
                crafting = collection.crafting.copy(materials = collection.crafting.materials.copy(excluded = setOf("BREAD"))),
            ))
            fixture.crafted(Material.BREAD, 1)
            fixture.settings = allowed.copy(collection = collection.copy(excludedWorlds = setOf(fixture.player.world.name)))
            fixture.crafted(Material.BREAD, 1)
            fixture.settings = allowed
            fixture.player.gameMode = GameMode.CREATIVE
            fixture.crafted(Material.BREAD, 1)
            fixture.flush() shouldBe emptyList()

            fixture.player.gameMode = GameMode.SURVIVAL
            fixture.crafted(Material.BREAD, 1)
            fixture.flush().single().questDeltas shouldBe mapOf("craft:bread" to 1L)
        }
    }
})

private class CraftProgressFixture : AutoCloseable {
    private val paper = MockBukkitTestRuntime.open()
    private val plugin = paper.createSimplePlugin("ArcRanksCraftProgressTest")
    val player = paper.addPlayer("Baker")
    private val root = Files.createTempDirectory("arcranks-craft-progress")
    var settings = ArcRanksSettings.load(root) { "unused-test-password" }.let { loaded ->
        loaded.copy(collection = loaded.collection.copy(
            crafting = loaded.collection.crafting.copy(source = loaded.collection.crafting.source.copy(amount = 10)),
        ))
    }
    var remainingResult: ItemStack? = null
    private val inventory = mockk<CraftingInventory>(relaxed = true) {
        every { result } answers { remainingResult }
    }
    private val view = mockk<InventoryView>(relaxed = true) {
        every { player } returns this@CraftProgressFixture.player
        every { topInventory } returns inventory
        every { type } returns InventoryType.WORKBENCH
    }
    private val writes = mutableListOf<ProgressMutation.Add>()
    private val buffer = ProgressBuffer(64) { _, mutations ->
        writes += mutations.map { it as ProgressMutation.Add }
        CompletableFuture.completedFuture(Unit)
    }
    private val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))

    init {
        player.openInventory(view)
        val catalog = PerkCatalogLoader(Config(root, "perks.yml")).load()
        val modifier = PerkProgressModifier(buffer, catalog, FractionalProgressBonus()) {
            listOf(PerkId("industry_momentum"))
        }
        paper.server.pluginManager.registerEvents(RankProgressListener(
            buffer,
            MovementAccumulator { MovementTuning(16.0, true) },
            modifier,
            { settings },
            tasks,
        ), plugin)
    }

    fun click(action: InventoryAction, cancelled: Boolean = false) {
        val recipe = mockk<Recipe> { every { result } returns ItemStack(Material.BREAD) }
        val click = if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) ClickType.SHIFT_LEFT else ClickType.LEFT
        CraftItemEvent(recipe, view, InventoryType.SlotType.RESULT, 0, click, action).apply {
            isCancelled = cancelled
        }.callEvent()
    }

    fun crafted(material: Material, amount: Int) {
        ItemCraftedEvent(player, ItemStack(material, amount)).callEvent()
    }

    fun flush(): List<ProgressMutation.Add> {
        buffer.flush(player.uniqueId).join()
        return writes.toList().also { writes.clear() }
    }

    override fun close() {
        tasks.close()
        paper.close()
    }
}
