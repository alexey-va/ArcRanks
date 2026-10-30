package ru.ruscrafting.ranks.dialog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.papermc.paper.connection.PlayerGameConnection
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.onboarding.api.StoryQuestService
import ru.ruscrafting.ranks.text.RankLocale
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

class QuestJournalDialogControllerTest : FunSpec({
    test("journal highlights the tracked story and opens owner details without accepting or tracking") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("QuestJournal")
            val player = paper.addPlayer("JournalReader")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            try {
                listOf("ru", "en").forEach { language ->
                    val fixture = JournalFixture(plugin, player, tasks, language)
                    fixture.controller.beginFlowAndOpen(player)
                    val screen = fixture.screens.last()
                    screen.id shouldBe "ranks.journal"
                    screen.columns shouldBe 2
                    fixture.plain(screen.buttons.first().label).contains("Первое приключение") shouldBe true
                    fixture.text().contains("Найдите капитана") shouldBe true
                    exportDialogPreview(screen, "quest-journal-$language")
                    fixture.click("story_0")
                    verify(exactly = 1) { fixture.provider.open(player.uniqueId, "adventure") }
                    verify(exactly = 0) { fixture.provider.track(any(), any()) }
                    fixture.controller.open(player)
                    fixture.click("daily")
                    fixture.dailyOpens shouldBe 1
                    fixture.ready = false
                    fixture.controller.open(player)
                    fixture.screens.last().buttons.map { it.id.value } shouldBe listOf("daily", "clan", "refresh")
                    fixture.text().contains(if (language == "ru") "загружаются" else "loading") shouldBe true
                    fixture.available = false
                    fixture.controller.open(player)
                    fixture.text().contains(if (language == "ru") "недоступны" else "unavailable") shouldBe true
                    fixture.click("clan")
                    fixture.clanOpens shouldBe 1
                    fixture.close()
                }
            } finally { tasks.close() }
        }
    }

    test("failed tracking keeps the previous selection and a dismissed save does not reopen the journal") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("QuestJournalSave")
            val player = paper.addPlayer("JournalSaver")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            try {
                val fixture = JournalFixture(plugin, player, tasks)
                fixture.pending = CompletableFuture()
                fixture.controller.open(player)
                fixture.click("stop_tracking")
                fixture.text().contains("Сохраняю") shouldBe true
                fixture.pending.completeExceptionally(IllegalStateException("fixture storage failure"))
                paper.performTicks(2)
                fixture.text().contains("Не удалось сохранить") shouldBe true
                fixture.screens.last().buttons.any { it.id.value == "stop_tracking" } shouldBe true
                fixture.refreshes shouldBe 0
                fixture.pending = CompletableFuture()
                fixture.click("stop_tracking")
                val afterPending = fixture.screens.size
                fixture.dismiss()
                fixture.pending.complete(null)
                paper.performTicks(2)
                fixture.screens.size shouldBe afterPending
                fixture.refreshes shouldBe 1
                fixture.close()
            } finally { tasks.close() }
        }
    }
})

private class JournalFixture(plugin: Plugin, private val player: Player, tasks: LifecycleTaskScope, language: String = "ru") : AutoCloseable {
    val screens = mutableListOf<PaperDialogScreen>()
    private lateinit var registration: Any
    var ready = true
    var available = true
    var dailyOpens = 0
    var clanOpens = 0
    var refreshes = 0
    var pending = CompletableFuture.completedFuture<Void?>(null)
    val provider = mockk<StoryQuestService> {
        every { ready(any()) } answers { this@JournalFixture.ready }
        every { quests(any()) } returns listOf(
            StoryQuestService.Quest("first_home", "Первый дом", "Постройте свой дом.", "Защитите участок", 2, 4, StoryQuestService.Status.ACTIVE, false),
            StoryQuestService.Quest("adventure", "Первое приключение", "Познакомьтесь с гильдией и островом Примис.", "Найдите капитана", 2, 4, StoryQuestService.Status.ACTIVE, true),
            StoryQuestService.Quest("casino", "Знакомство с казино", "Узнайте правила игр.", "", 0, 6, StoryQuestService.Status.AVAILABLE, false),
            StoryQuestService.Quest("farm", "Первая смена", "Работа на ферме завершена.", "", 2, 2, StoryQuestService.Status.COMPLETED, false),
        )
        every { open(any(), any()) } returns true
        every { track(any(), null) } answers { this@JournalFixture.pending }
    }
    private val presenter: (Player, PaperDialogScreen, Any) -> Unit = { _, screen, session ->
        screens += screen
        registration = session
    }
    private val runtime = PaperDialogRuntime::class.java.declaredConstructors.first { it.parameterCount == 2 }
        .apply { isAccessible = true }.newInstance(plugin, presenter) as PaperDialogRuntime
    private val text = RankLocale.fresh(Path.of("src/main/resources"), { language }, { false })
    val controller = QuestJournalDialogController(runtime, { provider.takeIf { available } },
        { dailyOpens++ }, { clanOpens++ }, { null }, { refreshes++ }, tasks, { text }, Logger.getLogger("QuestJournalTest"))

    fun click(id: String) {
        val key = registration.javaClass.declaredMethods.first { it.name.startsWith("key") }
            .apply { isAccessible = true }.invoke(registration, id) as String
        val connection = mockk<PlayerGameConnection> { every { this@mockk.player } returns this@JournalFixture.player }
        runtime.onCustomClick(mockk {
            every { commonConnection } returns connection
            every { identifier } returns Key.key(key)
            every { dialogResponseView } returns null
        })
    }
    fun dismiss() = click(screens.last().exitButton!!.id.value)
    override fun close() { runtime.close() }
    fun plain(value: Component): String = PlainTextComponentSerializer.plainText().serialize(value)
    fun text(): String = screens.last().body.joinToString("\n") { plain(it.text) }
}
