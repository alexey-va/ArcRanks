package ru.ruscrafting.ranks.quest

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.config.ArcRanksSettings
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
})

private class MemoryTrackingRepository : QuestTrackingRepository {
    val values = mutableMapOf<UUID, TrackedQuest>()
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
