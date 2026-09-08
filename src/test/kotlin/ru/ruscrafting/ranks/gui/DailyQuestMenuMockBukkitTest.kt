package ru.ruscrafting.ranks.gui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.quest.DailyQuest
import ru.ruscrafting.ranks.quest.DailyQuestBoard
import ru.ruscrafting.ranks.quest.DailyQuestProgress
import ru.ruscrafting.ranks.quest.QuestReplaceResult
import ru.ruscrafting.ranks.text.RankLocale
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.CompletableFuture

class DailyQuestMenuMockBukkitTest : StringSpec({
    afterTest { ConfigManager.clear() }
    "left click tracks, right click still replaces, and merged lore explains the objective" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("DailyQuestMenuTest")
            val player = paper.addPlayer("MenuTester")
            val root = Files.createTempDirectory("daily-menu")
            val settings = ArcRanksSettings.loadFresh(root) { "unused-test-password" }
            val locale = RankLocale.fresh(root, { "ru" }, { false })
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val quest = DailyQuest.ALL.first().copy(textId = "harvest", objective = "harvest:wheat")
            val board = DailyQuestBoard(LocalDate.parse("2026-09-08"), listOf(DailyQuestProgress(quest, 12)), 2)
            var selected: String? = null
            var replacements = 0
            val menu = DailyQuestMenu({ settings }, { locale }, { CompletableFuture.completedFuture(board) },
                tasks, ArcRanksMenuLayouts(root), { 1 }, {},
                replace = { _, _, _ -> replacements++; CompletableFuture.completedFuture(QuestReplaceResult.REPLACED) },
                selected = { _, _ -> selected },
                track = { _, _, id -> selected = if (selected == id) null else id; CompletableFuture.completedFuture(Unit) })
            val plain = PlainTextComponentSerializer.plainText()
            try {
                menu.open(player); paper.performTicks(3)
                val card = player.openInventory.topInventory.getItem(10)!!
                val lore = card.itemMeta.lore()!!.joinToString("\n", transform = plain::serialize)
                lore.contains("зрелые") shouldBe true
                lore.contains("ЛКМ") shouldBe true
                lore.contains("ПКМ") shouldBe true
                lore.contains("<quest-name>") shouldBe false
                val click = InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, 10,
                    ClickType.LEFT, InventoryAction.PICKUP_ALL)
                menu.onClick(click); paper.performTicks(3)
                click.isCancelled shouldBe true
                selected shouldBe quest.id
                replacements shouldBe 0
                plain.serialize(player.openInventory.topInventory.getItem(10)!!.itemMeta.displayName()!!).startsWith("✔") shouldBe true
                menu.onClick(InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, 10,
                    ClickType.LEFT, InventoryAction.PICKUP_ALL)); paper.performTicks(3)
                selected shouldBe null
                menu.onClick(InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, 10,
                    ClickType.RIGHT, InventoryAction.PICKUP_HALF)); paper.performTicks(3)
                replacements shouldBe 1
            } finally { tasks.close() }
        }
    }
})
