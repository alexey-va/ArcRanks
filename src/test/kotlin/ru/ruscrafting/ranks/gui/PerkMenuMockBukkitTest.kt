package ru.ruscrafting.ranks.gui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.config.RankCatalogLoader
import ru.ruscrafting.ranks.domain.*
import ru.ruscrafting.ranks.perk.*
import ru.ruscrafting.ranks.rankstate.RankState
import ru.ruscrafting.ranks.service.RankPlayerService
import ru.ruscrafting.ranks.service.RankPlayerSnapshot
import ru.ruscrafting.ranks.text.RankLocale
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

class PerkMenuMockBukkitTest : StringSpec({
    afterTest { ConfigManager.clear() }

    listOf(false, true).forEach { legacy ->
        "perk pages retain selection and refresh mapping with legacy=$legacy" {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("PerkPages")
                val player = paper.addPlayer("PerkPages")
                val root = Files.createTempDirectory("perk-pages")
                val settings = ArcRanksSettings.loadFresh(root) { "secret" }
                val locale = RankLocale.fresh(root, { "ru" }, { false })
                val allPerks = PerkCatalogLoader(Config(root, "perks.yml")).load()
                val catalog = if (legacy) PerkCatalog(allPerks.perks.filter { it.requiredMastery.ordinal <= 3 }) else allPerks
                val mastery = SpecializationPath.entries.associateWith { MasteryLevel.VI }
                val snapshot = RankPlayerSnapshot(RankState.Exact(RankId("settler")),
                    PlayerProgressProfile(ProgressSnapshot.EMPTY, SpecializationPath.FARMING),
                    null, mastery, PathAvailability.allAvailable(), emptySet())
                val players = mockk<RankPlayerService>()
                every { players.load(player.uniqueId) } returns CompletableFuture.completedFuture(snapshot)
                val selections = mockk<PerkSelectionService>()
                every { selections.selectIntoSlot(any(), any(), any(), any()) } returns CompletableFuture()
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val layouts = ArcRanksMenuLayouts(root)
                val menu = PerkMenu({ settings }, { locale }, { catalog }, players, selections, tasks, null,
                    back = {}, layouts = layouts,
                    masteryThresholds = { RankCatalogLoader(Config(root, "ranks.yml")).loadWithMastery().mastery })
                fun click(slot: Int) = menu.onClick(InventoryClickEvent(player.openInventory,
                    InventoryType.SlotType.CONTAINER, slot, ClickType.LEFT, InventoryAction.PICKUP_ALL))
                fun name(slot: Int) = PlainTextComponentSerializer.plainText().serialize(
                    player.openInventory.topInventory.getItem(slot)!!.itemMeta.displayName()!!)
                try {
                    menu.open(player)
                    paper.performTicks(2)
                    click(layouts.region(ArcRanksMenuLayouts.PERK_SLOTS, "slots").first())
                    val offers = layouts.region(ArcRanksMenuLayouts.PERK_SELECTION, "offers")
                    val firstName = name(offers.first())
                    click(layouts.slot(ArcRanksMenuLayouts.PERK_SELECTION, "status"))
                    val expected = catalog.forPath(SpecializationPath.FARMING)[if (legacy) 0 else 3]
                    name(offers.first()).contains(PlainTextComponentSerializer.plainText().serialize(locale.render(expected.nameKey))) shouldBe true
                    if (legacy) name(offers.first()) shouldBe firstName
                    else (name(offers.first()) != firstName) shouldBe true
                    click(layouts.slot(ArcRanksMenuLayouts.PERK_SELECTION, "refresh"))
                    paper.performTicks(2)
                    click(offers.first())
                    verify(exactly = 1) { selections.selectIntoSlot(player.uniqueId, 1, expected.id, mastery) }
                } finally {
                    player.closeInventory()
                    tasks.close()
                }
            }
        }
    }
})
