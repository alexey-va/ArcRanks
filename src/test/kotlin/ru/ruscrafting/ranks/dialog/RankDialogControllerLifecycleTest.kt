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
            second.complete(snapshot())
            paper.performTicks(1)
            capture.screens.last().id shouldBe "ranks.root"
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

private fun controller(plugin: org.bukkit.plugin.Plugin, players: RankPlayerService, capture: RankPresenterCapture, close: Boolean = false): ControllerHarness {
    val runtimeCtor = PaperDialogRuntime::class.java.declaredConstructors.first { it.parameterCount == 2 }.apply { isAccessible = true }
    val presenter: (Player, PaperDialogScreen, Any) -> Unit = { _, screen, registration -> capture.present(screen, registration) }
    val runtime = runtimeCtor.newInstance(plugin, presenter) as PaperDialogRuntime
    val settings = mockk<ArcRanksSettings>(relaxed = true)
    val locale = mockk<RankLocale>(relaxed = true)
    val catalog = RankCatalog(listOf(RankDefinition(RankId("settler"), "default", 1, "ranks.settler.name", 0, 0, SpecializationPath.entries.associateWith { 0L }, listOf("ranks.settler.benefit"))))
    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    return ControllerHarness(RankDialogController(runtime, { settings }, { catalog }, { previewLocale() ?: locale }, players,
        mockk<PromotionService>(relaxed = true), mockk<AdminProgressService>(relaxed = true),
        mockk<ContractService>(relaxed = true), { _, _ -> CompletableFuture.completedFuture(ContractRewardDeliveryResult.PENDING) },
        { mockk<PerkCatalog>(relaxed = true) }, mockk<PerkSelectionService>(relaxed = true),
        { mockk<WeeklyKitCatalog>(relaxed = true) }, mockk<WeeklyKitService>(relaxed = true),
        mockk<AnalyticsService>(relaxed = true), { mockk<TelemetryHealthSnapshot>(relaxed = true) }, tasks, {}, closeOnEscape = { close }), runtime, capture)
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
