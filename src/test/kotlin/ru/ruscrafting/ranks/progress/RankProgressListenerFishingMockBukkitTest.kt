package ru.ruscrafting.ranks.progress

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.FishHook
import org.bukkit.entity.Item
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.inventory.ItemStack
import ru.arc.config.Config
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.perk.FractionalProgressBonus
import ru.ruscrafting.ranks.perk.PerkCatalogLoader
import ru.ruscrafting.ranks.perk.PerkProgressModifier
import ru.ruscrafting.ranks.quest.QuestPlanCodec
import ru.ruscrafting.ranks.quest.QuestProgressDiagnostics
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

class RankProgressListenerFishingMockBukkitTest : StringSpec({
    "caught cod and salmon reach the ordered buffer and complete the choose-two fishing collection" {
        FishingProgressFixture().use { fixture ->
            repeat(2) { fixture.fish(Material.COD) }
            repeat(2) { fixture.fish(Material.SALMON) }
            val mutation = fixture.flush().single()
            mutation.metric shouldBe ProgressMetric.CROPS_HARVESTED
            mutation.delta shouldBe fixture.settings.collection.fishing.amount * 4
            mutation.questDeltas shouldBe mapOf("fish:cod" to 2L, "fish:salmon" to 2L)

            // Exact persisted plan and values from the reported completed card.
            val plan = QuestPlanCodec.decode("QP1|DISTINCT|2|fish:cod~2~fish_cod;fish:salmon~2~fish_salmon;fish:pufferfish~1~fish_pufferfish;fish:tropical_fish~1~fish_tropical_fish")
            val values = mutation.questActions.sortedBy(QuestAction::sequence).fold(plan.initialValues) { values, action ->
                plan.advance(values, action.objective, action.amount)
            }
            values shouldBe listOf(2L, 2L, 0L, 0L)
            plan.progress(values) shouldBe 2L
            plan.completed(values) shouldBe true
            fixture.lines.size shouldBe 4
            fixture.lines.all { "result=accepted" in it && "reason=-" in it } shouldBe true

            fixture.fish(Material.PUFFERFISH)
            fixture.fish(Material.TROPICAL_FISH)
            fixture.flush().single().questDeltas shouldBe mapOf("fish:pufferfish" to 1L, "fish:tropical_fish" to 1L)
        }
    }

    "cancelled catches record their reason and never reach the buffer" {
        FishingProgressFixture().use { fixture ->
            fixture.fish(Material.COD, cancelled = true)

            fixture.flush() shouldBe emptyList()
            fixture.diagnostics.latest(fixture.player.uniqueId, "fish:cod") shouldBe "event_cancelled"
            fixture.lines.single().also { line ->
                ("player=${fixture.player.uniqueId}" in line) shouldBe true
                ("world=${fixture.player.world.name}" in line) shouldBe true
                ("mode=SURVIVAL" in line) shouldBe true
                ("state=CAUGHT_FISH" in line) shouldBe true
                ("material=COD" in line && "amount=1" in line) shouldBe true
                ("result=rejected" in line && "reason=event_cancelled" in line) shouldBe true
            }
        }
    }

    "ineligible and disabled catches retain their guards and other fishing stages are silent" {
        FishingProgressFixture().use { fixture ->
            val allowed = fixture.settings
            fixture.settings = allowed.copy(collection = allowed.collection.copy(
                fishing = allowed.collection.fishing.copy(enabled = false),
            ))
            fixture.fish(Material.SALMON)
            fixture.diagnostics.latest(fixture.player.uniqueId, "fish:salmon") shouldBe "source_disabled"

            fixture.settings = allowed
            fixture.player.gameMode = GameMode.CREATIVE
            fixture.fish(Material.PUFFERFISH)
            fixture.diagnostics.latest(fixture.player.uniqueId, "fish:pufferfish") shouldBe "context_ineligible"

            fixture.player.gameMode = GameMode.SURVIVAL
            fixture.fish(Material.COD, state = PlayerFishEvent.State.BITE)
            fixture.fish(Material.COD, state = PlayerFishEvent.State.CAUGHT_ENTITY)
            fixture.flush() shouldBe emptyList()
            fixture.lines.size shouldBe 2
        }
    }
})

private class FishingProgressFixture : AutoCloseable {
    private val paper = MockBukkitTestRuntime.open()
    private val plugin = paper.createSimplePlugin("ArcRanksFishingProgressTest")
    val player = paper.addPlayer("Angler")
    private val root = Files.createTempDirectory("arcranks-fishing-progress")
    var settings = ArcRanksSettings.load(root) { "unused-test-password" }
    val diagnostics = QuestProgressDiagnostics()
    val lines = mutableListOf<String>()
    private val writes = mutableListOf<ProgressMutation.Add>()
    private val buffer = ProgressBuffer(64) { _, mutations ->
        writes += mutations.map { it as ProgressMutation.Add }
        CompletableFuture.completedFuture(Unit)
    }
    private val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))

    init {
        val catalog = PerkCatalogLoader(Config(root, "perks.yml")).load()
        val modifier = PerkProgressModifier(buffer, catalog, FractionalProgressBonus()) { emptyList() }
        paper.server.pluginManager.registerEvents(RankProgressListener(
            buffer,
            MovementAccumulator { MovementTuning(16.0, true) },
            modifier,
            { settings },
            tasks,
            diagnostics = diagnostics,
            fishDiagnostic = lines::add,
        ), plugin)
    }

    fun fish(material: Material, cancelled: Boolean = false, state: PlayerFishEvent.State = PlayerFishEvent.State.CAUGHT_FISH) {
        val item = mockk<Item> { every { itemStack } returns ItemStack(material) }
        PlayerFishEvent(player, item, mockk<FishHook>(), state).apply {
            isCancelled = cancelled
        }.callEvent()
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
