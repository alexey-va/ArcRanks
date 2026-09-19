package ru.ruscrafting.ranks.command

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
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
import ru.ruscrafting.ranks.gui.PerkMenu
import ru.ruscrafting.ranks.gui.RankPassportMenu
import ru.ruscrafting.ranks.gui.WeeklyKitMenu
import ru.ruscrafting.ranks.kit.WeeklyKitService
import ru.ruscrafting.ranks.promotion.PromotionService
import ru.ruscrafting.ranks.reload.ArcRanksReloadResult
import ru.ruscrafting.ranks.service.RankPlayerService
import ru.ruscrafting.ranks.text.RankLocale

class RankCommandTest : StringSpec({
    "direct quests command opens daily quests, rejects args and has no completions" {
        val player = mockk<Player>(relaxed = true)
        val opened = mutableListOf<Player>()
        val command = rankCommand { opened += it }
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
        val opened = mutableListOf<Player>()
        val command = rankCommand { opened += it }
        val rank = namedCommand("rank")

        command.onCommand(player, rank, "rank", arrayOf("quests")) shouldBe true
        opened shouldBe listOf(player)
        command.onTabComplete(player, rank, "rank", arrayOf("")) shouldContain "quests"
    }
})

private fun namedCommand(name: String): Command = mockk {
    every { this@mockk.name } returns name
}

private fun rankCommand(openDailyQuests: (Player) -> Unit): RankCommand {
    val locale = mockk<RankLocale>()
    every { locale.render(any(), any()) } returns Component.empty()
    every { locale.render(any(), any(), any()) } returns Component.empty()
    return RankCommand(
        server = mockk<Server>(relaxed = true),
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
        perkMenu = mockk<PerkMenu>(relaxed = true),
        weeklyKitMenu = mockk<WeeklyKitMenu>(relaxed = true),
        weeklyKits = mockk<WeeklyKitService>(relaxed = true),
        analyticsMenu = mockk<AnalyticsMenu>(relaxed = true),
        analytics = mockk<AnalyticsService>(relaxed = true),
        analyticsHealth = { mockk<TelemetryHealthSnapshot>(relaxed = true) },
        tasks = mockk<LifecycleTaskScope>(relaxed = true),
        reload = { ArcRanksReloadResult.NoChanges(0) },
        dialogs = mockk<RankDialogController>(relaxed = true),
        openDailyQuests = openDailyQuests,
    )
}
