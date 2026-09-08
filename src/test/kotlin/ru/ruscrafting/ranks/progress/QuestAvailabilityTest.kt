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
    "menu availability does not wait for the social network request or treat unknown as unlinked" {
        ru.arc.paper.testing.MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("QuestAvailabilityLatency")
            val player = paper.addPlayer("QuestLatency")
            val tasks = LifecycleTaskScope(ru.arc.core.BukkitTaskScheduler(plugin))
            val social = mockk<SocialQuestIntegration>()
            val pending = java.util.concurrent.CompletableFuture<Map<String, Boolean>>()
            every { social.status(player.uniqueId) } returns pending
            val availability = QuestAvailability(plugin, tasks, social)
            try {
                val refresh = availability.refresh(player.uniqueId)
                val menu = availability.available(player.uniqueId)
                paper.performTicks(2)
                refresh.isDone shouldBe false
                menu.isDone shouldBe true
                menu.join().any { it.endsWith(".unlinked") } shouldBe false
                io.mockk.verify(exactly = 1) { social.status(player.uniqueId) }
                pending.complete(mapOf("discord" to false))
                paper.performTicks(2)
                val refreshed = availability.available(player.uniqueId)
                paper.performTicks(2)
                refreshed.join().contains("discord.unlinked") shouldBe true
                refreshed.join().contains("telegram.unlinked") shouldBe false
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
        val query = availability.available(player)
        query.isDone shouldBe false
        availability.close()
        query.join() shouldBe emptySet()
        availability.available(player).join() shouldBe emptySet()
        availability.refresh(player).join() shouldBe Unit
        // A stale callback must not touch Bukkit, Redis or the uninitialized repository.
        queued.forEach(Runnable::run)
        social.close()
        tasks.close()
    }
})
