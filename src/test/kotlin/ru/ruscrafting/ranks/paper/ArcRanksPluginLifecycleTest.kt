package ru.ruscrafting.ranks.paper

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import org.bukkit.Bukkit
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import ru.ruscrafting.ranks.config.ArcRanksConfigLoader
import ru.ruscrafting.ranks.storage.MySqlProgressRepository
import ru.arc.sql.SqlRuntime
import ru.arc.sql.SqlExecutor
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class ArcRanksPluginLifecycleTest : FreeSpec({
    "reenable waits for the exact accepted worker even when an intermediate enable is disabled" {
        MockBukkitTestRuntime.open().use { paper ->
            listOf("LuckPerms", "ARC").forEach(paper::createSimplePlugin)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val preparations = AtomicInteger()
            mockkConstructor(ArcRanksConfigLoader::class)
            every { anyConstructed<ArcRanksConfigLoader>().load(1) } answers {
                Bukkit.isPrimaryThread() shouldBe false
                if (preparations.incrementAndGet() == 1) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                // Stop before SQL acquisition: this case exercises the real plugin lifecycle only.
                throw IllegalStateException("isolated lifecycle preparation")
            }
            var plugin: ArcRanksPlugin? = null
            try {
                plugin = paper.loadPlugin<ArcRanksPlugin>()
                awaitRanksLifecycle(paper) { entered.count == 0L }
                paper.server.pluginManager.disablePlugin(plugin)
                plugin.drained().isDone shouldBe false
                plugin.onDisable()
                plugin.drained().isDone shouldBe false
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(3)
                preparations.get() shouldBe 1
                paper.server.pluginManager.disablePlugin(plugin)
                plugin.drained().isDone shouldBe false
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(3)
                preparations.get() shouldBe 1
                release.countDown()
                awaitRanksLifecycle(paper) { preparations.get() == 2 }
                awaitRanksLifecycle(paper) { !plugin.isEnabled }
                preparations.get() shouldBe 2
            } finally {
                release.countDown()
                plugin?.let { active ->
                    paper.server.pluginManager.disablePlugin(active)
                    active.drained().get(5, TimeUnit.SECONDS)
                }
                unmockkConstructor(ArcRanksConfigLoader::class)
            }
        }
    }

    "the actual accepted SQL drain retains its lease and a failed drain refuses a replacement" {
        val configuration = ArcRanksConfigLoader(Files.createTempDirectory("rank-lifecycle-config")) { "disposable-test" }.load(1)
        MockBukkitTestRuntime.open().use { paper ->
            listOf("LuckPerms", "ARC").forEach(paper::createSimplePlugin)
            val prepared = CountDownLatch(1)
            val initialization = CompletableFuture<Unit>()
            val closeEntered = CountDownLatch(1)
            val acceptedSqlDrain = CompletableFuture<Unit>()
            val runtimeClosed = AtomicBoolean()
            val acquisitions = AtomicInteger()
            val executor = mockk<SqlExecutor> {
                every { closeAsync() } answers {
                    Bukkit.isPrimaryThread() shouldBe false
                    closeEntered.countDown()
                    acceptedSqlDrain
                }
            }
            val sql = mockk<SqlRuntime> {
                every { this@mockk.executor } returns executor
                every { close() } answers {
                    Bukkit.isPrimaryThread() shouldBe false
                    runtimeClosed.set(true)
                    Unit
                }
            }
            mockkConstructor(ArcRanksConfigLoader::class, MySqlProgressRepository::class)
            mockkObject(SqlRuntime.Companion)
            every { anyConstructed<ArcRanksConfigLoader>().load(1) } returns configuration
            every { SqlRuntime.create(any(), any()) } answers { acquisitions.incrementAndGet(); sql }
            every { anyConstructed<MySqlProgressRepository>().initialize() } answers { prepared.countDown(); initialization }
            var plugin: ArcRanksPlugin? = null
            try {
                plugin = paper.loadPlugin<ArcRanksPlugin>()
                awaitRanksLifecycle(paper) { prepared.count == 0L }
                paper.server.pluginManager.disablePlugin(plugin)
                initialization.complete(Unit)
                closeEntered.await(5, TimeUnit.SECONDS) shouldBe true
                plugin.drained().isDone shouldBe false
                runtimeClosed.get() shouldBe false
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(3)
                acquisitions.get() shouldBe 1
                acceptedSqlDrain.completeExceptionally(IllegalStateException("accepted SQL drain failed"))
                awaitRanksLifecycle(paper) { !plugin.isEnabled }
                runtimeClosed.get() shouldBe true
                plugin.drained().isCompletedExceptionally shouldBe true
                acquisitions.get() shouldBe 1
            } finally {
                initialization.complete(Unit)
                acceptedSqlDrain.complete(Unit)
                plugin?.let { active ->
                    paper.server.pluginManager.disablePlugin(active)
                    runCatching { active.drained().get(5, TimeUnit.SECONDS) }
                }
                unmockkObject(SqlRuntime.Companion)
                unmockkConstructor(ArcRanksConfigLoader::class, MySqlProgressRepository::class)
            }
        }
    }
})

private fun awaitRanksLifecycle(paper: MockBukkitTestRuntime, ready: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!ready() && System.nanoTime() < deadline) { paper.performTicks(1); Thread.sleep(1) }
    ready() shouldBe true
}
