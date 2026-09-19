package ru.ruscrafting.ranks.quest

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.config.RankCatalogLoader
import ru.ruscrafting.ranks.text.RankLocale
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture

class QuestTrackerMockBukkitTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "pin persists, HUD only reports changed progress, and completion clears the pin" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("QuestTrackingTest")
            val player = paper.addPlayer("QuestTester")
            val root = Files.createTempDirectory("quest-tracking")
            ArcRanksSettings.loadFresh(root) { "unused-test-password" }
            val locale = RankLocale.fresh(root, { "ru" }, { false })
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val clock = TrackingTestClock()
            val quest = DailyQuest.ALL.first()
            var board = DailyQuestBoard(DailyQuest.day(clock.instant()), listOf(DailyQuestProgress(quest, 0)))
            val storage = MemoryTrackingRepository()
            val tracker = QuestTracker(plugin, tasks, { DailyQuestCatalog(mapOf("settler" to 1), listOf(quest)) },
                { locale }, storage, { CompletableFuture.completedFuture(board) }, clock)
            try {
                tracker.install()
                paper.performTicks(2)
                val pin = tracker.toggle(player, board, quest.id)
                paper.performTicks(3)
                pin.join()
                storage.values[player.uniqueId] shouldBe TrackedQuest(board.day, quest.id)
                PlainTextComponentSerializer.plainText().serialize(player.nextActionBar()!!).contains("0/100") shouldBe true
                tracker.refresh(player.uniqueId)
                paper.performTicks(2)
                player.nextActionBar() shouldBe null

                board = board.copy(quests = listOf(DailyQuestProgress(quest, 12)))
                tracker.refresh(player.uniqueId)
                paper.performTicks(2)
                player.nextActionBar() shouldBe null
                clock.time = clock.time.plusSeconds(3)
                tracker.refresh(player.uniqueId)
                paper.performTicks(2)
                PlainTextComponentSerializer.plainText().serialize(player.nextActionBar()!!).contains("12/100") shouldBe true

                board = board.copy(quests = listOf(DailyQuestProgress(quest, quest.target)))
                tracker.refresh(player.uniqueId)
                paper.performTicks(2)
                tracker.selected(player.uniqueId, board) shouldBe null
                storage.values[player.uniqueId] shouldBe null
                val completed = PlainTextComponentSerializer.plainText().serialize(player.nextActionBar()!!)
                completed.contains("Цель выполнена") shouldBe true
                completed.contains("<quest-name>") shouldBe false
                completed.contains("<prefix>") shouldBe false
            } finally { tracker.close(); tasks.close() }
        }
    }

    "a stored selection is restored and a replaced or expired quest is cleared" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("QuestRestoreTest")
            val player = paper.addPlayer("RestoreTester")
            val root = Files.createTempDirectory("quest-restoring")
            ArcRanksSettings.loadFresh(root) { "unused-test-password" }
            val locale = RankLocale.fresh(root, { "ru" }, { false })
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val clock = TrackingTestClock()
            val quest = DailyQuest.ALL.first()
            var board = DailyQuestBoard(DailyQuest.day(clock.instant()), listOf(DailyQuestProgress(quest, 8)))
            val storage = MemoryTrackingRepository()
            storage.values[player.uniqueId] = TrackedQuest(board.day, quest.id)
            val tracker = QuestTracker(plugin, tasks, { DailyQuestCatalog(mapOf("settler" to 1), listOf(quest)) },
                { locale }, storage, { CompletableFuture.completedFuture(board) }, clock)
            try {
                tracker.install(); paper.performTicks(3)
                tracker.selected(player.uniqueId, board) shouldBe quest.id
                player.nextActionBar()!!.let { PlainTextComponentSerializer.plainText().serialize(it).contains("8/100") shouldBe true }
                board = board.copy(quests = listOf(DailyQuestProgress(DailyQuest.ALL[1], 0)))
                tracker.selected(player.uniqueId, board) shouldBe null
                tracker.refresh(player.uniqueId); paper.performTicks(2)
                storage.values[player.uniqueId] shouldBe null
                player.nextActionBar() shouldBe null
                // A queued selection callback must not leave its caller waiting during shutdown.
                val pending = tracker.toggle(player, board, board.quests.first().quest.id)
                tracker.close()
                pending.isCancelled shouldBe true
            } finally { tracker.close(); tasks.close() }
        }
    }
    "scoreboard snapshots, display preferences and hidden state share the same pin" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("QuestHudTest")
            val player = paper.addPlayer("HudTester")
            val root = Files.createTempDirectory("quest-hud")
            ArcRanksSettings.loadFresh(root) { "unused-test-password" }
            val locale = RankLocale.fresh(root, { "ru" }, { false })
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val clock = TrackingTestClock()
            val quest = DailyQuest.ALL.first().copy(textId = "harvest", objective = "harvest")
            val completed = DailyQuest.ALL[1]
            var board = DailyQuestBoard(DailyQuest.day(clock.instant()), listOf(
                DailyQuestProgress(completed, completed.target),
                DailyQuestProgress(quest, 12),
            ))
            val storage = MemoryTrackingRepository().also { it.mode = QuestDisplayMode.SCOREBOARD }
            val tracker = QuestTracker(plugin, tasks, { DailyQuestCatalog(mapOf("settler" to 1), listOf(quest)) },
                { locale }, storage, { CompletableFuture.completedFuture(board) }, clock)
            try {
                tracker.install(); paper.performTicks(3)
                tracker.placeholder(player.uniqueId, "quest_active") shouldBe "true"
                storage.values[player.uniqueId] shouldBe TrackedQuest(board.day, quest.id)
                tracker.placeholder(player.uniqueId, "quest_line_2")!!.contains("12/100") shouldBe true
                tracker.placeholder(player.uniqueId, "quest_line_3")!!.contains("/quests") shouldBe true
                tracker.placeholder(player.uniqueId, "quest_line_4") shouldBe ""
                player.nextActionBar() shouldBe null
                tracker.cycleDisplay(player); paper.performTicks(3)
                storage.mode shouldBe QuestDisplayMode.ACTIONBAR
                tracker.placeholder(player.uniqueId, "quest_active") shouldBe "false"
                player.nextActionBar()!!.let { PlainTextComponentSerializer.plainText().serialize(it).contains("12/100") shouldBe true }
                tracker.cycleDisplay(player); paper.performTicks(3)
                storage.mode shouldBe QuestDisplayMode.OFF
                tracker.placeholder(player.uniqueId, "quest_line_1") shouldBe ""
                board = board.copy(quests = listOf(DailyQuestProgress(quest, 100)))
                tracker.refresh(player.uniqueId); paper.performTicks(3)
                player.nextActionBar() shouldBe null
                tracker.selected(player.uniqueId, board) shouldBe null
            } finally { tracker.close(); tasks.close() }
        }
    }

    "scoreboard goal stays on one line when it fits and wraps long text at a word boundary" {
        questGoalParts("Переплавить предметы", "0/64") shouldBe listOf("Переплавить предметы")
        questGoalParts("Сходить в данж «Шахты»", "0/1") shouldBe listOf("Сходить в данж «Шахты»")
        val wrapped = questGoalParts("Подтвердить большую строительную операцию", "12/128")
        wrapped.size shouldBe 2
        wrapped.joinToString(" ") shouldBe "Подтвердить большую строительную операцию"
    }

    "scoreboard board keeps a pinned quest first and emits only three distinct unfinished rows" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("QuestBoardTest")
            val player = paper.addPlayer("BoardTester")
            val root = Files.createTempDirectory("quest-board")
            ArcRanksSettings.loadFresh(root) { "unused-test-password" }
            val locale = RankLocale.fresh(root, { "ru" }, { false })
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val clock = TrackingTestClock()
            val first = DailyQuest.ALL[0].copy(id = "board-first")
            val second = DailyQuest.ALL[1].copy(id = "board-second")
            val pinned = DailyQuest.ALL[2].copy(id = "board-pinned")
            val board = DailyQuestBoard(DailyQuest.day(clock.instant()), listOf(
                DailyQuestProgress(first, first.target),
                DailyQuestProgress(second, 7),
                DailyQuestProgress(second, 8), // duplicate IDs must not duplicate rows.
                DailyQuestProgress(pinned, 9),
                DailyQuestProgress(first.copy(id = "board-fourth"), 10),
            ))
            val storage = MemoryTrackingRepository().also { it.mode = QuestDisplayMode.SCOREBOARD }
            val tracker = QuestTracker(plugin, tasks, { DailyQuestCatalog(mapOf("settler" to 1), listOf(first, second, pinned)) },
                { locale }, storage, { CompletableFuture.completedFuture(board) }, clock)
            try {
                tracker.install(); paper.performTicks(3)
                val pin = tracker.toggle(player, board, pinned.id); paper.performTicks(3); pin.join()
                tracker.placeholder(player.uniqueId, "quest_board_header")!!.contains("/quests") shouldBe true
                tracker.placeholder(player.uniqueId, "quest_board_1")!!.contains("9/2000") shouldBe true
                tracker.placeholder(player.uniqueId, "quest_board_2")!!.contains("7/100") shouldBe true
                tracker.placeholder(player.uniqueId, "quest_board_3")!!.contains("10/100") shouldBe true
                tracker.placeholder(player.uniqueId, "quest_board_4") shouldBe null

                // Deliberate unpin keeps the board visible and must not auto-select another quest.
                val unpin = tracker.toggle(player, board, pinned.id); paper.performTicks(3); unpin.join()
                storage.values[player.uniqueId] shouldBe null
                tracker.selected(player.uniqueId, board) shouldBe null
                tracker.placeholder(player.uniqueId, "quest_board_1")!!.contains("7/100") shouldBe true
                tracker.placeholder(player.uniqueId, "quest_board_1")!!.contains("9/2000") shouldBe false
                tracker.placeholder(player.uniqueId, "quest_line_1") shouldBe ""
                tracker.placeholder(player.uniqueId, "quest_compact") shouldBe ""
                tracker.placeholder(player.uniqueId, "quest_active") shouldBe "false"

                val actionbar = tracker.cycleDisplay(player); paper.performTicks(3); actionbar.join()
                val off = tracker.cycleDisplay(player); paper.performTicks(3); off.join()
                tracker.placeholder(player.uniqueId, "quest_active") shouldBe "false"
                tracker.placeholder(player.uniqueId, "quest_board_1") shouldBe ""
                val score = tracker.cycleDisplay(player); paper.performTicks(3); score.join()
                storage.values[player.uniqueId] shouldBe null
                tracker.selected(player.uniqueId, board) shouldBe null
                tracker.placeholder(player.uniqueId, "quest_board_1")!!.contains("7/100") shouldBe true
            } finally { tracker.close(); tasks.close() }
        }
    }

    "short HUD labels keep every bundled quest counter on one compact line in both locales" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("ShortHudTester")
            val root = Files.createTempDirectory("quest-short-hud")
            ArcRanksSettings.loadFresh(root) { "unused-test-password" }
            val ranks = RankCatalogLoader(Config(root, "ranks.yml")).load().ranks.map { it.id.value }.toSet()
            val catalog = DailyQuestCatalog.load(Config(root, "daily-quests.yml"), ranks)
            val day = java.time.LocalDate.of(2026, 9, 19)
            val plain = PlainTextComponentSerializer.plainText()
            val legacy = LegacyComponentSerializer.legacySection()
            for (language in listOf("ru", "en")) {
                val locale = RankLocale.fresh(root, { language }, { false })
                for (base in catalog.pool) {
                    val quest = catalog.scalingByRank.getValue("caesar").apply(base)
                    val progress = DailyQuestProgress(quest, quest.target - 1,
                        stepValues = quest.plan?.steps?.map { it.target - 1 }.orEmpty())
                    val view = QuestTrackingView.of(progress)
                    val row = QuestHudSnapshot.render(DailyQuestBoard(day, listOf(progress)), quest.id, player, locale)
                        .boardLines.single()
                    val visible = plain.serialize(legacy.deserialize(row))
                    (visible.length <= 27) shouldBe true
                    visible.endsWith("${view.value}/${view.target}") shouldBe true
                    visible.contains("daily.") shouldBe false
                    visible.contains("…") shouldBe false
                }
                if (language == "ru") {
                    val farm = catalog.pool.single { it.id == "farm_job" }
                    val row = QuestHudSnapshot.render(DailyQuestProgress(farm, 0), player, locale, day).boardLines.single()
                    plain.serialize(legacy.deserialize(row)) shouldBe "Работа на ферме 0/2"
                    plain.serialize(locale.render("daily.farm_job.name", player)) shouldBe "Завершите работу на ферме"
                }
            }
        }
    }

    "custom HUD names fall back to the full localized name and preserve large counters" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("CustomHudTester")
            val root = Files.createTempDirectory("quest-custom-hud")
            ArcRanksSettings.loadFresh(root) { "unused-test-password" }
            Config(root, "lang/ru.yml").apply {
                setString("daily.custom.name", "Очень длинная пользовательская цель")
                saveStrict()
            }
            val locale = RankLocale.fresh(root, { "ru" }, { false })
            val plain = PlainTextComponentSerializer.plainText()
            plain.serialize(locale.render("daily.custom.short-name", player)) shouldBe "Очень длинная пользовательская цель"
            val quest = DailyQuest.ALL.first().copy(id = "custom", textId = "custom", target = 1_000_000_000)
            val row = QuestHudSnapshot.render(DailyQuestProgress(quest, 999_999_999), player, locale,
                java.time.LocalDate.of(2026, 9, 19)).boardLines.single()
            val visible = plain.serialize(LegacyComponentSerializer.legacySection().deserialize(row))
            (visible.length <= 27) shouldBe true
            visible.endsWith("999999999/1000000000") shouldBe true
            visible.contains("…") shouldBe true
        }
    }

    "an all-completed board reloads after the UTC day rolls over" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("QuestRolloverTest")
            val player = paper.addPlayer("RolloverTester")
            val root = Files.createTempDirectory("quest-rollover")
            ArcRanksSettings.loadFresh(root) { "unused-test-password" }
            val locale = RankLocale.fresh(root, { "ru" }, { false })
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val clock = TrackingTestClock()
            val quest = DailyQuest.ALL.first()
            var board = DailyQuestBoard(DailyQuest.day(clock.instant()), listOf(DailyQuestProgress(quest, quest.target)))
            val storage = MemoryTrackingRepository().also { it.mode = QuestDisplayMode.SCOREBOARD }
            val tracker = QuestTracker(plugin, tasks, { DailyQuestCatalog(mapOf("settler" to 1), listOf(quest)) },
                { locale }, storage, { CompletableFuture.completedFuture(board) }, clock)
            try {
                tracker.install(); paper.performTicks(3)
                storage.values[player.uniqueId] shouldBe null
                tracker.placeholder(player.uniqueId, "quest_board_header") shouldBe ""
                tracker.placeholder(player.uniqueId, "quest_board_1") shouldBe ""

                board = DailyQuestBoard(DailyQuest.day(clock.time.plusSeconds(86400)), listOf(DailyQuestProgress(quest, 0)))
                clock.time = clock.time.plusSeconds(86400)
                tracker.refresh(player.uniqueId); paper.performTicks(3)
                storage.values[player.uniqueId] shouldBe TrackedQuest(board.day, quest.id)
                tracker.placeholder(player.uniqueId, "quest_board_1")!!.contains("0/100") shouldBe true
            } finally { tracker.close(); tasks.close() }
        }
    }

})

private class MemoryTrackingRepository : QuestTrackingRepository {
    val values = mutableMapOf<UUID, TrackedQuest>()
    var mode = QuestDisplayMode.ACTIONBAR
    override fun loadMode(playerId: UUID) = CompletableFuture.completedFuture(mode)
    override fun saveMode(playerId: UUID, mode: QuestDisplayMode): CompletableFuture<Unit> { this.mode = mode; return CompletableFuture.completedFuture(Unit) }
    override fun load(playerId: UUID) = CompletableFuture.completedFuture(values[playerId])
    override fun save(playerId: UUID, selection: TrackedQuest): CompletableFuture<Unit> {
        values[playerId] = selection
        return CompletableFuture.completedFuture(Unit)
    }
    override fun clear(playerId: UUID, expected: TrackedQuest): CompletableFuture<Unit> {
        values.remove(playerId, expected)
        return CompletableFuture.completedFuture(Unit)
    }
}
private class TrackingTestClock(var time: Instant = Instant.parse("2026-09-08T12:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = time
}
