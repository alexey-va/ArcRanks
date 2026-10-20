package ru.ruscrafting.ranks.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import ru.arc.config.Config
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
        settings.gui.rankCurrent.material shouldBe "ORANGE_STAINED_GLASS_PANE"
        settings.gui.rankNext.material shouldBe "YELLOW_STAINED_GLASS_PANE"
        settings.gui.rankLocked.material shouldBe "GRAY_STAINED_GLASS_PANE"
        settings.gui.contracts.material shouldBe "WRITABLE_BOOK"
        settings.gui.perks.material shouldBe "ENCHANTED_BOOK"
        settings.gui.analytics.material shouldBe "SPYGLASS"
        settings.analytics.enabled shouldBe true
        settings.analytics.flushTicks shouldBe 1_200
        settings.reminders.firstDelaySeconds shouldBe 60
        settings.reminders.cooldownSeconds shouldBe 1_800
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

    "dungeon entrance destinations load explicit server coordinates with optional rotation" {
        val root = Files.createTempDirectory("arcranks-dungeon-destination")
        ArcRanksSettings.load(root) { "test-secret" }
        Config(root, "config.yml").apply {
            setStructured("quest-travel.dungeons", mapOf("em_id_the_mines" to mapOf(
                "server" to "spawn", "world" to "em_adventurers_guild", "x" to 100.5, "y" to 78.0, "z" to 200.5,
            )))
            saveStrict()
        }

        ArcRanksSettings.loadFresh(root) { "test-secret" }.dungeonQuestDestinations shouldBe
            mapOf("em_id_the_mines" to DungeonQuestDestination("spawn", "em_adventurers_guild", 100.5, 78.0, 200.5))
    }

    "incomplete unsafe and non-finite dungeon destinations reject configuration" {
        val root = Files.createTempDirectory("arcranks-invalid-dungeon-destination")
        ArcRanksSettings.load(root) { "test-secret" }
        val invalid: List<Config.() -> Unit> = listOf(
            { removeKey("quest-travel.dungeons.em_id_the_mines.x") },
            { setString("quest-travel.dungeons.em_id_the_mines.x", "NaN") },
            { setString("quest-travel.dungeons.em_id_the_mines.yaw", "Infinity") },
            { setDouble("quest-travel.dungeons.em_id_the_mines.pitch", 91.0) },
            { setString("quest-travel.dungeons.em_id_the_mines.world", " ") },
            { setString("quest-travel.dungeons.em_id_the_mines.server", "spawn/other") },
        )
        invalid.forEach { change ->
            Config(root, "config.yml").apply {
                setStructured("quest-travel.dungeons", mapOf("em_id_the_mines" to mapOf(
                    "server" to "spawn", "world" to "em_adventurers_guild", "x" to 100.5, "y" to 78.0, "z" to 200.5,
                )))
                change()
                saveStrict()
            }

            shouldThrowAny { ArcRanksSettings.loadFresh(root) { "test-secret" } }
        }
    }
})
