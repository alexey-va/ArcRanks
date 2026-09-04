package ru.ruscrafting.ranks.gui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.plugin.Plugin
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.admin.AdminProgressService
import ru.ruscrafting.ranks.api.RankProgressApi
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.config.PromotionMode
import ru.ruscrafting.ranks.config.RankCatalogLoader
import ru.ruscrafting.ranks.domain.MasteryLevel
import ru.ruscrafting.ranks.domain.PathAvailability
import ru.ruscrafting.ranks.domain.PlayerProgressProfile
import ru.ruscrafting.ranks.domain.ProgressSnapshot
import ru.ruscrafting.ranks.domain.RankEvaluator
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.promotion.PromotionService
import ru.ruscrafting.ranks.rankstate.RankState
import ru.ruscrafting.ranks.service.RankPlayerService
import ru.ruscrafting.ranks.service.RankPlayerSnapshot
import ru.ruscrafting.ranks.storage.ExternalProgressResult
import ru.ruscrafting.ranks.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.ranks.text.RankLocale
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

class RankPassportMenuMockBukkitTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "passport crosses the Paper inventory boundary from loading state to a nonitalic rendered overview" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                menuHarness(paper).use { harness ->
                    harness.menu.open(harness.player)

                    val loading = harness.player.openInventory.topInventory
                    loading.size shouldBe 45
                    loading.getItem(harness.slot(ArcRanksMenuLayouts.PASSPORT, "promotion"))?.type shouldBe Material.CLOCK

                    paper.performTicks(1)

                    val view = harness.player.openInventory
                    val inventory = view.topInventory
                    plain(view.title()) shouldBe plain(harness.locale.render("gui.title", harness.player))
                    val profile = requireNotNull(inventory.getItem(harness.slot(ArcRanksMenuLayouts.PASSPORT, "profile")))
                    profile.type shouldBe Material.PLAYER_HEAD
                    (profile.itemMeta as SkullMeta).owningPlayer?.name shouldBe harness.player.name
                    inventory.getItem(harness.slot(ArcRanksMenuLayouts.PASSPORT, "contracts"))?.type shouldBe Material.WRITABLE_BOOK
                    val paths = requireNotNull(inventory.getItem(harness.slot(ArcRanksMenuLayouts.PASSPORT, "paths")))
                    paths.type shouldBe Material.COMPASS
                    val pathsLore = requireNotNull(paths.itemMeta.lore()).map(::plain)
                    pathsLore shouldContain "Чтобы открыть ранг «Крестьянин», выполните цели 2 путей из 6."
                    pathsLore shouldContain "Сейчас выполнено: 0/2."
                    inventory.getItem(harness.slot(ArcRanksMenuLayouts.PASSPORT, "perks"))?.type shouldBe Material.ENCHANTED_BOOK
                    inventory.getItem(harness.slot(ArcRanksMenuLayouts.PASSPORT, "weekly-kit"))?.type shouldBe Material.CHEST
                    inventory.getItem(harness.slot(ArcRanksMenuLayouts.PASSPORT, "promotion"))?.type shouldBe Material.NETHER_STAR
                    val artisanLore = requireNotNull(
                        inventory.getItem(harness.region(ArcRanksMenuLayouts.PASSPORT, "ranks")[3])?.itemMeta?.lore(),
                    ).map(::plain)
                    artisanLore.count { it == "Основные лимиты" } shouldBe 1
                    artisanLore.count { it == "Особые возможности" } shouldBe 1
                    artisanLore.count { it == "Команды и награды" } shouldBe 1
                    (artisanLore.indexOf("Основные лимиты") < artisanLore.indexOf("Особые возможности")) shouldBe true
                    (artisanLore.indexOf("Особые возможности") < artisanLore.indexOf("Команды и награды")) shouldBe true
                    inventory.contents.filterNotNull().filter { it.type != Material.AIR }.forEach(::assertNonitalicSurface)
                    verify(exactly = 1) { harness.players.load(harness.player.uniqueId) }
                }
            }
        }
    }

    "passport owns only its top inventory interactions and supports paths navigation with a real back item" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                menuHarness(paper).use { harness ->
                    harness.menu.open(harness.player)
                    paper.performTicks(1)

                    click(paper, harness.player, 0).isCancelled shouldBe true
                    click(
                        paper,
                        harness.player,
                        harness.player.openInventory.topInventory.size,
                        ClickType.SHIFT_LEFT,
                        InventoryAction.MOVE_TO_OTHER_INVENTORY,
                    ).isCancelled shouldBe true
                    paper.callEvent(
                        InventoryDragEvent(
                            harness.player.openInventory,
                            ItemStack(Material.DIAMOND),
                            ItemStack(Material.AIR),
                            true,
                            mapOf(0 to ItemStack(Material.DIAMOND)),
                        ),
                    ).isCancelled shouldBe true

                    click(paper, harness.player, harness.slot(ArcRanksMenuLayouts.PASSPORT, "contracts")).isCancelled shouldBe true
                    harness.contractOpens shouldBe 1

                    click(paper, harness.player, harness.slot(ArcRanksMenuLayouts.PASSPORT, "paths")).isCancelled shouldBe true
                    val pathsView = harness.player.openInventory
                    plain(pathsView.title()) shouldBe plain(harness.locale.render("gui.paths.title", harness.player))
                    pathsView.topInventory.getItem(harness.slot(ArcRanksMenuLayouts.PATHS, "back"))?.type shouldBe
                        Material.valueOf(harness.settings.gui.back.material)
                    val back = requireNotNull(pathsView.topInventory.getItem(harness.slot(ArcRanksMenuLayouts.PATHS, "back")))
                    back.itemMeta.customModelData shouldBe harness.settings.gui.back.customModelData
                    assertNonitalicSurface(back)
                    click(paper, harness.player, harness.region(ArcRanksMenuLayouts.PATHS, "paths").first()).isCancelled shouldBe true
                    verify(exactly = 0) { harness.players.selectFocus(any(), any()) }

                    click(paper, harness.player, harness.slot(ArcRanksMenuLayouts.PATHS, "back")).isCancelled shouldBe true
                    plain(harness.player.openInventory.title()) shouldBe plain(harness.locale.render("gui.title", harness.player))

                    val unrelated = Bukkit.createInventory(null, 9, Component.text("Unrelated"))
                    requireNotNull(harness.player.openInventory(unrelated))
                    click(paper, harness.player, 0).isCancelled shouldBe false
                    paper.callEvent(
                        InventoryDragEvent(
                            harness.player.openInventory,
                            ItemStack(Material.DIAMOND),
                            ItemStack(Material.AIR),
                            true,
                            mapOf(0 to ItemStack(Material.DIAMOND)),
                        ),
                    ).isCancelled shouldBe false
                }
            }
        }
    }

    "a delayed snapshot cannot repaint an inventory the player has already left" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val delayed = CompletableFuture<RankPlayerSnapshot>()
                menuHarness(paper, delayed).use { harness ->
                    harness.menu.open(harness.player)
                    val unrelated = Bukkit.createInventory(null, 9, Component.text("Safe destination"))
                    requireNotNull(harness.player.openInventory(unrelated))

                    delayed.complete(harness.snapshot)
                    paper.performTicks(1)

                    harness.player.openInventory.topInventory shouldBe unrelated
                    plain(harness.player.openInventory.title()) shouldBe "Safe destination"
                }
            }
        }
    }

    "passport promotion click cannot bypass arcranks rankup permission" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                menuHarness(paper).use { harness ->
                    harness.player.setOp(false)
                    harness.player.addAttachment(harness.plugin, "arcranks.use", true)
                    harness.player.addAttachment(harness.plugin, "arcranks.rankup", false)
                    harness.menu.open(harness.player)
                    paper.performTicks(1)

                    click(paper, harness.player, harness.slot(ArcRanksMenuLayouts.PASSPORT, "promotion")).isCancelled shouldBe true

                    verify(exactly = 0) { harness.promotions.promote(any()) }
                    plain(requireNotNull(harness.nextComponentMessage())) shouldBe
                        plain(harness.locale.render("commands.no-permission", harness.player))
                }
            }
        }
    }

    "blocked promotion click changes only the clicked card without chat or a full refresh" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                menuHarness(paper, promotionMode = PromotionMode.ACTIVE).use { harness ->
                    harness.player.addAttachment(harness.plugin, "arcranks.rankup", true)
                    harness.menu.open(harness.player)
                    paper.performTicks(1)

                    click(paper, harness.player, harness.slot(ArcRanksMenuLayouts.PASSPORT, "promotion")).isCancelled shouldBe true

                    verify(exactly = 0) { harness.promotions.promote(any()) }
                    harness.nextComponentMessage() shouldBe null
                    plain(
                        requireNotNull(
                            harness.player.openInventory.topInventory
                                .getItem(harness.slot(ArcRanksMenuLayouts.PASSPORT, "promotion"))
                                ?.itemMeta
                                ?.displayName(),
                        ),
                    ) shouldBe plain(harness.locale.render("gui.promotion.feedback.blocked.name", harness.player))
                    verify(exactly = 1) { harness.players.load(harness.player.uniqueId) }
                }
            }
        }
    }

    "focus selection serializes rapid path clicks while the first request is pending" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val selected = CompletableFuture<RankPlayerSnapshot>()
                menuHarness(paper, focusFuture = selected).use { harness ->
                    harness.menu.open(harness.player)
                    paper.performTicks(1)
                    click(paper, harness.player, harness.slot(ArcRanksMenuLayouts.PASSPORT, "paths"))

                    click(paper, harness.player, harness.region(ArcRanksMenuLayouts.PATHS, "paths")[1]).isCancelled shouldBe true
                    click(paper, harness.player, harness.region(ArcRanksMenuLayouts.PATHS, "paths")[2]).isCancelled shouldBe true
                    verify(exactly = 1) {
                        harness.players.selectFocus(harness.player.uniqueId, SpecializationPath.INDUSTRY)
                    }
                    verify(exactly = 0) {
                        harness.players.selectFocus(harness.player.uniqueId, SpecializationPath.TRADE)
                    }

                    selected.complete(
                        harness.snapshot.copy(profile = harness.snapshot.profile.selectFocus(SpecializationPath.INDUSTRY)),
                    )
                    paper.performTicks(1)

                    plain(requireNotNull(harness.nextComponentMessage())) shouldBe plain(
                        harness.locale.render(
                            "commands.focus.selected",
                            harness.player,
                            mapOf("path" to harness.locale.render("paths.industry.name", harness.player)),
                        ),
                    )
                }
            }
        }
    }

    "passport admin controls are permission-gated and the progress action cannot double-submit" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                menuHarness(paper).use { harness ->
                    harness.player.isOp = false
                    harness.menu.open(harness.player)
                    paper.performTicks(1)

                    harness.player.openInventory.topInventory
                        .getItem(harness.slot(ArcRanksMenuLayouts.PASSPORT, "admin-advance"))?.type shouldBe
                        Material.GRAY_STAINED_GLASS_PANE
                    harness.player.openInventory.topInventory
                        .getItem(harness.slot(ArcRanksMenuLayouts.PASSPORT, "admin-analytics"))?.type shouldBe
                        Material.GRAY_STAINED_GLASS_PANE

                    harness.player.addAttachment(harness.plugin, RankPassportMenu.ADMIN_GRANT_PERMISSION, true)
                    harness.player.addAttachment(harness.plugin, RankPassportMenu.ADMIN_ANALYTICS_PERMISSION, true)
                    harness.menu.open(harness.player)
                    paper.performTicks(1)

                    val advance = requireNotNull(
                        harness.player.openInventory.topInventory
                            .getItem(harness.slot(ArcRanksMenuLayouts.PASSPORT, "admin-advance")),
                    )
                    plain(requireNotNull(advance.itemMeta.displayName())) shouldBe "Админ: выполнить следующий шаг"
                    val analytics = requireNotNull(
                        harness.player.openInventory.topInventory
                            .getItem(harness.slot(ArcRanksMenuLayouts.PASSPORT, "admin-analytics")),
                    )
                    plain(requireNotNull(analytics.itemMeta.displayName())) shouldBe "Админ: аналитика рангов"

                    val advanceSlot = harness.slot(ArcRanksMenuLayouts.PASSPORT, "admin-advance")
                    click(paper, harness.player, advanceSlot).isCancelled shouldBe true
                    click(paper, harness.player, advanceSlot).isCancelled shouldBe true
                    verify(exactly = 1) {
                        harness.progressApi.record(
                            "admin-gui",
                            any(),
                            harness.player.uniqueId,
                            ru.ruscrafting.ranks.domain.ProgressMetric.ACTIVE_MINUTES,
                            any(),
                        )
                    }

                    paper.performTicks(2)
                    click(paper, harness.player, harness.slot(ArcRanksMenuLayouts.PASSPORT, "admin-analytics"))
                    harness.analyticsOpens shouldBe 1
                }
            }
        }
    }
})

private class RankPassportMenuHarness(
    val plugin: Plugin,
    val player: Player,
    val nextComponentMessage: () -> Component?,
    val settings: ArcRanksSettings,
    val locale: RankLocale,
    val players: RankPlayerService,
    val promotions: PromotionService,
    val progressApi: RankProgressApi,
    val snapshot: RankPlayerSnapshot,
    val tasks: LifecycleTaskScope,
    val layouts: ArcRanksMenuLayouts,
    val menu: RankPassportMenu,
) : AutoCloseable {
    var contractOpens: Int = 0
    var analyticsOpens: Int = 0

    fun slot(menu: ru.arc.menu.MenuId, element: String): Int = layouts.slot(menu, element)

    fun region(menu: ru.arc.menu.MenuId, region: String): List<Int> = layouts.region(menu, region)

    override fun close() {
        player.closeInventory()
        tasks.close()
    }
}

private fun menuHarness(
    paper: MockBukkitTestRuntime,
    snapshotFuture: CompletableFuture<RankPlayerSnapshot>? = null,
    focusFuture: CompletableFuture<RankPlayerSnapshot>? = null,
    promotionMode: PromotionMode? = null,
): RankPassportMenuHarness {
    val plugin = paper.createSimplePlugin("ArcRanksMenuTest")
    val player = paper.addPlayer("PassportTester")
    val root = Files.createTempDirectory("arcranks-menu-platform")
    if (promotionMode != null) {
        Config(root, "config.yml").apply {
            setString("promotion-mode", promotionMode.name)
            saveStrict()
        }
    }
    val settings = ArcRanksSettings.load(root) { "secret" }
    val loaded = RankCatalogLoader(ConfigManager.of(root, "ranks.yml")).loadWithMastery()
    val catalog = loaded.catalog
    val locale = RankLocale(root, { settings.defaultLocale }, { settings.useClientLocale })
    val profile = PlayerProgressProfile(ProgressSnapshot.EMPTY, SpecializationPath.FARMING)
    val rankId = catalog.ranks.first().id
    val snapshot = RankPlayerSnapshot(
        rankState = RankState.Exact(rankId),
        profile = profile,
        evaluation = RankEvaluator(catalog).evaluate(rankId, profile.progress, PathAvailability.allAvailable()),
        mastery = SpecializationPath.entries.associateWith { MasteryLevel.NONE },
        availability = PathAvailability.allAvailable(),
        activePerks = emptySet(),
    )
    val players = mockk<RankPlayerService>()
    every { players.load(any()) } returns (snapshotFuture ?: CompletableFuture.completedFuture(snapshot))
    if (focusFuture != null) every { players.selectFocus(any(), any()) } returns focusFuture
    val promotions = mockk<PromotionService>(relaxed = true)
    val progressApi = mockk<RankProgressApi>()
    every { progressApi.record(any(), any(), any(), any(), any()) } returns
        CompletableFuture.completedFuture(ExternalProgressResult.APPLIED)
    val adminProgress = AdminProgressService(progressApi) { "admin-menu:test" }
    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    lateinit var harness: RankPassportMenuHarness
    val layouts = ArcRanksMenuLayouts(root)
    val menu = RankPassportMenu(
        settings = { settings },
        catalog = { catalog },
        locale = { locale },
        players = players,
        promotions = promotions,
        adminProgress = adminProgress,
        tasks = tasks,
        openContracts = { harness.contractOpens++ },
        openAnalytics = { harness.analyticsOpens++ },
        layouts = layouts,
    )
    harness = RankPassportMenuHarness(
        plugin,
        player,
        player::nextComponentMessage,
        settings,
        locale,
        players,
        promotions,
        progressApi,
        snapshot,
        tasks,
        layouts,
        menu,
    )
    paper.server.pluginManager.registerEvents(menu, plugin)
    return harness
}

private fun click(
    paper: MockBukkitTestRuntime,
    player: Player,
    rawSlot: Int,
    clickType: ClickType = ClickType.LEFT,
    action: InventoryAction = InventoryAction.PICKUP_ALL,
): InventoryClickEvent =
    paper.callEvent(
        InventoryClickEvent(
            player.openInventory,
            InventoryType.SlotType.CONTAINER,
            rawSlot,
            clickType,
            action,
        ),
    )

private fun assertNonitalicSurface(item: ItemStack) {
    val meta = item.itemMeta
    requireNotNull(meta.displayName()).decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
    meta.lore().orEmpty().forEach { line ->
        line.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
    }
}

private fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)
