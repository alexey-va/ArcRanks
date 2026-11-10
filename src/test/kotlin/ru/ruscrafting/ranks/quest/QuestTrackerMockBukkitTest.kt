package ru.ruscrafting.ranks.quest

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.menu.DialogTextLayout
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

    "scoreboard and bossbar share progress while a tracked story temporarily takes priority" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("QuestStoryPriority")
            val player = paper.addPlayer("StoryPriority")
            player.teleport(paper.addSimpleWorld("survival").spawnLocation)
            val root = Files.createTempDirectory("quest-story-priority")
            ArcRanksSettings.loadFresh(root) { "unused-test-password" }
            val locale = RankLocale.fresh(root, { "ru" }, { false })
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val clock = TrackingTestClock()
            val quest = DailyQuest.ALL.first()
            val board = DailyQuestBoard(DailyQuest.day(clock.instant()), listOf(DailyQuestProgress(quest, 25)))
            val storage = MemoryTrackingRepository().also { it.mode = QuestDisplayMode.SCOREBOARD }
            var story = false
            val tracker = QuestTracker(plugin, tasks, { DailyQuestCatalog(mapOf("settler" to 1), listOf(quest)) },
                { locale }, storage, { CompletableFuture.completedFuture(board) }, clock, storyTracked = { story },
                serverId = "survival")
            try {
                tracker.install(); paper.performTicks(4)
                player.bossBars.single().progress() shouldBe 0.25f
                val title = PlainTextComponentSerializer.plainText().serialize(player.bossBars.single().name())
                title.startsWith("Квест: ") shouldBe true
                title.endsWith(" · 25/100 · +50💰") shouldBe true
                tracker.placeholder(player.uniqueId, "quest_active") shouldBe "true"
                val remembered = storage.values[player.uniqueId]
                story = true
                paper.performTicks(20)
                player.bossBars shouldBe emptySet()
                tracker.placeholder(player.uniqueId, "quest_active") shouldBe "false"
                tracker.selected(player.uniqueId, board) shouldBe null
                storage.values[player.uniqueId] shouldBe remembered
                story = false
                val resume = tracker.toggle(player, board, quest.id, forceSelect = true)
                paper.performTicks(4); resume.join()
                tracker.selected(player.uniqueId, board) shouldBe quest.id
                player.bossBars.single().progress() shouldBe 0.25f
                val off = tracker.selectDisplay(player, QuestDisplayMode.OFF)
                paper.performTicks(4); off.join()
                player.bossBars shouldBe emptySet()
                tracker.placeholder(player.uniqueId, "quest_active") shouldBe "false"
            } finally { tracker.close(); tasks.close() }
            player.bossBars shouldBe emptySet()
        }
    }

    "death keeps scoreboard quests stable across refreshes and respawn restores the bossbar" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("QuestDeathHud")
            val player = paper.addPlayer("DeathHudTester")
            player.teleport(paper.addSimpleWorld("survival").spawnLocation)
            val root = Files.createTempDirectory("quest-death-hud")
            ArcRanksSettings.loadFresh(root) { "unused-test-password" }
            val locale = RankLocale.fresh(root, { "ru" }, { false })
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val clock = TrackingTestClock()
            val quest = DailyQuest.ALL.first()
            val board = DailyQuestBoard(DailyQuest.day(clock.instant()), listOf(DailyQuestProgress(quest, 25)))
            val storage = MemoryTrackingRepository().also { it.mode = QuestDisplayMode.SCOREBOARD }
            var loading: CompletableFuture<DailyQuestBoard>? = null
            val tracker = QuestTracker(plugin, tasks, { DailyQuestCatalog(mapOf("settler" to 1), listOf(quest)) },
                { locale }, storage, { loading ?: CompletableFuture.completedFuture(board) }, clock, serverId = "survival")
            fun sidebar() = listOf("quest_active", "quest_board_header", "quest_board_1", "quest_reward_summary")
                .map { tracker.placeholder(player.uniqueId, it) }
            try {
                tracker.install(); paper.performTicks(4)
                val beforeDeath = sidebar()
                beforeDeath.all { !it.isNullOrEmpty() } shouldBe true
                val pin = storage.values[player.uniqueId]
                player.bossBars.size shouldBe 1
                player.health = 0.0
                player.isDead shouldBe true
                repeat(220) { tick ->
                    if (tick == 25) {
                        loading = CompletableFuture()
                        tracker.refresh(player.uniqueId)
                    }
                    if (tick == 45) loading!!.complete(board)
                    paper.performTicks(1)
                    sidebar() shouldBe beforeDeath
                    if (tick >= 20) player.bossBars shouldBe emptySet()
                }
                storage.values[player.uniqueId] shouldBe pin
                player.respawn()
                player.isDead shouldBe false
                tracker.refresh(player.uniqueId); paper.performTicks(3)
                sidebar() shouldBe beforeDeath
                player.bossBars.single().progress() shouldBe 0.25f
                storage.values[player.uniqueId] shouldBe pin
            } finally { tracker.close(); tasks.close() }
        }
    }

    "bossbar gates ordinary objectives on world changes while keeping the daily pin" {
        for (serverId in listOf("spawn", "survival")) {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("QuestWorldHud")
                val player = paper.addPlayer("WorldHudTester")
                val spawn = paper.addSimpleWorld("rc_origin_spawn")
                val survival = paper.addSimpleWorld("survival")
                val vanilla = paper.addSimpleWorld("vanilla")
                val dungeon = paper.addSimpleWorld("otd_dungeon")
                val mining = paper.addSimpleWorld("mining")
                player.teleport(spawn.spawnLocation) shouldBe true
                val root = Files.createTempDirectory("quest-world-hud")
                ArcRanksSettings.loadFresh(root) { "unused-test-password" }
                val locale = RankLocale.fresh(root, { "ru" }, { false })
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val clock = TrackingTestClock()
                val breed = DailyQuest.ALL.first().copy(id = "breed_cow", textId = "breed_cow", objective = "breed:cow", target = 2)
                val expedition = DailyQuest.ALL[2].copy(id = "dungeon", textId = "dungeon", objective = "dungeon.complete", target = 1)
                val board = DailyQuestBoard(DailyQuest.day(clock.instant()),
                    listOf(DailyQuestProgress(breed, 1), DailyQuestProgress(expedition, 0)))
                val pin = TrackedQuest(board.day, breed.id)
                val storage = MemoryTrackingRepository().also {
                    it.mode = QuestDisplayMode.SCOREBOARD
                    it.values[player.uniqueId] = pin
                }
                val tracker = QuestTracker(plugin, tasks, { DailyQuestCatalog(mapOf("settler" to 2), listOf(breed, expedition)) },
                    { locale }, storage, { CompletableFuture.completedFuture(board) }, clock, serverId = serverId)
                try {
                    tracker.install(); paper.performTicks(4)
                    player.bossBars shouldBe emptySet()
                    for (overworld in listOf(survival, vanilla)) {
                        player.teleport(overworld.spawnLocation) shouldBe true
                        player.bossBars.singleOrNull()?.progress() shouldBe if (serverId == "survival") 0.5f else null
                        player.teleport(dungeon.spawnLocation) shouldBe true
                        player.bossBars shouldBe emptySet()
                    }
                    player.teleport(mining.spawnLocation) shouldBe true
                    tracker.refresh(player.uniqueId); paper.performTicks(3)
                    player.bossBars shouldBe emptySet()
                    storage.values[player.uniqueId] shouldBe pin
                    tracker.selected(player.uniqueId, board) shouldBe breed.id
                    tracker.placeholder(player.uniqueId, "quest_active") shouldBe "true"

                    val pinExpedition = tracker.toggle(player, board, expedition.id)
                    paper.performTicks(4); pinExpedition.join()
                    player.bossBars.single().progress() shouldBe 0.0f
                    player.teleport(spawn.spawnLocation) shouldBe true
                    player.bossBars.single().progress() shouldBe 0.0f
                    player.teleport(dungeon.spawnLocation) shouldBe true
                    player.bossBars.single().progress() shouldBe 0.0f
                } finally { tracker.close(); tasks.close() }
            }
        }
    }

    "bossbar preserves frozen rewards and counters with compact names and older locale files" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("CompactBossbarTester")
            val root = Files.createTempDirectory("quest-bossbar")
            Files.createDirectories(root.resolve("lang"))
            val plain = PlainTextComponentSerializer.plainText()
            val legacy = LegacyComponentSerializer.legacySection()
            for (language in listOf("ru", "en")) {
                Files.writeString(root.resolve("lang/$language.yml"), """
                    daily:
                      tracking:
                        bossbar: 'old <goal> <value>/<target>'
                      custom:
                        name: 'Очень длинная пользовательская цель с дополнительным описанием'
                """.trimIndent())
                Config(root, "lang/$language.yml").mergeMissingFromBundled("lang/$language.yml")
                val locale = RankLocale.fresh(root, { language }, { false })
                plain.serialize(locale.render("daily.tracking.bossbar")).startsWith("old ") shouldBe true
                val quest = DailyQuest.ALL.first().copy(id = "breed", textId = "breed", target = 1, money = 175)
                fun title(candidate: DailyQuest, value: Long = 0, challenge: Long = 0): net.kyori.adventure.text.Component {
                    val state = DailyQuestProgress(candidate, value, challengeValue = challenge)
                    return questBossBarTitle(state, QuestTrackingView.of(state), player, locale)
                }
                val label = if (language == "ru") "Квест: Развести животных" else "Quest: Breed animals"
                plain.serialize(title(quest)) shouldBe "$label · 0/1 · +175💰"
                val rare = title(quest.copy(money = 525, tokens = 3))
                plain.serialize(rare) shouldBe "$label · 0/1 · +525💰 +3"
                legacy.serialize(rare).contains("§f💰") shouldBe true
                legacy.serialize(rare).contains("§f") shouldBe true
                requireNotNull(DialogTextLayout.naturalWidth(rare)).let { it <= 280 } shouldBe true
                plain.serialize(title(quest.copy(money = 0, tokens = 3))) shouldBe "$label · 0/1 · +3"
                plain.serialize(title(quest.copy(money = 0, itemPreset = "enchant_supply", itemAmount = 2))) shouldBe
                    "$label · 0/1 · 2× ${if (language == "ru") "предм." else "item"}"
                plain.serialize(title(quest.copy(money = 0, crateKey = "case_weekly", crateKeyAmount = 1))) shouldBe
                    "$label · 0/1 · 1× ${if (language == "ru") "ключ" else "key"}"
                plain.serialize(title(quest.copy(money = 0))) shouldBe "$label · 0/1"
                val challenge = quest.copy(money = 50, challengeSuffix = "bonus", challengePercent = 50)
                plain.serialize(title(challenge, challenge = 1)) shouldBe "$label · 0/1 · +75💰"
                val crowded = title(quest.copy(id = "custom", textId = "custom", target = 1000, money = 525,
                    tokens = 3, itemPreset = "enchant_supply", itemAmount = 1, crateKey = "case_weekly", crateKeyAmount = 1), 999)
                val suffix = if (language == "ru") "1× предм. 1× ключ" else "1× item 1× key"
                plain.serialize(crowded).endsWith(" · 999/1000 · +525💰 +3 $suffix") shouldBe true
                plain.serialize(crowded).contains("…") shouldBe true
                requireNotNull(DialogTextLayout.naturalWidth(crowded)).let { it <= 280 } shouldBe true
            }
        }
    }

    "explicit settings selection is published only after durable save and preserves the pin" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("QuestDisplaySettingsTest")
            val player = paper.addPlayer("DisplaySettingsTester")
            val root = Files.createTempDirectory("quest-display-settings")
            ArcRanksSettings.loadFresh(root) { "unused-test-password" }
            val locale = RankLocale.fresh(root, { "ru" }, { false })
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val clock = TrackingTestClock()
            val quest = DailyQuest.ALL.first()
            val board = DailyQuestBoard(DailyQuest.day(clock.instant()), listOf(DailyQuestProgress(quest, 0)))
            val backing = MemoryTrackingRepository().also { it.mode = QuestDisplayMode.SCOREBOARD }
            val save = CompletableFuture<Unit>()
            val storage = object : QuestTrackingRepository by backing {
                override fun saveMode(playerId: UUID, mode: QuestDisplayMode): CompletableFuture<Unit> =
                    save.thenApply { backing.mode = mode }
            }
            val tracker = QuestTracker(plugin, tasks, { DailyQuestCatalog(mapOf("settler" to 1), listOf(quest)) },
                { locale }, storage, { CompletableFuture.completedFuture(board) }, clock, serverId = "survival")
            try {
                tracker.install(); paper.performTicks(3)
                val pin = backing.values[player.uniqueId]
                tracker.displaySettingsAvailable(player.uniqueId) shouldBe true
                val pending = tracker.selectDisplay(player, QuestDisplayMode.OFF)
                tracker.displaySettingsAvailable(player.uniqueId) shouldBe false
                tracker.displayMode(player.uniqueId) shouldBe QuestDisplayMode.SCOREBOARD
                pending.isDone shouldBe false
                save.complete(Unit); paper.performTicks(3); pending.join()
                tracker.displayMode(player.uniqueId) shouldBe QuestDisplayMode.OFF
                backing.mode shouldBe QuestDisplayMode.OFF
                backing.values[player.uniqueId] shouldBe pin
                tracker.placeholder(player.uniqueId, "quest_board_1") shouldBe ""
            } finally { tracker.close(); tasks.close() }
        }
    }

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
                { locale }, storage, { CompletableFuture.completedFuture(board) }, clock, serverId = "survival")
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
                { locale }, storage, { CompletableFuture.completedFuture(board) }, clock, serverId = "survival")
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
                { locale }, storage, { CompletableFuture.completedFuture(board) }, clock, serverId = "survival")
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
                { locale }, storage, { CompletableFuture.completedFuture(board) }, clock, serverId = "survival")
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

    "explicitly pinned quest leads token quests and reward columns keep the same order" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("RewardHudTester")
            val root = Files.createTempDirectory("quest-reward-hud")
            ArcRanksSettings.loadFresh(root) { "unused-test-password" }
            val day = java.time.LocalDate.of(2026, 9, 27)
            val normal = DailyQuest.ALL[0].copy(id = "normal", textId = "harvest", money = 50)
            val rare = DailyQuest.ALL[1].copy(id = "rare", textId = "craft", money = 150, tokens = 1)
            val secondRare = DailyQuest.ALL[2].copy(id = "rare-two", textId = "travel", money = 300, tokens = 2)
            val board = DailyQuestBoard(day, listOf(
                DailyQuestProgress(normal, 1), DailyQuestProgress(rare, 2),
                DailyQuestProgress(secondRare, 3), DailyQuestProgress(rare.copy(id = "done"), rare.target),
            ))
            fun plain(value: String) = PlainTextComponentSerializer.plainText()
                .serialize(LegacyComponentSerializer.legacySection().deserialize(value))
            for (language in listOf("ru", "en")) {
                val locale = RankLocale.fresh(root, { language }, { false })
                val hud = QuestHudSnapshot.render(board, normal.id, player, locale)
                hud.boardLines.map(::plain).map { it.startsWith("★ ") } shouldBe listOf(false, true, true)
                hud.boardLines.map(::plain).map { it.substringAfterLast(' ') } shouldBe listOf("1/100", "2/100", "3/2000")
                hud.boardRewards.map(::plain) shouldBe listOf(
                    "50💰",
                    "150💰 +1 " + if (language == "ru") "жет." else "tokens",
                    "300💰 +2 " + if (language == "ru") "жет." else "tokens",
                )
                hud.placeholder("quest_board_reward_2") shouldBe hud.boardRewards[1]
                hud.placeholder("quest_reward") shouldBe hud.boardRewards[0]
                plain(hud.compact).contains("1/100") shouldBe true
                val completed = QuestHudSnapshot.render(board.copy(quests = board.quests.map { it.copy(value = it.quest.target) }), normal.id, player, locale)
                completed.boardRewards shouldBe emptyList()
                completed.reward shouldBe ""
            }
            QuestHudSnapshot.emptyPlaceholder("quest_board_reward_1") shouldBe ""
            QuestHudSnapshot.emptyPlaceholder("quest_reward") shouldBe ""
        }
    }

    "board reward summary shows only currency totals for every current quest" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("RewardSummaryHudTester")
            val root = Files.createTempDirectory("quest-reward-summary-hud")
            ArcRanksSettings.loadFresh(root) { "unused-test-password" }
            val day = java.time.LocalDate.of(2026, 9, 27)
            val grantedChallenge = DailyQuest.ALL[0].copy(
                id = "granted-challenge", target = 100, money = 150, tokens = 1,
                challengeSuffix = "flawless", challengePercent = 25,
            )
            val pending = DailyQuest.ALL[1].copy(id = "pending", target = 100, money = 50, tokens = 2)
            val challengeReady = DailyQuest.ALL[2].copy(
                id = "challenge-ready", target = 100, money = 100,
                challengeSuffix = "flawless", challengePercent = 25,
            )
            val challengeNotReady = DailyQuest.ALL[2].copy(
                id = "challenge-not-ready", target = 100, money = 50,
                challengeSuffix = "flawless", challengePercent = 25,
            )
            val active = DailyQuest.ALL[0].copy(id = "active", target = 100, money = 300, tokens = 4)
            val board = DailyQuestBoard(day, listOf(
                DailyQuestProgress(grantedChallenge, 100, DailyRewardState.GRANTED, challengeValue = 100),
                DailyQuestProgress(pending, 100, DailyRewardState.PENDING),
                DailyQuestProgress(challengeReady, 0, challengeValue = 100),
                DailyQuestProgress(challengeNotReady, 0, challengeValue = 99),
                DailyQuestProgress(active, 0),
            ))
            fun plain(value: String) = PlainTextComponentSerializer.plainText()
                .serialize(LegacyComponentSerializer.legacySection().deserialize(value))

            for (language in listOf("ru", "en")) {
                val locale = RankLocale.fresh(root, { language }, { false })
                val hud = QuestHudSnapshot.render(board, null, player, locale)
                plain(hud.placeholder("quest_reward_summary")!!) shouldBe
                    "713💰 · 7"

                val allGranted = QuestHudSnapshot.render(board.copy(quests = board.quests.map {
                    it.copy(value = it.quest.target, rewardState = DailyRewardState.GRANTED)
                }), null, player, locale)
                allGranted.boardHeader.isNotEmpty() shouldBe true
                allGranted.boardLines shouldBe emptyList()
                plain(allGranted.rewardSummary) shouldBe
                    "713💰 · 7"

                fun singleReward(money: Long, tokens: Long): String = plain(QuestHudSnapshot.render(
                    DailyQuestBoard(day, listOf(DailyQuestProgress(
                        active.copy(money = money, tokens = tokens), 0,
                    ))), null, player, locale,
                ).rewardSummary)
                singleReward(50, 0) shouldBe "50💰"
                singleReward(0, 2) shouldBe "2"
                singleReward(0, 0) shouldBe ""
                val itemReward = QuestHudSnapshot.render(DailyQuestBoard(day, listOf(DailyQuestProgress(
                    active.copy(itemPreset = "enchant_supply", itemAmount = 2), 0,
                ))), null, player, locale).reward
                plain(itemReward).contains(if (language == "ru") "2 × Припасы чародея" else "2 × Enchanter supplies") shouldBe true

                val keyQuest = active.copy(money = 0, tokens = 0, period = QuestPeriod.WEEKLY,
                    crateKey = "case_weekly", crateKeyAmount = 1)
                val keyHud = QuestHudSnapshot.render(DailyQuestBoard(day, listOf(DailyQuestProgress(keyQuest, 0))),
                    keyQuest.id, player, locale)
                plain(keyHud.reward) shouldBe if (language == "ru") "1 × ключ «Недельная реликвия»" else "1 × Weekly Relic key"
                keyHud.rewardSummary shouldBe ""

                val empty = QuestHudSnapshot.render(DailyQuestBoard(day, emptyList()), null, player, locale)
                empty.boardHeader shouldBe ""
                empty.placeholder("quest_reward_summary") shouldBe ""
            }
        }
    }

    "a forced refresh during an in-flight board load retries the final granted reward summary" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("QuestRewardSummaryRefreshTest")
            val player = paper.addPlayer("RewardSummaryRefreshTester")
            val root = Files.createTempDirectory("quest-reward-summary-refresh")
            ArcRanksSettings.loadFresh(root) { "unused-test-password" }
            Config(root, "lang/ru.yml").apply {
                setString("daily.hud.reward-summary-money", "<earned-money>/<total-money>")
                saveStrict()
            }
            val locale = RankLocale.fresh(root, { "ru" }, { false })
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val quest = DailyQuest.ALL.first().copy(id = "last-quest", target = 100, money = 50)
            val day = java.time.LocalDate.of(2026, 9, 27)
            val pendingBoard = DailyQuestBoard(day, listOf(DailyQuestProgress(quest, quest.target, DailyRewardState.PENDING)))
            val grantedBoard = pendingBoard.copy(quests = listOf(pendingBoard.quests.single().copy(rewardState = DailyRewardState.GRANTED)))
            val firstLoad = CompletableFuture<DailyQuestBoard>()
            var loadCount = 0
            val tracker = QuestTracker(plugin, tasks, { DailyQuestCatalog(mapOf("settler" to 1), listOf(quest)) },
                { locale }, MemoryTrackingRepository().also { it.mode = QuestDisplayMode.SCOREBOARD }, {
                    loadCount++
                    if (loadCount == 1) firstLoad else CompletableFuture.completedFuture(grantedBoard)
                }, TrackingTestClock(Instant.parse("2026-09-27T12:00:00Z")), serverId = "survival")
            fun summary() = PlainTextComponentSerializer.plainText().serialize(
                LegacyComponentSerializer.legacySection().deserialize(tracker.placeholder(player.uniqueId, "quest_reward_summary")!!),
            )
            try {
                tracker.install(); paper.performTicks(3)
                loadCount shouldBe 1

                // Reward grant notification races the initial board read. The forced reload must survive it.
                tracker.refresh(player.uniqueId)
                firstLoad.complete(pendingBoard)
                paper.performTicks(3)
                summary() shouldBe "0/50"

                paper.performTicks(100)
                paper.performTicks(3)
                loadCount shouldBe 2
                summary() shouldBe "50/50"
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
                    plain.serialize(legacy.deserialize(row)) shouldBe "Работать на ферме 0/1"
                    plain.serialize(locale.render("daily.farm_job.name", player)) shouldBe "Завершить работу на ферме"
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
                { locale }, storage, { CompletableFuture.completedFuture(board) }, clock, serverId = "survival")
            try {
                tracker.install(); paper.performTicks(3)
                storage.values[player.uniqueId] shouldBe null
                tracker.placeholder(player.uniqueId, "quest_board_header")!!.isNotEmpty() shouldBe true
                tracker.placeholder(player.uniqueId, "quest_board_1") shouldBe ""
                PlainTextComponentSerializer.plainText().serialize(
                    LegacyComponentSerializer.legacySection().deserialize(tracker.placeholder(player.uniqueId, "quest_reward_summary")!!),
                ) shouldBe "50💰"

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
