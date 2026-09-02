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
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.contract.ActiveContract
import ru.ruscrafting.ranks.contract.ContractAdminCompleteResult
import ru.ruscrafting.ranks.contract.ContractBoard
import ru.ruscrafting.ranks.contract.ContractCycle
import ru.ruscrafting.ranks.contract.ContractId
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

    "admin contract control is absent for players and completes without claiming for authorized admins" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("ArcRanksContractAdminMenuTest")
                val player = paper.addPlayer("ContractAdminTester")
                player.isOp = false
                val root = Files.createTempDirectory("arcranks-contract-admin-menu")
                val settings = ArcRanksSettings.loadFresh(root) { "secret" }
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
                    val menu = ContractMenu({ settings }, { locale }, players, contracts, tasks, null, back = {})
                    paper.server.pluginManager.registerEvents(menu, plugin)

                    menu.open(player)
                    paper.performTicks(1)
                    player.openInventory.topInventory.getItem(ContractMenu.ADMIN_COMPLETE_SLOT)?.type shouldBe
                        Material.GRAY_STAINED_GLASS_PANE

                    player.addAttachment(plugin, ContractMenu.ADMIN_CONTRACT_PERMISSION, true)
                    menu.open(player)
                    paper.performTicks(1)
                    val control = requireNotNull(
                        player.openInventory.topInventory.getItem(ContractMenu.ADMIN_COMPLETE_SLOT),
                    )
                    control.type shouldBe Material.COMMAND_BLOCK
                    PlainTextComponentSerializer.plainText().serialize(requireNotNull(control.itemMeta.displayName())) shouldBe
                        "Админ: завершить контракт"

                    paper.callEvent(
                        InventoryClickEvent(
                            player.openInventory,
                            InventoryType.SlotType.CONTAINER,
                            ContractMenu.ADMIN_COMPLETE_SLOT,
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
