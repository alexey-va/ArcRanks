package ru.ruscrafting.ranks.reload

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.config.ConfigManager
import ru.ruscrafting.ranks.config.ArcRanksConfigFingerprints
import ru.ruscrafting.ranks.config.ArcRanksConfigLoader
import ru.ruscrafting.ranks.config.ArcRanksConfigSnapshot
import ru.ruscrafting.ranks.config.ArcRanksConfigStore
import ru.ruscrafting.ranks.config.ArcRanksLiveArea
import ru.ruscrafting.ranks.config.PromotionMode
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ArcRanksReloadCoordinatorTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "byte-identical reload is a no-op without restarting tasks or publishing a wrapper" {
        val active = baseline()
        val candidate = active.copy(generation = 2)
        val loader = loaderReturning(candidate)
        val store = ArcRanksConfigStore(active)
        var restartCalls = 0
        val installed = mutableListOf<ArcRanksConfigSnapshot>()
        var afterCommitCalls = 0
        val coordinator = ArcRanksReloadCoordinator(
            loader = loader,
            store = store,
            restartRecurringTasks = { restartCalls++ },
            installRecurringTasks = installed::add,
            afterCommit = { _, _ ->
                afterCommitCalls++
                emptyList()
            },
        )

        coordinator.reload() shouldBe ArcRanksReloadResult.NoChanges(generation = 1)

        (store.current() === active) shouldBe true
        restartCalls shouldBe 0
        installed shouldBe emptyList()
        afterCommitCalls shouldBe 0
        verify(exactly = 1) { loader.load(2) }
    }

    "invalid candidate preserves the active generation and never touches recurring tasks" {
        val active = baseline()
        val loader = mockk<ArcRanksConfigLoader>()
        every { loader.load(2) } throws IllegalArgumentException("invalid locale\nmissing commands.help")
        val store = ArcRanksConfigStore(active)
        var restartCalls = 0
        var installCalls = 0
        val coordinator = ArcRanksReloadCoordinator(
            loader = loader,
            store = store,
            restartRecurringTasks = { restartCalls++ },
            installRecurringTasks = { installCalls++ },
        )

        coordinator.reload() shouldBe ArcRanksReloadResult.Invalid("invalid locale missing commands.help")

        (store.current() === active) shouldBe true
        store.current().generation shouldBe 1
        restartCalls shouldBe 0
        installCalls shouldBe 0
    }

    "restart-only candidate reports exact paths without touching tasks or store" {
        val active = baseline()
        val candidate = active.copy(
            generation = 2,
            settings = active.settings.copy(serverId = "survival-replacement"),
            fingerprints = active.changedFingerprint("config.yml"),
        )
        val loader = loaderReturning(candidate)
        val store = ArcRanksConfigStore(active)
        var restartCalls = 0
        var installCalls = 0
        var afterCommitCalls = 0
        val coordinator = ArcRanksReloadCoordinator(
            loader = loader,
            store = store,
            restartRecurringTasks = { restartCalls++ },
            installRecurringTasks = { installCalls++ },
            afterCommit = { _, _ ->
                afterCommitCalls++
                emptyList()
            },
        )

        coordinator.reload() shouldBe ArcRanksReloadResult.RestartRequired(setOf("server-id"))

        (store.current() === active) shouldBe true
        restartCalls shouldBe 0
        installCalls shouldBe 0
        afterCommitCalls shouldBe 0
    }

    "live candidate is published only after its recurring tasks install successfully" {
        val active = baseline()
        val candidate = active.copy(
            generation = 2,
            settings = active.settings.copy(promotionMode = PromotionMode.ACTIVE),
            fingerprints = active.changedFingerprint("config.yml"),
        )
        val store = ArcRanksConfigStore(active)
        val events = mutableListOf<String>()
        val coordinator = ArcRanksReloadCoordinator(
            loader = loaderReturning(candidate),
            store = store,
            restartRecurringTasks = {
                events += "restart@${store.current().generation}"
            },
            installRecurringTasks = { snapshot ->
                events += "install-${snapshot.generation}@${store.current().generation}"
                (store.current() === active) shouldBe true
            },
            afterCommit = { snapshot, diff ->
                events += "commit-${snapshot.generation}@${store.current().generation}"
                (store.current() === candidate) shouldBe true
                diff.liveAreas shouldBe setOf(ArcRanksLiveArea.PROMOTION)
                listOf("warmup warning")
            },
        )

        val result = coordinator.reload().shouldBeInstanceOf<ArcRanksReloadResult.Applied>()

        result.generation shouldBe 2
        result.liveAreas shouldBe setOf(ArcRanksLiveArea.PROMOTION)
        result.warnings shouldBe listOf("warmup warning")
        (store.current() === candidate) shouldBe true
        events.shouldContainExactly(
            "restart@1",
            "install-2@1",
            "commit-2@2",
        )
    }

    "candidate task installation failure restores old tasks and leaves store untouched" {
        val active = baseline()
        val candidate = active.copy(
            generation = 2,
            settings = active.settings.copy(promotionMode = PromotionMode.ACTIVE),
            fingerprints = active.changedFingerprint("config.yml"),
        )
        val store = ArcRanksConfigStore(active)
        val events = mutableListOf<String>()
        var afterCommitCalls = 0
        val coordinator = ArcRanksReloadCoordinator(
            loader = loaderReturning(candidate),
            store = store,
            restartRecurringTasks = { events += "restart" },
            installRecurringTasks = { snapshot ->
                events += "install-${snapshot.generation}"
                if (snapshot === candidate) error("candidate scheduler rejected period")
            },
            afterCommit = { _, _ ->
                afterCommitCalls++
                emptyList()
            },
        )

        val result = coordinator.reload().shouldBeInstanceOf<ArcRanksReloadResult.RolledBack>()

        result.reason shouldBe "candidate scheduler rejected period"
        result.rollbackFailure shouldBe null
        (store.current() === active) shouldBe true
        store.current().generation shouldBe 1
        afterCommitCalls shouldBe 0
        events.shouldContainExactly(
            "restart",
            "install-2",
            "restart",
            "install-1",
        )
    }

    "overlapping reload is rejected as Busy while the first parse is still in progress" {
        val active = baseline()
        val candidate = active.copy(generation = 2)
        val enteredLoader = CountDownLatch(1)
        val releaseLoader = CountDownLatch(1)
        val loader = mockk<ArcRanksConfigLoader>()
        every { loader.load(2) } answers {
            enteredLoader.countDown()
            check(releaseLoader.await(5, TimeUnit.SECONDS)) { "test did not release the loader" }
            candidate
        }
        val store = ArcRanksConfigStore(active)
        var restartCalls = 0
        val coordinator = ArcRanksReloadCoordinator(
            loader = loader,
            store = store,
            restartRecurringTasks = { restartCalls++ },
            installRecurringTasks = {},
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val firstReload = executor.submit<ArcRanksReloadResult> { coordinator.reload() }
            check(enteredLoader.await(5, TimeUnit.SECONDS)) { "first reload did not enter loader" }

            coordinator.reload() shouldBe ArcRanksReloadResult.Busy

            releaseLoader.countDown()
            firstReload.get(5, TimeUnit.SECONDS) shouldBe ArcRanksReloadResult.NoChanges(1)
            (store.current() === active) shouldBe true
            restartCalls shouldBe 0
            verify(exactly = 1) { loader.load(2) }
        } finally {
            releaseLoader.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
})

private fun baseline(): ArcRanksConfigSnapshot =
    ArcRanksConfigLoader(Files.createTempDirectory("arcranks-reload")) { "test-secret" }.load(1)

private fun loaderReturning(snapshot: ArcRanksConfigSnapshot): ArcRanksConfigLoader =
    mockk<ArcRanksConfigLoader>().also { loader ->
        every { loader.load(snapshot.generation) } returns snapshot
    }

private fun ArcRanksConfigSnapshot.changedFingerprint(resource: String): ArcRanksConfigFingerprints {
    val original = fingerprints.files.getValue(resource)
    val replacement = (if (original.first() == '0') '1' else '0') + original.drop(1)
    return ArcRanksConfigFingerprints(fingerprints.files + (resource to replacement))
}
