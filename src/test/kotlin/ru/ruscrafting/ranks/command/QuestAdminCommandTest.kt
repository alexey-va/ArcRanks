package ru.ruscrafting.ranks.command

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Server
import org.bukkit.command.CommandSender
import ru.arc.core.LifecycleTaskScope
import ru.ruscrafting.ranks.quest.MySqlDailyQuestRepository
import ru.ruscrafting.ranks.text.RankLocale
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

class QuestAdminCommandTest : StringSpec({
    "unprivileged commands and completion never load or mutate player state" {
        val server = mockk<Server>()
        val locale = mockk<RankLocale>()
        every { locale.render(any(), any(), any()) } returns Component.empty()
        val sender = mockk<CommandSender>(relaxed = true)
        every { sender.hasPermission(QuestAdminCommand.PERMISSION) } returns false
        val repository = mockk<MySqlDailyQuestRepository>()
        val command = QuestAdminCommand(server, { locale }, mockk<LifecycleTaskScope>(), repository,
            { error("must not flush") }, { error("must not refresh") }, Logger.getAnonymousLogger())
        listOf("inspect", "assign", "replace", "reset").forEach { command.execute(sender, listOf(it, "Target", "all")) }
        command.complete(sender, listOf("")) shouldBe emptyList()
        verify(exactly = 0) { server.getPlayerExact(any()) }
        verify(exactly = 0) { repository.board(any()) }
        verify(exactly = 0) { repository.adminResetProgress(any(), any(), any()) }
    }

    "unknown and malformed administrative actions never reach storage" {
        val server = mockk<Server>()
        val locale = mockk<RankLocale>()
        every { locale.render(any(), any(), any()) } returns Component.empty()
        val sender = mockk<CommandSender>(relaxed = true)
        every { sender.hasPermission(QuestAdminCommand.PERMISSION) } returns true
        val command = QuestAdminCommand(server, { locale }, mockk(), mockk(),
            { CompletableFuture.completedFuture(Unit) }, {}, Logger.getAnonymousLogger())
        listOf(emptyList(), listOf("event", "Target", "vote.confirmed", "1", "id"),
            listOf("reset", "Target"), listOf("assign", "Target", "extra")).forEach { command.execute(sender, it) }
        verify(exactly = 0) { server.getPlayerExact(any()) }
        QuestAdminCommand.validArguments("reset", listOf("reset", "Target", "all")) shouldBe true
    }
})
