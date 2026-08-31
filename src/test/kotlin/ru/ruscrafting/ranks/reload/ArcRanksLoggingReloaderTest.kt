package ru.ruscrafting.ranks.reload

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files

class ArcRanksLoggingReloaderTest : StringSpec({
    "unchanged logging bytes are a no-op" {
        val path = Files.createTempFile("arcranks-logging", ".yml")
        Files.writeString(path, "level: INFO\n")
        var reloads = 0
        val reloader = ArcRanksLoggingReloader(path) { reloads++ }

        reloader.reloadIfChanged() shouldBe ArcRanksLoggingReloadResult.Unchanged
        reloads shouldBe 0
    }

    "changed logging bytes reload exactly once and become the active fingerprint" {
        val path = Files.createTempFile("arcranks-logging", ".yml")
        Files.writeString(path, "level: INFO\n")
        var reloads = 0
        val reloader = ArcRanksLoggingReloader(path) { reloads++ }
        Files.writeString(path, "level: DEBUG\n")

        reloader.reloadIfChanged() shouldBe ArcRanksLoggingReloadResult.Applied
        reloader.reloadIfChanged() shouldBe ArcRanksLoggingReloadResult.Unchanged
        reloads shouldBe 1
    }

    "failed logging reload is rejected and remains retryable" {
        val path = Files.createTempFile("arcranks-logging", ".yml")
        Files.writeString(path, "level: INFO\n")
        var reloads = 0
        val reloader = ArcRanksLoggingReloader(path) {
            reloads++
            error("invalid logging syntax")
        }
        Files.writeString(path, "level: [\n")

        reloader.reloadIfChanged().shouldBeInstanceOf<ArcRanksLoggingReloadResult.Invalid>()
        reloader.reloadIfChanged().shouldBeInstanceOf<ArcRanksLoggingReloadResult.Invalid>()
        reloads shouldBe 2
    }

    "file mutation during reload is rejected instead of advancing the fingerprint" {
        val path = Files.createTempFile("arcranks-logging", ".yml")
        Files.writeString(path, "level: INFO\n")
        var rewrites = 0
        val reloader = ArcRanksLoggingReloader(path) {
            rewrites++
            Files.writeString(path, "level: TRACE\n")
        }
        Files.writeString(path, "level: DEBUG\n")

        val result = reloader.reloadIfChanged().shouldBeInstanceOf<ArcRanksLoggingReloadResult.Invalid>()
        result.reason shouldBe "logging.yml changed while it was being reloaded; retry reload"
        rewrites shouldBe 1
    }
})
