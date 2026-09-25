package ru.ruscrafting.ranks.dialog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor
import org.bukkit.entity.Player
import io.papermc.paper.connection.PlayerGameConnection
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.admin.AdminProgressService
import ru.ruscrafting.ranks.analytics.AnalyticsService
import ru.ruscrafting.ranks.analytics.TelemetryHealthSnapshot
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.contract.ContractService
import ru.ruscrafting.ranks.contract.ContractRewardDeliveryResult
import ru.ruscrafting.ranks.domain.*
import ru.ruscrafting.ranks.kit.*
import ru.ruscrafting.ranks.perk.*
import ru.ruscrafting.ranks.promotion.PromotionService
import ru.ruscrafting.ranks.rankstate.RankState
import ru.ruscrafting.ranks.service.RankPlayerService
import ru.ruscrafting.ranks.service.RankPlayerSnapshot
import ru.ruscrafting.ranks.text.RankLocale
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

class RankDialogControllerLifecycleTest : FunSpec({
    test("root shows today's quests and paths expose goals mastery and actual perk effects") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("RankSummary")
            val player = paper.addPlayer("RankSummary")
            val base = snapshot()
            val next = base.evaluation!!.currentRank.copy(id = RankId("peasant"), displayNameKey = "ranks.peasant.name")
            val progress = base.copy(evaluation = base.evaluation.copy(
                nextRank = next, eligibility = RankEligibility.CORE_INCOMPLETE,
                recommendation = NextStep.ActiveMinutes(25),
                activeMinutesCurrent = 1475, activeMinutesRequired = 1500,
                goals = SpecializationPath.entries.map { GoalProgress(it, 0, 250, GoalState.INCOMPLETE) },
            ))
            val players = mockk<RankPlayerService>()
            every { players.load(player.uniqueId) } returns CompletableFuture.completedFuture(progress)
            val capture = RankPresenterCapture()
            val board = ru.ruscrafting.ranks.quest.DailyQuestBoard(java.time.LocalDate.now(), listOf(
                ru.ruscrafting.ranks.quest.DailyQuestProgress(ru.ruscrafting.ranks.quest.DailyQuest.ALL.first(), 100),
                ru.ruscrafting.ranks.quest.DailyQuestProgress(ru.ruscrafting.ranks.quest.DailyQuest.ALL[1], 0),
            ))
            val locale = RankLocale.fresh(java.nio.file.Path.of("src/main/resources"), { "ru" }, { false })
            val harness = controller(plugin, players, capture, actualLocale = locale, questBoard = board)
            val plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            harness.controller.beginFlowAndOpen(player)
            paper.performTicks(2)
            val rootText = capture.screens.last().body.joinToString { plain.serialize(it.text) }
            rootText.contains("1 / 2 выполнено") shouldBe true
            rootText.contains("25") shouldBe true
            rootText.contains("без AFK") shouldBe true
            rootText.contains("24 ч. 35 мин.") shouldBe true
            rootText.contains("25 ч.") shouldBe true
            rootText.replace(Regex("[\\p{Co}\\s]+"), " ").contains("для повышения") shouldBe true
            rootText.contains(plain.serialize(locale.render("ranks.peasant.name"))) shouldBe true
            click(harness, player, "paths")
            val pathsText = capture.screens.last().body.joinToString { plain.serialize(it.text) }
            pathsText.contains("0 / 250") shouldBe true
            // Screenshot regression: metadata is outside the six path rows;
            // each overview row contains a compact ratio; mastery stays in details.
            capture.screens.last().body.size shouldBe 3
            val pathTable = plain.serialize(capture.screens.last().body.last().text)
            pathTable.contains("до цели ранга") shouldBe false
            pathTable.contains("Выбранный путь") shouldBe false
            if (pathTable.contains('\uE570')) {
                pathTable.count { it == '\uE577' } shouldBe 5
            }
            pathTable.lines().any { it.contains("0 / 250") && !it.contains("Ступень") } shouldBe true
            val tooltip = plain.serialize(capture.screens.last().buttons.first().tooltip)
            tooltip.contains("+10%") shouldBe true
            tooltip.contains("100") shouldBe true
            tooltip.contains("\n\n") shouldBe true
            tooltip.lines().filter { it.isNotBlank() }.all { it == it.trimStart() } shouldBe true
            click(harness, player, "path_farming")
            val detail = capture.screens.last().body.joinToString { plain.serialize(it.text) }
            detail.replace(Regex("\\p{Co}"), "").replace(Regex("\\s+"), " ").contains("контракт") shouldBe false
            detail.contains("Ступень") shouldBe true
        }
    }

    test("all rank sections and perk paths keep useful data in aligned bodies") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("RankCards")
            val player = paper.addPlayer("RankCards")
            val players = mockk<RankPlayerService>()
            every { players.load(player.uniqueId) } returns CompletableFuture.completedFuture(snapshot())
            val capture = RankPresenterCapture()
            val locale = RankLocale.fresh(java.nio.file.Path.of("src/main/resources"), { "ru" }, { false })
            val harness = controller(plugin, players, capture, actualLocale = locale)
            val catalog = ru.ruscrafting.ranks.config.RankCatalogLoader(ru.arc.config.Config(java.nio.file.Path.of("src/main/resources"), "ranks.yml")).load()
            val plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            harness.controller.beginFlowAndOpen(player)
            paper.performTicks(2)
            click(harness, player, "benefits")
            catalog.ranks.forEach { rank ->
                click(harness, player, "rank_${rank.id.value}")
                val ids = capture.screens.last().buttons.map { it.id.value }
                val sections = mutableListOf(capture.screens.last())
                ids.drop(1).forEach { id -> click(harness, player, id); sections += capture.screens.last() }
                val output = sections.flatMap { it.body }.joinToString(" ") { plain.serialize(it.text) }
                output.contains("Ваш ранг") shouldBe false
                output.contains("Состояние") shouldBe false
                sections.forEach { section ->
                    check(section.body.isNotEmpty() && section.body.all { body -> body.width == 468 }) {
                        "${section.id} body widths: ${section.body.map { it.width }}"
                    }
                }
                rank.benefitKeys.forEach { key ->
                    val words = plain.serialize(locale.render(key)).replace("•", "").split(Regex("\\s+")).filter { it.length > 2 }
                    words.forEach { word -> check(output.contains(word.trimEnd(':'))) { "Missing $word from $key" } }
                }
                click(harness, player, capture.screens.last().exitButton!!.id.value)
                paper.performTicks(2)
            }
            click(harness, player, capture.screens.last().exitButton!!.id.value)
            paper.performTicks(2)
            click(harness, player, "perks")
            paper.performTicks(2)
            click(harness, player, "slot_1")
            SpecializationPath.entries.forEach { path ->
                click(harness, player, "perk_path_${path.name.lowercase()}")
                capture.screens.last().body.size shouldBe 4
                capture.screens.last().body.drop(1).all { it.width == 468 } shouldBe true
                if (System.getProperty("arcranks.dialogPreview") != null) {
                    capture.screens.last().body.drop(1).zip(listOf('\uE540', '\uE560', '\uE580')).forEach { (card, frameStart) ->
                        plain.serialize(card.text).contains(frameStart) shouldBe true
                    }
                }
                capture.screens.last().buttons.size shouldBe 3
                click(harness, player, capture.screens.last().exitButton!!.id.value)
                paper.performTicks(2)
            }
        }
    }

    test("Back restores a fresh root snapshot after a real child visit") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("RankDialogLifecycleTest")
            val player = paper.addPlayer("RankDialogLifecycle")
            val first = CompletableFuture.completedFuture(snapshot())
            val second = CompletableFuture<RankPlayerSnapshot>()
            val players = mockk<RankPlayerService>()
            every { players.load(player.uniqueId) } returnsMany listOf(first, second)
            val capture = RankPresenterCapture()
            val harness = controller(plugin, players, capture)
            val controller = harness.controller

            controller.beginFlowAndOpen(player)
            paper.performTicks(1)
            click(harness, player, "paths")
            capture.screens.last().id shouldBe "ranks.paths"
            click(harness, player, capture.screens.last().exitButton!!.id.value)
            capture.screens.last().id shouldBe "ranks.root"
            capture.screens.last().buttons.any { it.id.value == "chest" } shouldBe false
            second.complete(snapshot())
            paper.performTicks(1)
            capture.screens.last().id shouldBe "ranks.root"
            capture.screens.last().buttons.any { it.id.value == "chest" } shouldBe false
            verify(exactly = 2) { players.load(player.uniqueId) }
        }
    }

    test("dismissed loading future cannot publish a late screen") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("RankDialogDismissTest")
            val player = paper.addPlayer("RankDialogDismiss")
            val pending = CompletableFuture<RankPlayerSnapshot>()
            val players = mockk<RankPlayerService>()
            every { players.load(player.uniqueId) } returns pending
            val capture = RankPresenterCapture()
            val harness = controller(plugin, players, capture)
            val controller = harness.controller

            controller.beginFlowAndOpen(player)
            capture.screens.last().id shouldBe "ranks.root"
            capture.screens.last().buttons.any { it.id.value == "chest" } shouldBe false
            val count = capture.screens.size
            click(harness, player, capture.screens.last().exitButton!!.id.value)
            val afterDismiss = capture.screens.size
            pending.complete(snapshot())
            paper.performTicks(1)
            capture.screens.size shouldBe afterDismiss
        }
    }

    test("explicit close preference marks root and child footers") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("RankDialogCloseTest")
            val player = paper.addPlayer("RankDialogClose")
            val players = mockk<RankPlayerService>()
            every { players.load(player.uniqueId) } returns CompletableFuture.completedFuture(snapshot())
            val capture = RankPresenterCapture()
            val harness = controller(plugin, players, capture, close = true)
            val controller = harness.controller

            controller.beginFlowAndOpen(player)
            paper.performTicks(1)
            capture.screens.last().exitButton!!.closeDialogBeforeAction shouldBe true
            click(harness, player, "paths")
            capture.screens.last().exitButton!!.closeDialogBeforeAction shouldBe true
        }
    }

    test("insufficient available paths use the unavailable recommendation") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("RankUnavailableRecommendation")
            val player = paper.addPlayer("RankUnavailableRecommendation")
            val base = snapshot()
            val next = base.evaluation!!.currentRank.copy(id = RankId("peasant"), displayNameKey = "ranks.peasant.name")
            val unavailable = base.copy(
                evaluation = base.evaluation.copy(
                    nextRank = next,
                    eligibility = RankEligibility.INSUFFICIENT_AVAILABLE_PATHS,
                    availableChoices = 0,
                    requiredChoices = 2,
                    recommendation = null,
                ),
                availability = PathAvailability(SpecializationPath.entries.toSet()),
            )
            val players = mockk<RankPlayerService>()
            every { players.load(player.uniqueId) } returns CompletableFuture.completedFuture(unavailable)
            val capture = RankPresenterCapture()
            val locale = RankLocale.fresh(java.nio.file.Path.of("src/main/resources"), { "ru" }, { false })
            val harness = controller(plugin, players, capture, actualLocale = locale)
            val plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            harness.controller.beginFlowAndOpen(player)
            paper.performTicks(2)
            val rootText = capture.screens.last().body.joinToString { plain.serialize(it.text) }
            rootText.contains(plain.serialize(locale.render("gui.recommendation.unavailable", player))) shouldBe true
            rootText.contains(plain.serialize(locale.render("gui.recommendation.ready", player))) shouldBe false
        }
    }

    test("paths table keeps completion markers and detail tiers readable") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("RankPathSurfaceRegression")
            val player = paper.addPlayer("RankPathSurface")
            val players = mockk<RankPlayerService>()
            every { players.load(player.uniqueId) } returns CompletableFuture.completedFuture(pathSurfaceSnapshot())
            val capture = RankPresenterCapture()
            val locale = RankLocale.fresh(java.nio.file.Path.of("src/main/resources"), { "ru" }, { false })
            val harness = controller(plugin, players, capture, actualLocale = locale)
            val plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()

            harness.controller.beginFlowAndOpen(player)
            paper.performTicks(2)
            click(harness, player, "paths")
            val paths = capture.screens.last()
            val table = plain.serialize(paths.body.last().text)
            table.contains("Готово") shouldBe true
            table.contains("✔") shouldBe true
            table.contains("250 / 250") shouldBe true
            table.contains("○") shouldBe true
            table.contains("12 / 250") shouldBe true
            styledRuns(paths.body.last().text).filter { "✔" in it.text }.mapNotNull { it.color }.distinct() shouldBe
                listOf(TextColor.color(0x9bd48d))
            styledRuns(paths.body.last().text).filter { "○" in it.text }.mapNotNull { it.color }.distinct() shouldBe
                listOf(TextColor.color(0xf4bd6a))
            val unavailableRow = table.substringAfter("Промышленность").substringBefore("Торговля")
            unavailableRow.contains("Готово") shouldBe false
            unavailableRow.contains("✔") shouldBe false

            val pathButtons = paths.buttons.filter { it.id.value.startsWith("path_") }
            pathButtons shouldHaveSize SpecializationPath.entries.size
            pathButtons.map { plain.serialize(it.label) }.forEach { label ->
                label shouldBe label.trimStart()
                label.contains("В процессе") shouldBe false
                label.contains("—") shouldBe false
            }
            val farmingButton = pathButtons.first { it.id.value == "path_farming" }
            val farmingLabel = plain.serialize(farmingButton.label)
            farmingLabel.contains("✔") shouldBe true
            farmingLabel.contains("★") shouldBe true
            val pathNames = SpecializationPath.entries.map { path ->
                plain.serialize(locale.render("paths.${path.name.lowercase()}.name", player))
            }
            pathNames.distinct() shouldHaveSize SpecializationPath.entries.size
            val expectedPathColors = listOf(
                0x9bd48d,
                0x85dfc4,
                0xf4d87a,
                0x86dcf1,
                0xffb277,
                0xf3a2c9,
            ).map { TextColor.color(it) }
            pathButtons.zip(pathNames).zip(expectedPathColors).forEach { (buttonAndName, expectedColor) ->
                val (button, name) = buttonAndName
                plain.serialize(button.label).contains(name) shouldBe true
                styledRuns(button.label).filter { name in it.text }.mapNotNull { it.color }.distinct() shouldBe listOf(expectedColor)
            }
            pathButtons.flatMap { button ->
                styledRuns(button.label).filter { run -> pathNames.any { it in run.text } }.mapNotNull { it.color }
            }.distinct() shouldHaveSize SpecializationPath.entries.size

            click(harness, player, "path_farming")
            val detail = capture.screens.last()
            val perkTable = detail.body[3].text
            countCodePoint(perkTable, 0xE577) shouldBe 2
            val perkCatalog = ru.ruscrafting.ranks.perk.PerkCatalogLoader(
                ru.arc.config.Config(java.nio.file.Path.of("src/main/resources"), "perks.yml"),
            ).load()
            val perkNames = perkCatalog.forPath(SpecializationPath.FARMING).map { perk ->
                perk to plain.serialize(locale.render(perk.nameKey, player))
            }
            perkNames.map { it.second }.distinct() shouldHaveSize 3
            val expectedTierColors = mapOf(
                MasteryLevel.I to TextColor.color(0x9bd48d),
                MasteryLevel.II to TextColor.color(0x86dcf1),
                MasteryLevel.III to TextColor.color(0xc4abff),
            )
            val detailText = plain.serialize(perkTable)
            perkNames.forEach { (perk, name) ->
                detailText.normalizedDialogText().contains(name.normalizedDialogText()) shouldBe true
                colorsForPhrase(perkTable, name) shouldBe
                    listOf(expectedTierColors.getValue(perk.requiredMastery))
            }
        }
    }
})

private class RankPresenterCapture {
    val screens = mutableListOf<PaperDialogScreen>()
    val registrations = mutableListOf<Any>()
    fun present(screen: PaperDialogScreen, registration: Any) { exportDialogPreview(screen); screens += screen; registrations += registration }
}

private data class ControllerHarness(val controller: RankDialogController, val runtime: PaperDialogRuntime, val capture: RankPresenterCapture)

private fun controller(plugin: org.bukkit.plugin.Plugin, players: RankPlayerService, capture: RankPresenterCapture, close: Boolean = false,
    actualLocale: RankLocale? = null,
    questBoard: ru.ruscrafting.ranks.quest.DailyQuestBoard? = null,
): ControllerHarness {
    val runtimeCtor = PaperDialogRuntime::class.java.declaredConstructors.first { it.parameterCount == 2 }.apply { isAccessible = true }
    val presenter: (Player, PaperDialogScreen, Any) -> Unit = { _, screen, registration -> capture.present(screen, registration) }
    val runtime = runtimeCtor.newInstance(plugin, presenter) as PaperDialogRuntime
    val settings = mockk<ArcRanksSettings>(relaxed = true)
    every { settings.features.perks } returns true
    val locale = mockk<RankLocale>(relaxed = true)
    val catalog = if (actualLocale != null) ru.ruscrafting.ranks.config.RankCatalogLoader(ru.arc.config.Config(java.nio.file.Path.of("src/main/resources"), "ranks.yml")).load() else RankCatalog(listOf(RankDefinition(RankId("settler"), "default", 1, "ranks.settler.name", 0, 0, SpecializationPath.entries.associateWith { 0L }, listOf("ranks.settler.benefit"))))
    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    return ControllerHarness(RankDialogController(runtime, { settings }, { catalog }, { actualLocale ?: previewLocale() ?: locale }, players,
        mockk<PromotionService>(relaxed = true), mockk<AdminProgressService>(relaxed = true),
        mockk<ContractService>(relaxed = true), { _, _ -> CompletableFuture.completedFuture(ContractRewardDeliveryResult.PENDING) },
        { if (actualLocale == null) mockk<PerkCatalog>(relaxed = true) else PerkCatalogLoader(ru.arc.config.Config(java.nio.file.Path.of("src/main/resources"), "perks.yml")).load() }, mockk<PerkSelectionService>(relaxed = true),
        { mockk<WeeklyKitCatalog>(relaxed = true) }, mockk<WeeklyKitService>(relaxed = true),
        mockk<AnalyticsService>(relaxed = true), { mockk<TelemetryHealthSnapshot>(relaxed = true) }, tasks, {}, closeOnEscape = { close },
        loadQuestSummary = { CompletableFuture.completedFuture(questBoard) },
        masteryThresholds = { SpecializationPath.entries.associateWith { MasteryThresholds(100, 500, 1000) } }), runtime, capture)
}

private fun click(harness: ControllerHarness, player: Player, id: String) {
    val registration = harness.capture.registrations.last()
    val keyMethod = registration.javaClass.declaredMethods.first { it.name.startsWith("key") }.apply { isAccessible = true }
    val key = keyMethod.invoke(registration, id) as String
    val connection = mockk<PlayerGameConnection> { every { this@mockk.player } returns player }
    harness.runtime.onCustomClick(mockk {
        every { commonConnection } returns connection
        every { identifier } returns Key.key(key)
        every { dialogResponseView } returns null
    })
}

private fun snapshot() = RankPlayerSnapshot(RankState.Exact(RankId("settler")), PlayerProgressProfile(ProgressSnapshot.EMPTY, SpecializationPath.FARMING),
    RankEvaluation(RankDefinition(RankId("settler"), "default", 1, "ranks.settler.name", 0, 0, SpecializationPath.entries.associateWith { 0L }, listOf("ranks.settler.benefit")), null, RankEligibility.TOP_RANK, 0, 0, 0, 0, 0, emptyList(), null),
    SpecializationPath.entries.associateWith { MasteryLevel.NONE }, PathAvailability.allAvailable(), emptySet())

private fun context(player: Player) = mockk<PaperDialogClickContext>(relaxed = true).also { every { it.player } returns player }

private fun pathSurfaceSnapshot(): RankPlayerSnapshot {
    val base = snapshot()
    val next = base.evaluation!!.currentRank.copy(id = RankId("peasant"), displayNameKey = "ranks.peasant.name")
    val values = mapOf(
        ProgressMetric.CROPS_HARVESTED to 250L,
        ProgressMetric.PRODUCTION_ACTIONS to 17L,
        ProgressMetric.WEALTH_PEAK to 12L,
        ProgressMetric.TRAVEL_BLOCKS to 33L,
        ProgressMetric.BLOCKS_PLACED to 44L,
        ProgressMetric.COMMUNITY_MINUTES to 55L,
    )
    val goals = SpecializationPath.entries.map { path ->
        GoalProgress(path, ProgressSnapshot(values).value(path.metric), 250L,
            when (path) {
                SpecializationPath.FARMING -> GoalState.COMPLETE
                SpecializationPath.INDUSTRY -> GoalState.UNAVAILABLE
                else -> GoalState.INCOMPLETE
            })
    }
    return base.copy(
        profile = PlayerProgressProfile(ProgressSnapshot(values), SpecializationPath.FARMING),
        evaluation = base.evaluation.copy(
            nextRank = next,
            eligibility = RankEligibility.CHOICES_INCOMPLETE,
            completedChoices = 1,
            availableChoices = SpecializationPath.entries.size - 1,
            requiredChoices = 2,
            goals = goals,
        ),
        mastery = SpecializationPath.entries.associateWith { MasteryLevel.NONE },
        availability = PathAvailability(setOf(SpecializationPath.INDUSTRY)),
    )
}

private data class StyledRun(val text: String, val color: TextColor?)

private fun styledRuns(component: Component): List<StyledRun> {
    val result = mutableListOf<StyledRun>()
    fun visit(node: Component, inherited: Style) {
        val style = node.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
        val text = (node as? TextComponent)?.content().orEmpty()
        if (text.isNotEmpty()) result += StyledRun(text, style.color())
        node.children().forEach { visit(it, style) }
    }
    visit(component, Style.empty())
    return result
}

private fun colorsForPhrase(component: Component, phrase: String): List<TextColor> {
    val words = phrase.split(Regex("\\s+")).filter(String::isNotBlank)
    return styledRuns(component).filter { run -> words.any { it in run.text } }.mapNotNull { it.color }.distinct()
}

private fun String.normalizedDialogText(): String = replace(Regex("\\s+"), " ").trim()

private fun countCodePoint(component: Component, codePoint: Int): Int =
    styledRuns(component).sumOf { it.text.codePoints().filter { point -> point == codePoint }.count().toInt() }
