package ru.ruscrafting.ranks.command

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.ruscrafting.ranks.admin.AdminProgressService
import ru.ruscrafting.ranks.analytics.AnalyticsService
import ru.ruscrafting.ranks.analytics.TelemetryHealthSnapshot
import ru.ruscrafting.ranks.api.RankProgressApi
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.contract.ContractService
import ru.ruscrafting.ranks.dialog.RankDialogController
import ru.ruscrafting.ranks.domain.RankCatalog
import ru.ruscrafting.ranks.gui.AnalyticsMenu
import ru.ruscrafting.ranks.gui.ContractMenu
import ru.ruscrafting.ranks.gui.RankPassportMenu
import ru.ruscrafting.ranks.promotion.PromotionService
import ru.ruscrafting.ranks.reload.ArcRanksReloadResult
import ru.ruscrafting.ranks.service.RankPlayerService
import ru.ruscrafting.ranks.text.RankLocale

class RankCommandTest : StringSpec({
    "settings command opens quest display settings without adding a quest-menu route" {
        val player = mockk<Player>(relaxed = true)
        every { player.hasPermission("arcranks.use") } returns true
        val opened = mutableListOf<Player>()
        val command = rankCommand(openQuestDisplaySettings = { opened += it })
        val rank = namedCommand("rank")
        command.onCommand(player, rank, "rank", arrayOf("quest-display")) shouldBe true
        opened shouldBe listOf(player)
        command.onCommand(player, rank, "rank", arrayOf("quest-display", "unexpected")) shouldBe true
        opened shouldBe listOf(player)
        command.onTabComplete(player, rank, "rank", arrayOf("quest-dis")) shouldBe emptyList()
    }

    "legacy weekly kit commands report retirement and are absent from all suggestions" {
        val player = mockk<Player>(relaxed = true)
        every { player.hasPermission(any<String>()) } returns true
        val locale = mockk<RankLocale>()
        val server = mockk<Server>()
        val command = rankCommand(locale = locale, server = server)
        val rank = namedCommand("rank")

        listOf(arrayOf("kit"), arrayOf("weekly"), arrayOf("admin", "kit", "reset", "Target")).forEach { args ->
            command.onCommand(player, rank, "rank", args) shouldBe true
        }

        verify(exactly = 3) { locale.chat("commands.weekly-kit.retired", player, any()) }
        verify { server wasNot Called }
        command.onTabComplete(player, rank, "rank", arrayOf("kit")) shouldBe emptyList()
        command.onTabComplete(player, rank, "rank", arrayOf("weekly")) shouldBe emptyList()
        command.onTabComplete(player, rank, "rank", arrayOf("admin", "kit")) shouldBe emptyList()
        command.onTabComplete(player, rank, "rank", arrayOf("admin", "kit", "")) shouldBe emptyList()
    }

    "direct quests command opens daily quests, rejects args and has no completions" {
        val player = mockk<Player>(relaxed = true)
        val opened = mutableListOf<Player>()
        val command = rankCommand(openDailyQuests = { opened += it })
        val quests = namedCommand("quests")

        command.onCommand(player, quests, "quests", emptyArray()) shouldBe true
        opened shouldBe listOf(player)
        command.onCommand(player, quests, "quests", arrayOf("unexpected")) shouldBe true
        opened shouldBe listOf(player)
        command.onTabComplete(player, quests, "quests", emptyArray()) shouldBe emptyList()
        command.onTabComplete(player, quests, "quests", arrayOf("un")) shouldBe emptyList()
    }

    "rank quests remains a compatible daily quest route" {
        val player = mockk<Player>(relaxed = true)
        every { player.hasPermission("arcranks.use") } returns true
        val opened = mutableListOf<Player>()
        val command = rankCommand(openDailyQuests = { opened += it })
        val rank = namedCommand("rank")

        command.onCommand(player, rank, "rank", arrayOf("quests")) shouldBe true
        opened shouldBe listOf(player)
        command.onTabComplete(player, rank, "rank", arrayOf("")) shouldContain "quests"
    }

    "direct perks command opens the same native dialog as rank perks" {
        val player = mockk<Player>(relaxed = true)
        val dialogs = mockk<RankDialogController>(relaxed = true)
        val command = rankCommand(dialogs = dialogs)

        command.onCommand(player, namedCommand("perks"), "perks", emptyArray()) shouldBe true
        command.onCommand(player, namedCommand("rank"), "rank", arrayOf("perks")) shouldBe true

        verify(exactly = 2) { dialogs.beginFlowAndOpenPerks(player) }
        command.onTabComplete(player, namedCommand("perks"), "perks", emptyArray()) shouldBe emptyList()
    }

    "admin completion exposes only permitted actions and actual player slots" {
        val target = mockk<Player> { every { name } returns "Target" }
        val server = mockk<Server> { every { onlinePlayers } returns listOf(target) }
        val player = mockk<Player>(relaxed = true)
        every { player.hasPermission("arcranks.use") } returns true
        val command = rankCommand(server = server)
        val rank = namedCommand("rank")
        command.onTabComplete(player, rank, "rank", arrayOf("ad")) shouldBe emptyList()
        command.onTabComplete(player, rank, "rank", arrayOf("admin", "inspect", "")) shouldBe emptyList()

        every { player.hasPermission("arcranks.admin.grant") } returns true
        command.onTabComplete(player, rank, "rank", arrayOf("admin", "")) shouldBe listOf("grant", "advance")
        command.onTabComplete(player, rank, "rank", arrayOf("AdMiN", "GRANT", "ta")) shouldBe listOf("Target")
        command.onTabComplete(player, rank, "rank", arrayOf("admin", "GRANT", "Target", "active_")) shouldBe listOf("active_minutes")
        listOf(arrayOf("admin", "unknown", ""), arrayOf("admin", "reload", ""), arrayOf("admin", "grant", "Target", "active_minutes", "1", "id", ""), arrayOf("why", "")).forEach {
            command.onTabComplete(player, rank, "rank", it) shouldBe emptyList()
        }
        every { player.hasPermission("arcranks.use") } returns false
        command.onTabComplete(player, rank, "rank", arrayOf("admin", "grant", "")) shouldBe emptyList()
    }
})

private fun namedCommand(name: String): Command = mockk {
    every { this@mockk.name } returns name
}

private fun rankCommand(
    openDailyQuests: (Player) -> Unit = {},
    openQuestDisplaySettings: (Player) -> Unit = {},
    dialogs: RankDialogController = mockk(relaxed = true),
    server: Server = mockk(relaxed = true),
    locale: RankLocale = mockk(),
): RankCommand {
    every { locale.render(any(), any()) } returns Component.empty()
    every { locale.render(any(), any(), any()) } returns Component.empty()
    every { locale.chat(any(), any(), any()) } returns Component.empty()
    return RankCommand(
        server = server,
        settings = { mockk<ArcRanksSettings>(relaxed = true) },
        catalog = { mockk<RankCatalog>(relaxed = true) },
        locale = { locale },
        players = mockk<RankPlayerService>(relaxed = true),
        progressApi = mockk<RankProgressApi>(relaxed = true),
        adminProgress = mockk<AdminProgressService>(relaxed = true),
        promotions = mockk<PromotionService>(relaxed = true),
        menu = mockk<RankPassportMenu>(relaxed = true),
        contractMenu = mockk<ContractMenu>(relaxed = true),
        contracts = mockk<ContractService>(relaxed = true),
        analyticsMenu = mockk<AnalyticsMenu>(relaxed = true),
        analytics = mockk<AnalyticsService>(relaxed = true),
        analyticsHealth = { mockk<TelemetryHealthSnapshot>(relaxed = true) },
        tasks = mockk<LifecycleTaskScope>(relaxed = true),
        reload = { java.util.concurrent.CompletableFuture.completedFuture(ArcRanksReloadResult.NoChanges(0)) },
        dialogs = dialogs,
        openDailyQuests = openDailyQuests,
        openQuestDisplaySettings = openQuestDisplaySettings,
    )
}
