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
import java.util.concurrent.atomic.AtomicBoolean

class ArcRanksPluginLifecycleTest : FreeSpec({
    "maintenance configuration finishes before enable returns" {
        MockBukkitTestRuntime.open().use { paper ->
            listOf("LuckPerms", "ARC").forEach(paper::createSimplePlugin)
            var preparations = 0
            mockkConstructor(ArcRanksConfigLoader::class)
            every { anyConstructed<ArcRanksConfigLoader>().load(1) } answers {
                Bukkit.isPrimaryThread() shouldBe false
                preparations++
                throw IllegalStateException("isolated configuration failure")
            }
            try {
                val plugin = paper.loadPlugin<ArcRanksPlugin>()
                preparations shouldBe 1
                plugin.isEnabled shouldBe false
                plugin.drained().isDone shouldBe true
                plugin.onDisable()
                preparations shouldBe 1
            } finally { unmockkConstructor(ArcRanksConfigLoader::class) }
        }
    }

    "maintenance SQL drain finishes before resource release and before enable failure returns" {
        val configuration = ArcRanksConfigLoader(Files.createTempDirectory("rank-lifecycle-config")) { "disposable-test" }.load(1)
        MockBukkitTestRuntime.open().use { paper ->
            listOf("LuckPerms", "ARC").forEach(paper::createSimplePlugin)
            val closeEntered = CountDownLatch(1)
            val acceptedSqlDrain = CompletableFuture<Unit>()
            val runtimeClosed = AtomicBoolean()
            val returned = AtomicBoolean()
            val observedBeforeReturn = AtomicBoolean()
            val executor = mockk<SqlExecutor> {
                every { closeAsync() } answers { Bukkit.isPrimaryThread() shouldBe false; closeEntered.countDown(); acceptedSqlDrain }
            }
            val sql = mockk<SqlRuntime> {
                every { this@mockk.executor } returns executor
                every { close() } answers { Bukkit.isPrimaryThread() shouldBe false; runtimeClosed.set(true); Unit }
            }
            mockkConstructor(ArcRanksConfigLoader::class, MySqlProgressRepository::class)
            mockkObject(SqlRuntime.Companion)
            every { anyConstructed<ArcRanksConfigLoader>().load(1) } returns configuration
            every { SqlRuntime.create(any(), any()) } returns sql
            every { anyConstructed<MySqlProgressRepository>().initialize() } returns CompletableFuture.failedFuture(IllegalStateException("isolated schema failure"))
            val release = Thread {
                try {
                    if (closeEntered.await(5, TimeUnit.SECONDS)) observedBeforeReturn.set(!returned.get() && !runtimeClosed.get())
                } finally { acceptedSqlDrain.completeExceptionally(IllegalStateException("isolated accepted drain failure")) }
            }.apply { start() }
            try {
                val plugin = paper.loadPlugin<ArcRanksPlugin>()
                returned.set(true)
                observedBeforeReturn.get() shouldBe true
                runtimeClosed.get() shouldBe true
                plugin.isEnabled shouldBe false
                plugin.drained().isCompletedExceptionally shouldBe true
            } finally {
                acceptedSqlDrain.complete(Unit); release.join(5_000)
                unmockkObject(SqlRuntime.Companion)
                unmockkConstructor(ArcRanksConfigLoader::class, MySqlProgressRepository::class)
            }
        }
    }
})
