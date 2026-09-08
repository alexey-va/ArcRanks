package ru.ruscrafting.ranks.dialog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.key.Key
import io.papermc.paper.connection.PlayerGameConnection
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.command.CommandSender
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.quest.DailyQuest
import ru.ruscrafting.ranks.quest.DailyQuestBoard
import ru.ruscrafting.ranks.quest.DailyQuestProgress
import ru.ruscrafting.ranks.quest.QuestDisplayMode
import ru.ruscrafting.ranks.quest.QuestReplaceResult
import ru.ruscrafting.ranks.text.RankLocale
import java.time.LocalDate
import java.util.concurrent.CompletableFuture

class DailyQuestDialogControllerTest : FunSpec({
    test("catalog is paginated into six goal cards and keeps the utility row") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("DailyQuestDialogCatalogTest")
            val player = paper.addPlayer("DailyDialogCatalog")
            val board = board(13)
            val pending = CompletableFuture<DailyQuestBoard>()
            val capture = PresenterCapture()
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            try {
                val controller = controller(plugin, pending, capture, tasks)
                controller.beginFlowAndOpen(player)
                capture.screens.last().id shouldBe "ranks.daily"
                pending.complete(board)
                paper.performTicks(2)
                val catalog = capture.screens.last()
                catalog.id shouldBe "ranks.daily"
                catalog.columns shouldBe 2
                catalog.buttons.count { it.id.value.startsWith("goal_") } shouldBe 6
                catalog.buttons.any { it.id.value == "next" } shouldBe true
            } finally {
                tasks.close()
            }
        }
    }

    test("detail actions preserve the page and stale config buttons cannot mutate") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("DailyDialogActions")
            val player = paper.addPlayer("DailyActions")
            val capture = PresenterCapture()
            val state = ActionState()
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            try {
                val controller = controller(plugin, CompletableFuture.completedFuture(board(13)), capture, tasks, state)
                controller.beginFlowAndOpen(player)
                paper.performTicks(2)
                click(capture, player, "next")
                paper.performTicks(2)
                click(capture, player, "goal_quest_6")
                capture.screens.last().id shouldBe "ranks.daily.detail"
                click(capture, player, "track")
                paper.performTicks(2)
                state.tracks shouldBe 1
                state.selected shouldBe "quest_6"
                click(capture, player, "replace")
                paper.performTicks(2)
                state.replacements shouldBe 1
                click(capture, player, "daily_footer")
                paper.performTicks(2)
                capture.screens.last().buttons.first().id.value shouldBe "goal_quest_6"
                click(capture, player, "goal_quest_6")
                state.generation++
                click(capture, player, "replace")
                state.replacements shouldBe 1
            } finally {
                tasks.close()
            }
        }
    }

    test("a dismissed loading visit cannot reopen from a late board result") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("DailyQuestDialogDismissTest")
            val player = paper.addPlayer("DailyDialogDismiss")
            val pending = CompletableFuture<DailyQuestBoard>()
            val capture = PresenterCapture()
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            try {
                val controller = controller(plugin, pending, capture, tasks)
                controller.beginFlowAndOpen(player)
                val beforeClose = capture.screens.size
                capture.runtime.close(player)
                pending.complete(board(1))
                paper.performTicks(2)
                capture.screens.size shouldBe beforeClose
            } finally {
                tasks.close()
            }
        }
    }
})

private class PresenterCapture {
    val screens = mutableListOf<PaperDialogScreen>()
    lateinit var runtime: PaperDialogRuntime
    lateinit var registration: Any
    fun present(screen: PaperDialogScreen, registration: Any) {
        exportDialogPreview(screen); screens += screen
        this.registration = registration
    }
}

private fun controller(
    plugin: org.bukkit.plugin.Plugin,
    board: CompletableFuture<DailyQuestBoard>,
    capture: PresenterCapture,
    tasks: LifecycleTaskScope,
    state: ActionState = ActionState(),
): DailyQuestDialogController {
    val runtimeCtor = PaperDialogRuntime::class.java.declaredConstructors.first { it.parameterCount == 2 }.apply { isAccessible = true }
    val presenter: (Player, PaperDialogScreen, Any) -> Unit = { _, screen, registration -> capture.present(screen, registration) }
    capture.runtime = runtimeCtor.newInstance(plugin, presenter) as PaperDialogRuntime
    val locale = mockk<RankLocale>()
    every { locale.render(any<String>(), any<CommandSender>(), any<Map<String, Component>>()) } answers { Component.text(firstArg<String>()) }
    every { locale.renderLines(any(), any(), any()) } returns emptyList()
    every { locale.text(any()) } answers { Component.text(firstArg<Any>().toString()) }
    return DailyQuestDialogController(
        runtime = capture.runtime,
        load = { board },
        selected = { _, _ -> state.selected },
        track = { _, _, id -> state.tracks++; state.selected = id; CompletableFuture.completedFuture(Unit) },
        displayMode = { QuestDisplayMode.SCOREBOARD },
        cycleDisplay = { CompletableFuture.completedFuture(Unit) },
        diagnose = { _, _ -> null },
        trackingEnabled = { true },
        replace = { _, _, _ -> state.replacements++; CompletableFuture.completedFuture(QuestReplaceResult.UNAVAILABLE) },
        generation = { state.generation },
        tasks = tasks,
        locale = { previewLocale() ?: locale },
    )
}

private fun board(count: Int): DailyQuestBoard = DailyQuestBoard(
    LocalDate.parse("2026-09-08"),
    (0 until count).map { index ->
        (previewQuests() ?: DailyQuest.ALL).let { pool ->
            val quest = pool[index % pool.size].copy(id = "quest_$index")
            DailyQuestProgress(quest, minOf(index.toLong(), quest.target - 1))
        }
    },
    replacementsLeft = 2,
)

private class ActionState {
    var generation = 1L
    var selected: String? = null
    var tracks = 0
    var replacements = 0
}

private fun click(capture: PresenterCapture, player: Player, id: String) {
    val keyMethod = capture.registration.javaClass.declaredMethods.first { it.name.startsWith("key") }.apply { isAccessible = true }
    val key = keyMethod.invoke(capture.registration, id) as String
    val connection = mockk<PlayerGameConnection> { every { this@mockk.player } returns player }
    capture.runtime.onCustomClick(mockk {
        every { commonConnection } returns connection
        every { identifier } returns Key.key(key)
        every { dialogResponseView } returns null
    })
}
