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
        Files.readString(root.resolve("config.yml")) shouldNotContain "test-password"
    }

    "missing database secret fails closed before the plugin starts" {
        val root = Files.createTempDirectory("arcranks-settings-missing-secret")

        runCatching { ArcRanksSettings.load(root) { null } }.exceptionOrNull()?.message shouldBe
            "Environment variable ARC_RANKS_MYSQL_PASSWORD is required"
    }
})
