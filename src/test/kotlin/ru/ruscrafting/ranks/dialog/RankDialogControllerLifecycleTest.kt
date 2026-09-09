package ru.ruscrafting.ranks.dialog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.key.Key
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
            pathTable.lines().any { it.contains("0 / 250") && !it.contains("Ступень") } shouldBe true
            val tooltip = plain.serialize(capture.screens.last().buttons.first().tooltip)
            tooltip.contains("+10%") shouldBe true
            tooltip.contains("100") shouldBe true
            tooltip.contains("\n\n") shouldBe true
            tooltip.lines().filter { it.isNotBlank() }.all { it == it.trimStart() } shouldBe true
            click(harness, player, "path_farming")
            val detail = capture.screens.last().body.joinToString { plain.serialize(it.text) }
            detail.replace(Regex("\\p{Co}"), "").replace(Regex("\\s+"), " ").contains("на ежедневные задания не действует") shouldBe true
            detail.contains("Ступень") shouldBe true
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
    val locale = mockk<RankLocale>(relaxed = true)
    val catalog = RankCatalog(listOf(RankDefinition(RankId("settler"), "default", 1, "ranks.settler.name", 0, 0, SpecializationPath.entries.associateWith { 0L }, listOf("ranks.settler.benefit"))))
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
