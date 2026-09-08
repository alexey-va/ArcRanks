package ru.ruscrafting.ranks.progress

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import java.util.UUID

class QuestAvailabilityTest : StringSpec({
    "only assignment checks eligibility and completion polling skips boards without social quests" {
        ru.arc.paper.testing.MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("QuestAssignmentEligibility")
            val player = paper.addPlayer("QuestAssignment")
            val tasks = LifecycleTaskScope(ru.arc.core.BukkitTaskScheduler(plugin))
            val social = mockk<SocialQuestIntegration>()
            val pending = java.util.concurrent.CompletableFuture<Map<String, Boolean>>()
            every { social.status(player.uniqueId) } returns pending
            val repository = mockk<ru.ruscrafting.ranks.quest.MySqlDailyQuestRepository>()
            every { repository.existingBoard(player.uniqueId) } returns java.util.concurrent.CompletableFuture.completedFuture(null)
            val availability = QuestAvailability(plugin, tasks, social)
            availability.install(repository, mockk<ru.ruscrafting.ranks.api.RankQuestApi>())
            try {
                val poll = availability.refresh(player.uniqueId)
                paper.performTicks(2)
                poll.isDone shouldBe true
                io.mockk.verify(exactly = 0) { social.status(any()) }
                io.mockk.verify(exactly = 0) { repository.board(any()) }
                val assignment = availability.forAssignment(player.uniqueId)
                paper.performTicks(2)
                assignment.isDone shouldBe false
                pending.complete(mapOf("discord" to false))
                assignment.join().contains("discord.unlinked") shouldBe true
                assignment.join().contains("telegram.unlinked") shouldBe false
            } finally {
                availability.close()
                tasks.close()
            }
        }
    }

    "shutdown releases queued provider queries before the main-thread SQL flush barrier" {
        val queued = mutableListOf<Runnable>()
        val scheduler = mockk<TaskScheduler>(relaxed = true)
        every { scheduler.runSync(any()) } answers {
            queued += firstArg<Runnable>()
            mockk<ScheduledTask>(relaxed = true)
        }
        val tasks = LifecycleTaskScope(scheduler)
        val plugin = mockk<Plugin>()
        val social = SocialQuestIntegration(plugin, tasks)
        val availability = QuestAvailability(plugin, tasks, social)
        val player = UUID.randomUUID()
        val query = availability.forAssignment(player)
        query.isDone shouldBe false
        availability.close()
        query.join() shouldBe emptySet()
        availability.forAssignment(player).join() shouldBe emptySet()
        availability.refresh(player).join() shouldBe Unit
        // A stale callback must not touch Bukkit, Redis or the uninitialized repository.
        queued.forEach(Runnable::run)
        social.close()
        tasks.close()
    }
})
