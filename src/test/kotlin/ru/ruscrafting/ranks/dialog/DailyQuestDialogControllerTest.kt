package ru.ruscrafting.ranks.dialog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.key.Key
import io.papermc.paper.connection.PlayerGameConnection
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
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
import ru.ruscrafting.ranks.quest.DailyRewardState
import ru.ruscrafting.ranks.quest.QuestDisplayMode
import ru.ruscrafting.ranks.quest.QuestMode
import ru.ruscrafting.ranks.quest.QuestPlan
import ru.ruscrafting.ranks.quest.QuestReplaceResult
import ru.ruscrafting.ranks.quest.QuestStep
import ru.ruscrafting.ranks.text.RankLocale
import java.time.LocalDate
import java.util.concurrent.CompletableFuture

class DailyQuestDialogControllerTest : FunSpec({
    test("catalog is paginated into twelve goal cards and keeps the utility row") {
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
                val expectedFooter = previewLocale()?.render("dialogs.common.back", player) ?: Component.text("dialogs.common.back")
                PlainTextComponentSerializer.plainText().serialize(capture.screens.last().exitButton!!.label) shouldBe
                    PlainTextComponentSerializer.plainText().serialize(expectedFooter)
                pending.complete(board)
                paper.performTicks(2)
                val catalog = capture.screens.last()
                catalog.id shouldBe "ranks.daily"
                catalog.columns shouldBe 2
                catalog.buttons.any { it.id.value == "chest" } shouldBe false
                catalog.buttons.count { it.id.value.startsWith("goal_") } shouldBe 12
                catalog.buttons.any { it.id.value == "next" } shouldBe true
                catalog.buttons.any { it.id.value == "rank" } shouldBe true
            } finally {
                tasks.close()
            }
        }
    }

    test("daily summary keeps two columns and separates quest progress from its reward") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("DailyQuestHierarchy")
            val player = paper.addPlayer("DailyHierarchy")
            val plain = PlainTextComponentSerializer.plainText()
            listOf("no-focus", "long", "en").forEach { sample ->
                val quest = DailyQuest.ALL.first().copy(id = "hierarchy", textId = "harvest", objective = "harvest",
                    target = if (sample == "long") 99999999 else 37,
                    money = if (sample == "long") 1000000 else 125)
                val daily = if (sample == "no-focus") board(8).copy(quests = listOf(DailyQuestProgress(quest, 9)) + board(7).quests)
                    else DailyQuestBoard(LocalDate.of(2026, 10, 23), listOf(DailyQuestProgress(quest, 9)),
                        replacementsLeft = 2, selectedFocus = ru.ruscrafting.ranks.domain.SpecializationPath.FARMING)
                val capture = PresenterCapture(export = false)
                val state = ActionState()
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                try {
                    val actualLocale = RankLocale.fresh(java.nio.file.Path.of("src/main/resources"), { if (sample == "en") "en" else "ru" }, { false })
                    controller(plugin, CompletableFuture.completedFuture(daily), capture, tasks, state, actualLocale).beginFlowAndOpen(player)
                    paper.performTicks(2)
                    val catalog = capture.screens.last()
                    catalog.body[1].width shouldBe 320
                    val text = catalog.body.joinToString(" ") { plain.serialize(it.text) }.replace(Regex("\\p{Co}"), "").replace(Regex("\\s+"), " ")
                    text.contains(if (sample == "en") "Optional for promotion" else "Для ранга необязательны") shouldBe true
                    if (sample == "no-focus") text.contains("Не выбран") shouldBe true
                    catalog.buttons.first().id.value shouldBe "goal_hierarchy"
                    exportDialogPreview(catalog, "daily-$sample")
                    click(capture, player, "goal_hierarchy")
                    val detail = capture.screens.last()
                    val bodies = detail.body.map { plain.serialize(it.text).replace(Regex("\\p{Co}"), "").replace(Regex("\\s+"), " ") }
                    val rewardHeading = bodies.indexOfFirst { it.contains(if (sample == "en") "Quest reward" else "Награда за задание") }
                    (rewardHeading > 0) shouldBe true
                    bodies[rewardHeading - 1].contains(if (sample == "en") "Progress" else "Прогресс") shouldBe true
                    bodies[rewardHeading - 1].contains("💰") shouldBe false
                    bodies[rewardHeading + 1].contains("💰") shouldBe true
                    detail.buttons.first().id.value shouldBe "track"
                    exportDialogPreview(detail, "daily-detail-$sample")
                    click(capture, player, "track")
                    paper.performTicks(2)
                    state.tracks shouldBe 1
                    state.selected shouldBe "hierarchy"
                } finally { tasks.close() }
            }
        }
    }

    test("rank shortcut keeps the visited daily page in native history") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("DailyQuestRankNavigation")
            val player = paper.addPlayer("DailyRankNavigation")
            val capture = PresenterCapture()
            val state = ActionState()
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            try {
                controller(plugin, CompletableFuture.completedFuture(board(13)), capture, tasks, state).beginFlowAndOpen(player)
                paper.performTicks(2)
                click(capture, player, "next")
                paper.performTicks(2)
                click(capture, player, "rank")
                state.rankOpens shouldBe 1
                capture.screens.last().id shouldBe "ranks.root"
                click(capture, player, capture.screens.last().exitButton!!.id.value)
                paper.performTicks(2)
                capture.screens.last().id shouldBe "ranks.daily"
                capture.screens.last().buttons.first().id.value shouldBe "goal_quest_12"
                capture.screens.last().buttons.any { it.id.value == "rank" } shouldBe true
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
                click(capture, player, "goal_quest_12")
                capture.screens.last().id shouldBe "ranks.daily.detail"
                capture.screens.last().buttons.any { it.id.value == "chest" } shouldBe false
                click(capture, player, "track")
                paper.performTicks(2)
                state.tracks shouldBe 1
                state.selected shouldBe "quest_12"
                click(capture, player, "replace")
                paper.performTicks(2)
                state.replacements shouldBe 1
                click(capture, player, "daily_footer")
                paper.performTicks(2)
                capture.screens.last().buttons.first().id.value shouldBe "goal_quest_12"
                click(capture, player, "goal_quest_12")
                state.generation++
                click(capture, player, "replace")
                state.replacements shouldBe 1
            } finally {
                tasks.close()
            }
        }
    }

    test("quest hover explains the action and monetary reward without opening details") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("DailyQuestTooltipTest")
            val player = paper.addPlayer("DailyTooltip")
            val capture = PresenterCapture(export = false)
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val quest = DailyQuest.ALL.first().copy(textId = "harvest", objective = "harvest", target = 37, money = 125)
            val board = DailyQuestBoard(LocalDate.parse("2026-09-08"), listOf(DailyQuestProgress(quest, 9)))
            try {
                val actualLocale = RankLocale.fresh(java.nio.file.Path.of("src/main/resources"), { "ru" }, { false })
                controller(plugin, CompletableFuture.completedFuture(board), capture, tasks, actualLocale = actualLocale).beginFlowAndOpen(player)
                paper.performTicks(2)
                val tooltipComponent = capture.screens.last().buttons.first().tooltip
                val tooltip = PlainTextComponentSerializer.plainText().serialize(tooltipComponent)
                tooltip.contains("37") shouldBe true
                tooltip.contains("Соберите зрелый урожай: 37 растений.") shouldBe true
                tooltip.contains("125") shouldBe true
                tooltip.contains("💰") shouldBe true
                tooltip.contains("Открыть подробности") shouldBe false
                tooltip.contains("<target>") shouldBe false
                tooltip.startsWith("\n") shouldBe true
                tooltip.endsWith("\n") shouldBe true
                tooltip.contains("\n\n") shouldBe true
                tooltip.lines().filter { it.isNotBlank() }.all { it == it.trimStart() } shouldBe true
                val colors = buildList {
                    fun collect(component: Component) {
                        component.color()?.value()?.let(::add)
                        component.children().forEach(::collect)
                    }
                    collect(tooltipComponent)
                }
                (0xb8b8b8 !in colors) shouldBe true
                (0x22d3ee in colors) shouldBe true
                (0xffffff in colors) shouldBe true
                val body = capture.screens.last().body.joinToString { net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it.text) }
                body.contains("Монеты и прогресс — автоматически") shouldBe false
            } finally {
                tasks.close()
            }
        }
    }

    test("detail shows the description and dispatches the current chain step destination") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("DailyMechanicTravel")
            val player = paper.addPlayer("MechanicTraveler")
            val capture = PresenterCapture(export = false)
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val actions = ActionState()
            val plan = ru.ruscrafting.ranks.quest.QuestPlan(ru.ruscrafting.ranks.quest.QuestMode.CHAIN, listOf(
                ru.ruscrafting.ranks.quest.QuestStep("craft", 1, "craft"),
                ru.ruscrafting.ranks.quest.QuestStep("furniture.craft", 1, "furniture_craft"),
            ))
            val quest = DailyQuest.ALL.first().copy(id = "workshop_chain", textId = "furniture_craft", plan = plan, target = 2)
            val board = DailyQuestBoard(LocalDate.parse("2026-10-20"), listOf(DailyQuestProgress(quest, 1, stepValues = listOf(1, 0))))
            try {
                val actualLocale = RankLocale.fresh(java.nio.file.Path.of("src/main/resources"), { "ru" }, { false })
                controller(plugin, CompletableFuture.completedFuture(board), capture, tasks, actions, actualLocale).beginFlowAndOpen(player)
                paper.performTicks(2)
                click(capture, player, "goal_workshop_chain")
                val screen = capture.screens.last()
                screen.buttons.single { it.id.value == "travel_workshop" }.closeDialogBeforeAction shouldBe true
                val body = screen.body.joinToString(" ") { PlainTextComponentSerializer.plainText().serialize(it.text) }
                body.contains("мастерской") shouldBe true
                body.contains("Эвана") shouldBe true
                body.contains("daily.furniture_craft.description") shouldBe false
                click(capture, player, "travel_workshop")
                actions.travel shouldBe ("furniture.craft" to "workshop")
            } finally {
                tasks.close()
            }
        }
    }

    test("rare quest is starred in the catalog and detail title") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("DailyQuestRareMarkerTest")
            val player = paper.addPlayer("DailyRareMarker")
            val capture = PresenterCapture(export = false)
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val quest = DailyQuest.ALL.first().copy(
                textId = "harvest",
                objective = "harvest",
                money = 450,
                tokens = 2,
            )
            val board = DailyQuestBoard(LocalDate.parse("2026-09-13"), listOf(DailyQuestProgress(quest, 9)))
            try {
                val actualLocale = RankLocale.fresh(java.nio.file.Path.of("src/main/resources"), { "ru" }, { false })
                controller(plugin, CompletableFuture.completedFuture(board), capture, tasks, actualLocale = actualLocale).beginFlowAndOpen(player)
                paper.performTicks(2)

                val card = capture.screens.last().buttons.first()
                PlainTextComponentSerializer.plainText().serialize(card.label).startsWith("★ ") shouldBe true
                PlainTextComponentSerializer.plainText().serialize(card.tooltip).contains("Редкое задание") shouldBe true

                click(capture, player, "goal_${quest.id}")
                PlainTextComponentSerializer.plainText().serialize(capture.screens.last().title).startsWith("★ ") shouldBe true
            } finally {
                tasks.close()
            }
        }
    }

    test("a concrete dungeon goal offers entrance travel and closes the dialog before dispatch") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("DailyQuestDungeonTravelTest")
            val player = paper.addPlayer("DailyDungeonTravel")
            val capture = PresenterCapture(export = false)
            val state = ActionState()
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val quest = DailyQuest.ALL.first().copy(
                id = "dungeon_mines", textId = "dungeon_mines", objective = "dungeon.complete:em_id_the_mines", target = 1,
            )
            try {
                val actualLocale = RankLocale.fresh(java.nio.file.Path.of("src/main/resources"), { "ru" }, { false })
                controller(
                    plugin,
                    CompletableFuture.completedFuture(DailyQuestBoard(LocalDate.parse("2026-09-08"), listOf(DailyQuestProgress(quest, 0)))),
                    capture,
                    tasks,
                    state,
                    actualLocale,
                ).beginFlowAndOpen(player)
                paper.performTicks(2)
                click(capture, player, "goal_dungeon_mines")
                val travel = capture.screens.last().buttons.single { it.id.value == "dungeon_travel" }
                travel.closeDialogBeforeAction shouldBe true
                val plain = PlainTextComponentSerializer.plainText()
                plain.serialize(travel.label).contains("Ко входу в подземелье") shouldBe true
                val tooltip = plain.serialize(requireNotNull(travel.tooltip))
                tooltip.contains("ко входу этого подземелья на Спавне") shouldBe true
                tooltip.contains("Для начала прохождения обратитесь к NPC") shouldBe true
                click(capture, player, "dungeon_travel")
                state.dungeonTravels shouldBe 1
                state.dungeonObjective shouldBe "dungeon.complete:em_id_the_mines"
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

    test("completed distinct fishing collection does not request its unused alternatives") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("DailyQuestDistinctChoice")
            val player = paper.addPlayer("DailyDistinctChoice")
            val capture = PresenterCapture()
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val actualLocale = RankLocale.fresh(java.nio.file.Path.of("src/main/resources"), { "ru" }, { false })
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
            val plain = PlainTextComponentSerializer.plainText()
            try {
                for (completed in listOf(false, true)) {
                    val state = DailyQuestProgress(quest, if (completed) 2 else 1,
                        rewardState = if (completed) DailyRewardState.GRANTED else null,
                        stepValues = listOf(2, if (completed) 2 else 0, 0, 0))
                    val board = DailyQuestBoard(LocalDate.parse("2026-10-23"), listOf(state))
                    controller(plugin, CompletableFuture.completedFuture(board), capture, tasks, actualLocale = actualLocale)
                        .beginFlowAndOpen(player)
                    paper.performTicks(2)
                    val card = capture.screens.last().buttons.first()
                    val tooltip = plain.serialize(card.tooltip)
                    tooltip.contains("Достаточно любых 2 из 4 вариантов.") shouldBe true
                    tooltip.contains("Условия выполнены.") shouldBe completed
                    if (completed) {
                        plain.serialize(card.label).contains("2/2") shouldBe true
                        plain.serialize(card.label).contains("0/1") shouldBe false
                        tooltip.contains("Поймайте иглобрюха — не требуется") shouldBe true
                    }
                    click(capture, player, "goal_angler_collection")
                    val body = capture.screens.last().body.joinToString("\n") { plain.serialize(it.text) }
                    body.contains("Достаточно любых 2 из 4 вариантов.") shouldBe true
                    body.contains("Условия выполнены.") shouldBe completed
                    if (completed) {
                        body.contains("Текущий этап:") shouldBe false
                        body.contains("✓ Поймайте треску — 2/2") shouldBe true
                        body.contains("✓ Поймайте лосося — 2/2") shouldBe true
                        body.contains("Поймайте иглобрюха — не требуется") shouldBe true
                        body.contains("Поймайте тропическую рыбу — не требуется") shouldBe true
                        body.contains("0/1") shouldBe false
                    }
                }
            } finally {
                tasks.close()
            }
        }
    }
})

private class PresenterCapture(private val export: Boolean = true) {
    val screens = mutableListOf<PaperDialogScreen>()
    lateinit var runtime: PaperDialogRuntime
    lateinit var registration: Any
    fun present(screen: PaperDialogScreen, registration: Any) {
        if (export) exportDialogPreview(screen)
        screens += screen
        this.registration = registration
    }
}

private fun controller(
    plugin: org.bukkit.plugin.Plugin,
    board: CompletableFuture<DailyQuestBoard>,
    capture: PresenterCapture,
    tasks: LifecycleTaskScope,
    state: ActionState = ActionState(),
    actualLocale: RankLocale? = null,
): DailyQuestDialogController {
    val runtimeCtor = PaperDialogRuntime::class.java.declaredConstructors.first { it.parameterCount == 2 }.apply { isAccessible = true }
    val presenter: (Player, PaperDialogScreen, Any) -> Unit = { _, screen, registration -> capture.present(screen, registration) }
    capture.runtime = runtimeCtor.newInstance(plugin, presenter) as PaperDialogRuntime
    val locale = mockk<RankLocale>()
    every { locale.render(any<String>(), any<CommandSender>(), any<Map<String, Component>>()) } answers { Component.text(firstArg<String>()) }
    every { locale.chat(any<String>(), any<CommandSender>(), any<Map<String, Component>>()) } answers { Component.text(firstArg<String>()) }
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
        locale = { actualLocale ?: previewLocale() ?: locale },
        openRank = { player ->
            state.rankOpens++
            capture.runtime.open(player, PaperDialogScreen(title = Component.text("Rank"), buttons = emptyList(), id = "ranks.root"))
        },
        openDungeon = { _, objective -> state.dungeonTravels++; state.dungeonObjective = objective; true },
        travelLocations = { if (it == "furniture.craft") listOf("workshop") else emptyList() },
        travel = { _, objective, location -> state.travel = objective to location; true },
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
    var dungeonTravels = 0
    var dungeonObjective: String? = null
    var travel: Pair<String, String>? = null
    var rankOpens = 0
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
