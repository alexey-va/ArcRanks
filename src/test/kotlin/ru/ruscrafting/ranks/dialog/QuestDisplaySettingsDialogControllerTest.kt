package ru.ruscrafting.ranks.dialog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.papermc.paper.connection.PlayerGameConnection
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.quest.QuestDisplayMode
import ru.ruscrafting.ranks.text.RankLocale
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class QuestDisplaySettingsDialogControllerTest : FunSpec({
    test("three explicit choices mark the saved mode and wait for persistence in both locales") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("QuestDisplayChoices")
            val player = paper.addPlayer("DisplayChoices")
            listOf("ru", "en").forEach { language ->
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val fixture = DisplayFixture(plugin, player, tasks, language)
                try {
                    fixture.controller.beginFlowAndOpen(player)
                    val screen = fixture.screens.last()
                    screen.columns shouldBe 1
                    screen.buttons.map { it.id.value } shouldBe listOf("mode_scoreboard", "mode_actionbar", "mode_off")
                    fixture.plain(screen.buttons.first().label).startsWith("✔") shouldBe true
                    screen.buttons.drop(1).all { fixture.plain(it.label).startsWith("○") } shouldBe true
                    screen.buttons.any { it.id.value == "quests" } shouldBe false
                    exportDialogPreview(screen, "quest-display-$language")
                    fixture.click("mode_scoreboard")
                    fixture.saves shouldBe 0
                    fixture.pending = CompletableFuture()
                    fixture.click("mode_actionbar")
                    fixture.saves shouldBe 1
                    fixture.mode shouldBe QuestDisplayMode.SCOREBOARD
                    fixture.screens.last().buttons shouldBe emptyList()
                    fixture.text().contains(if (language == "ru") "Сохраняю" else "Saving") shouldBe true
                    fixture.pending.complete(Unit)
                    paper.performTicks(2)
                    fixture.mode shouldBe QuestDisplayMode.ACTIONBAR
                    fixture.plain(fixture.screens.last().buttons.single { it.id.value == "mode_actionbar" }.label)
                        .startsWith("✔") shouldBe true
                } finally { fixture.runtime.close(); tasks.close() }
            }
        }
    }

    test("unavailable and failed saves leave the setting truthful and retryable") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("QuestDisplayFailure")
            val player = paper.addPlayer("DisplayFailure")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val fixture = DisplayFixture(plugin, player, tasks)
            try {
                fixture.available = false
                fixture.controller.beginFlowAndOpen(player)
                fixture.screens.last().buttons.map { it.id.value } shouldBe listOf("retry")
                fixture.text().contains("недоступна") shouldBe true
                fixture.available = true
                fixture.click("retry")
                fixture.pending = CompletableFuture.failedFuture(IllegalStateException("storage unavailable"))
                fixture.click("mode_off")
                paper.performTicks(2)
                fixture.mode shouldBe QuestDisplayMode.SCOREBOARD
                fixture.text().contains("Не удалось сохранить") shouldBe true
                fixture.plain(fixture.screens.last().buttons.first().label).startsWith("✔") shouldBe true
                fixture.pending = CompletableFuture.completedFuture(Unit)
                fixture.click("mode_off")
                paper.performTicks(2)
                fixture.mode shouldBe QuestDisplayMode.OFF
                fixture.plain(fixture.screens.last().buttons.last().label).startsWith("✔") shouldBe true
            } finally { fixture.runtime.close(); tasks.close() }
        }
    }

    test("shared settings history survives command entry and late saves cannot reopen a departed visit") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("QuestDisplayHistory")
            val player = paper.addPlayer("DisplayHistory")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val fixture = DisplayFixture(plugin, player, tasks)
            try {
                fixture.runtime.beginFlow(player)
                fixture.runtime.open(player, PaperDialogScreen(
                    id = "settings.root", title = Component.text("Settings"),
                    buttons = listOf(PaperDialogButton(PaperDialogActionId.of("quest_display"), Component.text("Quest display"),
                        onClick = { fixture.controller.beginFlowAndOpen(it.player) })),
                ))
                fixture.click("quest_display")
                fixture.screens.last().id shouldBe "ranks.quest-display"
                fixture.pending = CompletableFuture()
                fixture.click("mode_actionbar")
                fixture.click(fixture.screens.last().exitButton!!.id.value)
                fixture.screens.last().id shouldBe "settings.root"
                val afterBack = fixture.screens.size
                fixture.pending.complete(Unit)
                paper.performTicks(2)
                fixture.screens.size shouldBe afterBack
                fixture.click("quest_display")
                fixture.plain(fixture.screens.last().buttons.single { it.id.value == "mode_actionbar" }.label)
                    .startsWith("✔") shouldBe true
                fixture.pending = CompletableFuture()
                fixture.click("mode_off")
                fixture.generation++
                val beforeStale = fixture.screens.size
                fixture.pending.complete(Unit)
                paper.performTicks(2)
                fixture.screens.size shouldBe beforeStale
                fixture.controller.beginFlowAndOpen(player)
                fixture.generation++
                val saves = fixture.saves
                fixture.click("mode_scoreboard")
                fixture.saves shouldBe saves
            } finally { fixture.runtime.close(); tasks.close() }
        }
    }
})

private class DisplayFixture(plugin: Plugin, private val player: Player, tasks: LifecycleTaskScope, language: String = "ru") {
    val screens = mutableListOf<PaperDialogScreen>()
    private lateinit var registration: Any
    private val presenter: (Player, PaperDialogScreen, Any) -> Unit = { _, screen, session ->
        screens += screen
        registration = session
    }
    val runtime = PaperDialogRuntime::class.java.declaredConstructors.first { it.parameterCount == 2 }
        .apply { isAccessible = true }.newInstance(plugin, presenter) as PaperDialogRuntime
    var mode = QuestDisplayMode.SCOREBOARD
    var available = true
    var generation = 1L
    var saves = 0
    var pending = CompletableFuture.completedFuture(Unit)
    private val rankLocale = RankLocale.fresh(Path.of("src/main/resources"), { language }, { false })
    val controller = QuestDisplaySettingsDialogController(
        runtime = runtime, mode = { mode }, available = { available },
        select = { _, choice -> saves++; pending.thenApply { mode = choice; Unit } },
        tasks = tasks, generation = { generation },
        locale = { rankLocale },
    )

    fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)
    fun text(): String = screens.last().body.joinToString("\n") { plain(it.text) }
    fun click(id: String) {
        val key = registration.javaClass.declaredMethods.first { it.name.startsWith("key") }
            .apply { isAccessible = true }.invoke(registration, id) as String
        val connection = mockk<PlayerGameConnection> { every { this@mockk.player } returns this@DisplayFixture.player }
        runtime.onCustomClick(mockk {
            every { commonConnection } returns connection
            every { identifier } returns Key.key(key)
            every { dialogResponseView } returns null
        })
    }
}
