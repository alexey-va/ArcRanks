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
import ru.ruscrafting.ranks.quest.DailyRewardState
import ru.ruscrafting.ranks.quest.QuestMode
import ru.ruscrafting.ranks.quest.QuestPeriod
import ru.ruscrafting.ranks.quest.QuestPlan
import ru.ruscrafting.ranks.quest.QuestReplaceResult
import ru.ruscrafting.ranks.quest.QuestStep
import ru.ruscrafting.ranks.text.RankLocale
import java.nio.file.Files
import java.time.LocalDate
import java.time.Instant
import java.util.concurrent.CompletableFuture

class DailyQuestMenuMockBukkitTest : StringSpec({
    afterTest { ConfigManager.clear() }
    "twenty-one daily and three weekly cards fit above the footer and weekly right-click never replaces" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("MixedQuestChest")
            val player = paper.addPlayer("MixedQuestChestViewer")
            val root = Files.createTempDirectory("mixed-quest-menu")
            val settings = ArcRanksSettings.loadFresh(root) { "unused-test-password" }
            val locale = RankLocale.fresh(root, { "ru" }, { false })
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val quests = (0 until 24).map { index ->
                DailyQuestProgress(DailyQuest.ALL.first().copy(id = "goal_$index",
                    period = if (index < 21) QuestPeriod.DAILY else QuestPeriod.WEEKLY,
                    crateKey = if (index < 21) null else "case_weekly", crateKeyAmount = if (index < 21) 0 else 1,
                    money = if (index < 21) 50 else 0), 0)
            }
            val board = DailyQuestBoard(LocalDate.parse("2026-10-28"), quests, replacementsLeft = 2)
            var replacements = 0
            val menu = DailyQuestMenu({ settings }, { locale }, { CompletableFuture.completedFuture(board) },
                tasks, ArcRanksMenuLayouts(root), { 1 }, {},
                replace = { _, _, _ -> replacements++; CompletableFuture.completedFuture(QuestReplaceResult.REPLACED) },
                now = { Instant.parse("2026-10-28T13:00:00Z") })
            val plain = PlainTextComponentSerializer.plainText()
            try {
                menu.open(player)
                paper.performTicks(3)
                val inventory = player.openInventory.topInventory
                inventory.size shouldBe 54
                val geometry = DailyQuestLayout(24)
                geometry.slots.distinct().size shouldBe 24
                geometry.slots.all { it in 9..44 } shouldBe true
                geometry.slots.forEachIndexed { index, slot ->
                    plain.serialize(inventory.getItem(slot)!!.itemMeta.displayName()!!)
                        .startsWith(if (index < 21) "[День]" else "[Неделя]") shouldBe true
                }
                plain.serialize(inventory.getItem(45)!!.itemMeta.displayName()!!).contains("Ранги") shouldBe true
                plain.serialize(inventory.getItem(49)!!.itemMeta.displayName()!!).contains("Клановые задания") shouldBe true
                plain.serialize(inventory.getItem(53)!!.itemMeta.displayName()!!).contains("Обновить") shouldBe true
                val slot = geometry.slots.last()
                val lore = inventory.getItem(slot)!!.itemMeta.lore()!!.joinToString("\n", transform = plain::serialize)
                lore.contains("Осталось: 4 дн. 11 ч.") shouldBe true
                lore.contains("2026") shouldBe false
                lore.contains("До:") shouldBe false
                inventory.getItem(geometry.slots.first())!!.itemMeta.lore()!!.joinToString("\n", transform = plain::serialize)
                    .contains("Осталось: 11 ч.") shouldBe true
                lore.contains("1 × ключ «Недельная реликвия»") shouldBe true
                lore.contains("ПКМ") shouldBe false
                lore.contains("💰") shouldBe false
                menu.onClick(InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, slot,
                    ClickType.RIGHT, InventoryAction.PICKUP_HALF))
                paper.performTicks(2)
                replacements shouldBe 0
            } finally { tasks.close() }
        }
    }

    "clan entry stays in the footer and does not fail the personal board when its lookup fails" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("DailyClanChest")
            val player = paper.addPlayer("ClanChestViewer")
            val root = Files.createTempDirectory("daily-clan-menu")
            val settings = ArcRanksSettings.loadFresh(root) { "unused-test-password" }
            val locale = RankLocale.fresh(root, { "ru" }, { false })
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val board = DailyQuestBoard(LocalDate.parse("2026-10-26"), listOf(DailyQuestProgress(DailyQuest.ALL.first(), 4)))
            var opened = 0
            val menu = DailyQuestMenu({ settings }, { locale }, { CompletableFuture.completedFuture(board) },
                tasks, ArcRanksMenuLayouts(root), { 1 }, {},
                openClanQuests = { opened++ },
                loadClanQuests = { CompletableFuture.failedFuture(IllegalStateException("unavailable")) })
            val plain = PlainTextComponentSerializer.plainText()
            try {
                menu.open(player)
                paper.performTicks(3)
                val inventory = player.openInventory.topInventory
                inventory.getItem(13)!!.type shouldBe org.bukkit.Material.WHEAT
                val entry = inventory.getItem(22)!!
                plain.serialize(entry.itemMeta.displayName()!!).contains("Клановые задания") shouldBe true
                entry.itemMeta.lore()!!.joinToString("\n", transform = plain::serialize).contains("Состояние клана сейчас недоступно") shouldBe true
                menu.onClick(InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, 22,
                    ClickType.LEFT, InventoryAction.PICKUP_ALL))
                opened shouldBe 1
            } finally { tasks.close() }
        }
    }

    "left click tracks, right click still replaces, and merged lore explains the objective" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("DailyQuestMenuTest")
            val player = paper.addPlayer("MenuTester")
            val root = Files.createTempDirectory("daily-menu")
            val settings = ArcRanksSettings.loadFresh(root) { "unused-test-password" }
            val locale = RankLocale.fresh(root, { "ru" }, { false })
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val quest = DailyQuest.ALL.first().copy(textId = "harvest", objective = "harvest:wheat", itemPreset = "enchant_supply", itemAmount = 2)
            val board = DailyQuestBoard(LocalDate.parse("2026-09-08"), listOf(DailyQuestProgress(quest, 88)), 2, selectedFocus = ru.ruscrafting.ranks.domain.SpecializationPath.FARMING)
            var selected: String? = null
            var replacements = 0
            val menu = DailyQuestMenu({ settings }, { locale }, { CompletableFuture.completedFuture(board) },
                tasks, ArcRanksMenuLayouts(root), { 1 }, {},
                replace = { _, _, _ -> replacements++; CompletableFuture.completedFuture(QuestReplaceResult.REPLACED) },
                selected = { _, _ -> selected },
                diagnose = { _, _ -> "not_mature" },
                track = { _, _, id -> selected = if (selected == id) null else id; CompletableFuture.completedFuture(Unit) })
            val plain = PlainTextComponentSerializer.plainText()
            try {
                menu.open(player); paper.performTicks(3)
                val card = player.openInventory.topInventory.getItem(13)!!
                val lore = card.itemMeta.lore()!!.joinToString("\n", transform = plain::serialize)
                lore.contains("Соберите зрелый урожай: ${quest.target} растений.") shouldBe true
                lore.contains("ЛКМ") shouldBe true
                lore.contains("ПКМ") shouldBe true
                lore.contains("<quest-name>") shouldBe false
                lore.contains("По вашему пути") shouldBe true
                lore.contains("Почти готово") shouldBe true
                lore.contains("2 × Припасы чародея") shouldBe true
                lore.contains("ещё не выросло") shouldBe false
                menu.onClick(InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, 13,
                    ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY)); paper.performTicks(3)
                selected shouldBe null
                player.openInventory.topInventory.getItem(13)!!.itemMeta.lore()!!.joinToString("\n", transform = plain::serialize)
                    .contains("ещё не выросло") shouldBe true
                val statusSlot = ArcRanksMenuLayouts(root).slot(ArcRanksMenuLayouts.DAILY_QUESTS, "status")
                menu.onClick(InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, statusSlot,
                    ClickType.LEFT, InventoryAction.PICKUP_ALL)); paper.performTicks(3)
                selected shouldBe null
                replacements shouldBe 0
                val status = player.openInventory.topInventory.getItem(statusSlot)!!.itemMeta
                plain.serialize(status.displayName()!!) shouldBe "Выполнено: 0/1"
                status.lore()!!.joinToString("\n", transform = plain::serialize).contains("сменить отображение") shouldBe false
                val click = InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, 13,
                    ClickType.LEFT, InventoryAction.PICKUP_ALL)
                menu.onClick(click); paper.performTicks(3)
                click.isCancelled shouldBe true
                selected shouldBe quest.id
                replacements shouldBe 0
                plain.serialize(player.openInventory.topInventory.getItem(13)!!.itemMeta.displayName()!!).startsWith("✔") shouldBe true
                menu.onClick(InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, 13,
                    ClickType.LEFT, InventoryAction.PICKUP_ALL)); paper.performTicks(3)
                selected shouldBe null
                menu.onClick(InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, 13,
                    ClickType.RIGHT, InventoryAction.PICKUP_HALF)); paper.performTicks(3)
                replacements shouldBe 1
            } finally { tasks.close() }
        }
    }

    "completed collection lore distinguishes required choices from unused alternatives" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("DailyQuestCollectionMenuTest")
            val player = paper.addPlayer("CollectionMenuTester")
            val root = Files.createTempDirectory("daily-collection-menu")
            val settings = ArcRanksSettings.loadFresh(root) { "unused-test-password" }
            val locale = RankLocale.fresh(root, { "ru" }, { false })
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val plan = QuestPlan(QuestMode.DISTINCT, listOf(
                QuestStep("fish:cod", 2, "fish_cod"),
                QuestStep("fish:salmon", 2, "fish_salmon"),
                QuestStep("fish:pufferfish", 1, "fish_pufferfish"),
                QuestStep("fish:tropical_fish", 1, "fish_tropical_fish"),
            ), requiredDistinct = 2)
            val quest = DailyQuest.ALL.first().copy(
                id = "angler_collection", textId = "angler_collection", objective = "quest.angler_collection",
                target = plan.target, plan = plan,
            )
            val board = DailyQuestBoard(LocalDate.parse("2026-10-23"), listOf(
                DailyQuestProgress(quest, 2, DailyRewardState.GRANTED, listOf(2, 2, 0, 0)),
            ))
            val menu = DailyQuestMenu({ settings }, { locale }, { CompletableFuture.completedFuture(board) },
                tasks, ArcRanksMenuLayouts(root), { 1 }, {})
            val plain = PlainTextComponentSerializer.plainText()
            try {
                menu.open(player)
                paper.performTicks(3)
                fun lore() = player.openInventory.topInventory.getItem(13)!!.itemMeta.lore()!!.joinToString("\n", transform = plain::serialize)
                lore().contains("Достаточно любых 2 из 4 вариантов.") shouldBe true
                lore().contains("Условия выполнены.") shouldBe true
                lore().contains("0/1") shouldBe false
                menu.onClick(InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, 13,
                    ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY))
                paper.performTicks(3)
                val details = lore()
                details.contains("✓ Поймайте треску 2/2") shouldBe true
                details.contains("✓ Поймайте лосося 2/2") shouldBe true
                details.contains("Поймайте иглобрюха — не требуется") shouldBe true
                details.contains("Поймайте тропическую рыбу — не требуется") shouldBe true
                details.contains("0/1") shouldBe false
            } finally {
                tasks.close()
            }
        }
    }
})
