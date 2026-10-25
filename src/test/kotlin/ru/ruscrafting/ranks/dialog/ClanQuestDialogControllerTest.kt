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
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.clan.ClanQuestBoard
import ru.ruscrafting.ranks.clan.ClanQuestDefinition
import ru.ruscrafting.ranks.clan.ClanQuestProgress
import ru.ruscrafting.ranks.quest.QuestMode
import ru.ruscrafting.ranks.quest.QuestPlan
import ru.ruscrafting.ranks.quest.QuestStep
import ru.ruscrafting.ranks.text.RankLocale
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.CompletableFuture

class ClanQuestDialogControllerTest : FunSpec({
    test("shared board shows clan identity, actual run progress and participant credit in both languages") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ClanQuestPresentation")
            val player = paper.addPlayer("ClanViewer")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            try {
                listOf("ru", "en").forEach { language ->
                    val capture = ClanCapture(plugin)
                    val board = clanBoard()
                    val controller = clanController(capture, tasks, language) { CompletableFuture.completedFuture(board) }
                    controller.beginFlowAndOpen(player)
                    paper.performTicks(2)
                    val catalog = capture.last()
                    catalog.id shouldBe "ranks.clan"
                    catalog.columns shouldBe 1
                    plain(catalog.exitButton!!.label) shouldBe "Close"
                    capture.text().contains(board.clanName) shouldBe true
                    capture.text().contains("02.11.2026") shouldBe true
                    capture.text().contains("00:00 UTC") shouldBe true
                    plain(catalog.buttons.first().label).contains("1/2") shouldBe true
                    catalog.buttons.none { it.id.value.contains("claim") } shouldBe true
                    exportDialogPreview(catalog, "clan-board-$language")
                    capture.click(player, "clan_goal_dungeon_run")
                    paper.performTicks(2)
                    capture.last().id shouldBe "ranks.clan.detail"
                    capture.text().contains("1/2") shouldBe true
                    capture.text().contains("2/2") shouldBe true
                    capture.text().contains(if (language == "ru") "ваши участия" else "your participations") shouldBe true
                    capture.text().contains("+1") shouldBe true
                    capture.text().contains("60%") shouldBe false
                    capture.text().contains("💰") shouldBe false
                    exportDialogPreview(capture.last(), "clan-detail-$language")
                }
            } finally { tasks.close() }
        }
    }

    test("no clan and failed membership lookup remain different visible states") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ClanQuestUnavailable")
            val player = paper.addPlayer("ClanUnavailable")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            try {
                val capture = ClanCapture(plugin)
                clanController(capture, tasks, "ru") { CompletableFuture.completedFuture(null) }.beginFlowAndOpen(player)
                paper.performTicks(2)
                capture.text().contains("Вступите в клан") shouldBe true
                capture.last().buttons.single().id.value shouldBe "clan_browse"
                clanController(capture, tasks, "ru") { CompletableFuture.failedFuture(IllegalStateException("offline")) }.beginFlowAndOpen(player)
                paper.performTicks(2)
                capture.text().contains("Не удалось получить") shouldBe true
                capture.text().contains("Вступите в клан") shouldBe false
                capture.last().buttons.single().id.value shouldBe "clan_retry"
            } finally { tasks.close() }
        }
    }

    test("weekly clan binding is visible and a completed project has a receipt without claim") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ClanQuestBinding")
            val player = paper.addPlayer("ClanBinding")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            try {
                val capture = ClanCapture(plugin)
                val board = clanBoard().copy(boundClanId = "team:99")
                clanController(capture, tasks, "ru") { CompletableFuture.completedFuture(board) }.beginFlowAndOpen(player)
                paper.performTicks(2)
                capture.text().contains("уже помогали другому клану") shouldBe true
                exportDialogPreview(capture.last(), "clan-bound-ru")
                val completed = board.copy(boundClanId = null, quests = board.quests.map { state ->
                    state.copy(values = state.quest.plan.steps.map { it.target }, myContributions = state.quest.plan.steps.map { it.target }, completed = true)
                })
                clanController(capture, tasks, "ru") { CompletableFuture.completedFuture(completed) }.beginFlowAndOpen(player)
                paper.performTicks(2)
                capture.click(player, "clan_goal_dungeon_run")
                paper.performTicks(2)
                capture.text().contains("Проект завершён") shouldBe true
                capture.last().buttons.map { it.id.value } shouldBe listOf("clan_refresh")
                exportDialogPreview(capture.last(), "clan-complete-ru")
            } finally { tasks.close() }
        }
    }

    test("combined battle project presents separate arena and dungeon goals with useful destinations") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ClanBattleDetail")
            paper.createSimplePlugin("ArcDuels")
            val player = paper.addPlayer("ClanFighter")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            try {
                val capture = ClanCapture(plugin)
                clanController(capture, tasks, "ru") { CompletableFuture.completedFuture(clanBoard()) }.beginFlowAndOpen(player)
                paper.performTicks(2)
                capture.click(player, "clan_goal_battle_campaign")
                paper.performTicks(2)
                capture.text().contains("Бои на арене") shouldBe true
                capture.text().contains("Совместные походы") shouldBe true
                capture.last().buttons.map { it.id.value } shouldBe listOf("clan_dungeon", "clan_arena", "clan_refresh")
                capture.last().buttons.take(2).all { it.closeDialogBeforeAction } shouldBe true
                exportDialogPreview(capture.last(), "clan-battle-ru")
            } finally { tasks.close() }
        }
    }

    test("detail and board return through actual history, while dismissed loading cannot reopen") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ClanQuestHistory")
            val player = paper.addPlayer("ClanHistory")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            try {
                val capture = ClanCapture(plugin)
                val controller = clanController(capture, tasks, "ru") { CompletableFuture.completedFuture(clanBoard()) }
                val parent = PaperDialogScreen(id = "fixture.parent", title = Component.text("Personal quests"), buttons = listOf(
                    PaperDialogButton(PaperDialogActionId.of("open_clan"), Component.text("Clan quests"), onClick = { controller.open(it.player) }),
                ))
                capture.runtime.beginFlow(player)
                capture.runtime.open(player, parent)
                capture.click(player, "open_clan")
                paper.performTicks(2)
                capture.click(player, "clan_goal_dungeon_run")
                paper.performTicks(2)
                capture.click(player, "clan_footer")
                paper.performTicks(2)
                capture.last().id shouldBe "ranks.clan"
                capture.click(player, capture.last().exitButton!!.id.value)
                capture.last().id shouldBe "fixture.parent"

                val pending = CompletableFuture<ClanQuestBoard?>()
                clanController(capture, tasks, "ru") { pending }.beginFlowAndOpen(player)
                capture.runtime.close(player)
                val count = capture.screens.size
                pending.complete(clanBoard())
                paper.performTicks(2)
                capture.screens.size shouldBe count
            } finally { tasks.close() }
        }
    }
})

private fun clanBoard(): ClanQuestBoard {
    val quest = ClanQuestDefinition("dungeon_run", "dungeon_run", QuestPlan(QuestMode.ALL,
        listOf(QuestStep("clan.dungeon_run", 2, "clan_dungeon_run"))), "IRON_SWORD")
    val boss = ClanQuestDefinition("boss_hunt", "boss_hunt", QuestPlan(QuestMode.ALL,
        listOf(QuestStep("clan.boss_kill", 1, "clan_boss_kill"))), "WITHER_SKELETON_SKULL")
    val campaign = ClanQuestDefinition("battle_campaign", "battle_campaign", QuestPlan(QuestMode.ALL,
        listOf(QuestStep("clan.arena_match", 2, "clan_arena_match"), QuestStep("clan.dungeon_run", 1, "clan_dungeon_run"))), "NETHERITE_SWORD")
    return ClanQuestBoard("team:7", "Северные рыцари", LocalDate.parse("2026-10-26"),
        listOf(ClanQuestProgress(quest, listOf(1), listOf(1), 2, false),
            ClanQuestProgress(boss, listOf(0), listOf(0), 0, false),
            ClanQuestProgress(campaign, listOf(1, 0), listOf(1, 0), 2, false)), 4, null)
}

private fun clanController(
    capture: ClanCapture, tasks: LifecycleTaskScope, language: String,
    load: (java.util.UUID) -> CompletableFuture<ClanQuestBoard?>,
): ClanQuestDialogController {
    val locale = RankLocale.fresh(Path.of("src/main/resources"), { language }, { false })
    return ClanQuestDialogController(capture.runtime, load, tasks, { locale }, openDungeon = {})
}

private fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)

private class ClanCapture(plugin: Plugin) {
    val screens = mutableListOf<PaperDialogScreen>()
    private lateinit var registration: Any
    val runtime: PaperDialogRuntime
    init {
        val constructor = PaperDialogRuntime::class.java.declaredConstructors.first { it.parameterCount == 2 }.apply { isAccessible = true }
        val presenter: (Player, PaperDialogScreen, Any) -> Unit = { _, screen, actions -> screens += screen; registration = actions }
        runtime = constructor.newInstance(plugin, presenter) as PaperDialogRuntime
    }
    fun last() = screens.last()
    fun text() = last().body.joinToString("\n") { plain(it.text) }.replace(Regex("\\p{Co}"), "").replace(Regex("[ \\t]+"), " ")
    fun click(player: Player, id: String) {
        val keyMethod = registration.javaClass.declaredMethods.first { it.name.startsWith("key") }.apply { isAccessible = true }
        val key = keyMethod.invoke(registration, id) as String
        val connection = mockk<PlayerGameConnection> { every { this@mockk.player } returns player }
        runtime.onCustomClick(mockk {
            every { commonConnection } returns connection
            every { identifier } returns Key.key(key)
            every { dialogResponseView } returns null
        })
    }
}
