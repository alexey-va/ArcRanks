package ru.ruscrafting.ranks.gui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.contract.ActiveContract
import ru.ruscrafting.ranks.contract.ContractAcceptResult
import ru.ruscrafting.ranks.contract.ContractAdminCompleteResult
import ru.ruscrafting.ranks.contract.ContractBoard
import ru.ruscrafting.ranks.contract.ContractCycle
import ru.ruscrafting.ranks.contract.ContractId
import ru.ruscrafting.ranks.contract.ContractOffer
import ru.ruscrafting.ranks.contract.ContractService
import ru.ruscrafting.ranks.domain.MasteryLevel
import ru.ruscrafting.ranks.domain.PathAvailability
import ru.ruscrafting.ranks.domain.PlayerProgressProfile
import ru.ruscrafting.ranks.domain.ProgressSnapshot
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.rankstate.RankState
import ru.ruscrafting.ranks.service.RankPlayerService
import ru.ruscrafting.ranks.service.RankPlayerSnapshot
import ru.ruscrafting.ranks.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.ranks.text.RankLocale
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CompletableFuture

class ContractMenuAdminMockBukkitTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "completed contract honeycombs explain the exact weekly state" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("ArcRanksContractStampMessageTest")
                val player = paper.addPlayer("ContractStampTester")
                val root = Files.createTempDirectory("arcranks-contract-stamp-message")
                val settings = ArcRanksSettings.loadFresh(root) { "secret" }
                val locale = RankLocale.fresh(root, { settings.defaultLocale }, { settings.useClientLocale })
                val snapshot = RankPlayerSnapshot(
                    RankState.Missing,
                    PlayerProgressProfile(ProgressSnapshot.EMPTY, SpecializationPath.FARMING),
                    null,
                    SpecializationPath.entries.associateWith { MasteryLevel.NONE },
                    PathAvailability.allAvailable(),
                    emptySet(),
                )
                val cycle = ContractCycle.at(Instant.parse("2026-08-29T18:42:00Z"))
                val board = ContractBoard(cycle, 2, 2, null, emptyList(), false)
                val players = mockk<RankPlayerService>()
                val contracts = mockk<ContractService>()
                every { players.load(player.uniqueId) } returns CompletableFuture.completedFuture(snapshot)
                every { contracts.board(player.uniqueId, any()) } returns CompletableFuture.completedFuture(board)
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                try {
                    val menu = ContractMenu(
                        { settings }, { locale }, players, contracts, tasks, null,
                        back = {},
                        layouts = ArcRanksMenuLayouts(root),
                    )
                    paper.server.pluginManager.registerEvents(menu, plugin)

                    menu.open(player)
                    paper.performTicks(1)

                    val first = requireNotNull(player.openInventory.topInventory.getItem(10))
                    val second = requireNotNull(player.openInventory.topInventory.getItem(16))
                    val plain = PlainTextComponentSerializer.plainText()
                    plain.serialize(requireNotNull(first.itemMeta.displayName())) shouldBe "Контракт 1 из 3 завершён"
                    plain.serialize(requireNotNull(second.itemMeta.displayName())) shouldBe "Контракт 2 из 3 завершён"
                    second.itemMeta.lore().orEmpty().map(plain::serialize).any {
                        "Выполнено контрактов: 2 из 3" in it
                    } shouldBe true
                } finally {
                    tasks.close()
                }
            }
        }
    }

    "accepting a contract tells the player the exact objective actions reward and next step" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("ArcRanksContractAcceptedMessageTest")
                val player = paper.addPlayer("ContractMessageTester")
                val root = Files.createTempDirectory("arcranks-contract-accepted-message")
                val settings = ArcRanksSettings.loadFresh(root) { "secret" }
                val locale = RankLocale.fresh(root, { settings.defaultLocale }, { settings.useClientLocale })
                val snapshot = RankPlayerSnapshot(
                    RankState.Missing,
                    PlayerProgressProfile(ProgressSnapshot.EMPTY, SpecializationPath.FARMING),
                    null,
                    SpecializationPath.entries.associateWith { MasteryLevel.NONE },
                    PathAvailability.allAvailable(),
                    emptySet(),
                )
                val cycle = ContractCycle.at(Instant.parse("2026-08-29T18:42:00Z"))
                val offer = ContractOffer(
                    ContractId("aaaaaaaaaaaaaaaaaaaaaaaa"), cycle, 0, 0,
                    SpecializationPath.FARMING, 180, 18,
                )
                val active = ActiveContract(
                    offer.id, cycle, 0, SpecializationPath.FARMING,
                    0, 180, 18, 0,
                )
                val board = ContractBoard(cycle, 0, 0, null, listOf(offer), true)
                val players = mockk<RankPlayerService>()
                val contracts = mockk<ContractService>()
                every { players.load(player.uniqueId) } returns CompletableFuture.completedFuture(snapshot)
                every { contracts.board(player.uniqueId, any()) } returns CompletableFuture.completedFuture(board)
                every { contracts.accept(player.uniqueId, offer.id, any()) } returns
                    CompletableFuture.completedFuture(ContractAcceptResult.Accepted(active))
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                try {
                    val menu = ContractMenu(
                        { settings }, { locale }, players, contracts, tasks, null,
                        back = {},
                        layouts = ArcRanksMenuLayouts(root),
                    )
                    paper.server.pluginManager.registerEvents(menu, plugin)

                    menu.open(player)
                    paper.performTicks(1)
                    paper.callEvent(
                        InventoryClickEvent(
                            player.openInventory,
                            InventoryType.SlotType.CONTAINER,
                            20,
                            ClickType.LEFT,
                            InventoryAction.PICKUP_ALL,
                        ),
                    )
                    paper.performTicks(1)

                    PlainTextComponentSerializer.plainText().serialize(requireNotNull(player.nextComponentMessage())) shouldBe
                        "Ранги • Контракт принят: Земледелие.\n" +
                        "Цель: наберите 180 очков.\n" +
                        "Засчитывается:\n" +
                        "  Собирайте спелый урожай — +1\n" +
                        "  Ловите рыбу — +5\n" +
                        "  Разводите животных — +8\n" +
                        "Награда: +18 к пути, 2000 монет, 1 жет.\n" +
                        "Предмет: 1 × Токен зачарования.\n" +
                        "После выполнения откройте /rank → «Контракты».\n" +
                        "Заберите готовую награду."
                } finally {
                    tasks.close()
                }
            }
        }
    }

    "admin contract control is absent for players and completes without claiming for authorized admins" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("ArcRanksContractAdminMenuTest")
                val player = paper.addPlayer("ContractAdminTester")
                player.isOp = false
                val root = Files.createTempDirectory("arcranks-contract-admin-menu")
                val settings = ArcRanksSettings.loadFresh(root) { "secret" }
                Config(root, "config.yml").apply {
                    setInt("gui.layouts.contracts.elements.admin-complete.slot", 49)
                    save()
                }
                val locale = RankLocale.fresh(root, { settings.defaultLocale }, { settings.useClientLocale })
                val snapshot = RankPlayerSnapshot(
                    RankState.Missing,
                    PlayerProgressProfile(ProgressSnapshot.EMPTY, SpecializationPath.COMMUNITY),
                    null,
                    SpecializationPath.entries.associateWith { MasteryLevel.NONE },
                    PathAvailability.allAvailable(),
                    emptySet(),
                )
                val cycle = ContractCycle.at(Instant.parse("2026-08-29T18:42:00Z"))
                val active = ActiveContract(
                    ContractId("aaaaaaaaaaaaaaaaaaaaaaaa"), cycle, 0, SpecializationPath.COMMUNITY,
                    0, 60, 6, 4,
                )
                val board = ContractBoard(cycle, 0, 0, active, emptyList(), false)
                val players = mockk<RankPlayerService>()
                val contracts = mockk<ContractService>()
                every { players.load(player.uniqueId) } returns CompletableFuture.completedFuture(snapshot)
                every { contracts.board(player.uniqueId, any()) } returns CompletableFuture.completedFuture(board)
                every { contracts.adminComplete(player.uniqueId, player.uniqueId.toString()) } returns
                    CompletableFuture.completedFuture(ContractAdminCompleteResult.Completed(active.copy(adminCompleted = true)))
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                try {
                    val menu = ContractMenu(
                        { settings }, { locale }, players, contracts, tasks, null,
                        back = {},
                        layouts = ArcRanksMenuLayouts(root),
                    )
                    paper.server.pluginManager.registerEvents(menu, plugin)

                    menu.open(player)
                    paper.performTicks(1)
                    player.openInventory.topInventory.getItem(49)?.type shouldBe
                        Material.GRAY_STAINED_GLASS_PANE

                    player.addAttachment(plugin, ContractMenu.ADMIN_CONTRACT_PERMISSION, true)
                    menu.open(player)
                    paper.performTicks(1)
                    val control = requireNotNull(
                        player.openInventory.topInventory.getItem(49),
                    )
                    control.type shouldBe Material.COMMAND_BLOCK
                    player.openInventory.topInventory.getItem(48)?.type shouldBe
                        Material.GRAY_STAINED_GLASS_PANE
                    PlainTextComponentSerializer.plainText().serialize(requireNotNull(control.itemMeta.displayName())) shouldBe
                        "Админ: завершить контракт"

                    paper.callEvent(
                        InventoryClickEvent(
                            player.openInventory,
                            InventoryType.SlotType.CONTAINER,
                            49,
                            ClickType.LEFT,
                            InventoryAction.PICKUP_ALL,
                        ),
                    )
                    paper.performTicks(1)

                    verify(exactly = 1) { contracts.adminComplete(player.uniqueId, player.uniqueId.toString()) }
                    verify(exactly = 0) { contracts.claim(any()) }
                } finally {
                    tasks.close()
                }
            }
        }
    }
})
