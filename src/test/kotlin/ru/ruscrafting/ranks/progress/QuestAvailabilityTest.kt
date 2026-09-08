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
