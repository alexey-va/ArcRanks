package ru.ruscrafting.ranks.progress

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Bukkit
import org.bukkit.plugin.ServicePriority
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.api.ArcRedisLease
import ru.arc.paper.api.ArcRedisService
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SocialQuestIntegrationThreadingTest : StringSpec({
    "cold social acquisition and late close never block the owning thread" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("SocialThreading")
            val player = paper.addPlayer("SocialTester")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val lease = mockk<ArcRedisLease>(relaxed = true)
            val provider = mockk<ArcRedisService>()
            every { provider.acquirePrimary(any()) } answers {
                check(!Bukkit.isPrimaryThread())
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                lease
            }
            paper.server.servicesManager.register(ArcRedisService::class.java, provider, plugin, ServicePriority.Normal)
            val worker = BoundedAsyncExecutor("social-acquire-test")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val resources = java.util.concurrent.ConcurrentLinkedDeque<AutoCloseable>()
            val social = SocialQuestIntegration(plugin, tasks, worker, resources::add)
            try {
                val request = social.status(player.uniqueId)
                entered.await(5, TimeUnit.SECONDS) shouldBe true
                request.isDone shouldBe false
                // The acquire remains gated while close must return immediately.
                social.close()
                tasks.close()
                release.countDown()
                worker.closeAsync { resources.forEach(AutoCloseable::close) }.get(5, TimeUnit.SECONDS)
                request.get(5, TimeUnit.SECONDS) shouldBe emptyMap()
                verify(exactly = 1) { lease.close() }
            } finally {
                release.countDown()
                social.close()
                tasks.close()
                worker.close()
            }
        }
    }
})
