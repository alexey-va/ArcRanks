package ru.ruscrafting.ranks.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import ru.arc.config.ConfigManager
import java.nio.file.Files

class ArcRanksSettingsTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "bundled settings load a shadow-mode, secret-free runtime profile" {
        val root = Files.createTempDirectory("arcranks-settings")
        val settings = ArcRanksSettings.load(root) { name ->
            if (name == "ARC_RANKS_MYSQL_PASSWORD") "test-password" else null
        }

        settings.serverId shouldBe "survival"
        settings.promotionMode shouldBe PromotionMode.SHADOW
        settings.sql.password shouldBe "test-password"
        settings.gui.background.material shouldBe "GRAY_STAINED_GLASS_PANE"
        settings.gui.back.material shouldBe "BLUE_STAINED_GLASS_PANE"
        settings.gui.rankLocked.material shouldBe "GRAY_STAINED_GLASS_PANE"
        settings.gui.contracts.material shouldBe "WRITABLE_BOOK"
        settings.gui.perks.material shouldBe "ENCHANTED_BOOK"
        settings.gui.analytics.material shouldBe "SPYGLASS"
        settings.analytics.enabled shouldBe true
        settings.analytics.flushTicks shouldBe 1_200
        Files.readString(root.resolve("config.yml")) shouldNotContain "test-password"
    }

    "missing database secret fails closed before the plugin starts" {
        val root = Files.createTempDirectory("arcranks-settings-missing-secret")

        runCatching { ArcRanksSettings.load(root) { null } }.exceptionOrNull()?.message shouldBe
            "Environment variable ARC_RANKS_MYSQL_PASSWORD or plugin-local .env is required"
    }

    "plugin-local dot-env supplies the database secret without process environment wiring" {
        val root = Files.createTempDirectory("arcranks-settings-dot-env")
        Files.writeString(root.resolve(".env"), "shared-classic-password\n")

        val settings = ArcRanksSettings.load(root) { null }

        settings.sql.password shouldBe "shared-classic-password"
    }

    "process environment takes precedence over plugin-local dot-env" {
        val root = Files.createTempDirectory("arcranks-settings-env-priority")
        Files.writeString(root.resolve(".env"), "file-password\n")

        val settings = ArcRanksSettings.load(root) { "environment-password" }

        settings.sql.password shouldBe "environment-password"
    }
})
