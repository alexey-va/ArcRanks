package ru.ruscrafting.ranks.text

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.yaml.snakeyaml.Yaml
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.ruscrafting.ranks.config.RankCatalogLoader
import ru.ruscrafting.ranks.kit.WeeklyKitCatalogLoader
import java.nio.file.Files
import java.time.LocalDate

class RankLocaleTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "Russian and English catalogs have exact visible-key parity" {
        val russian = resourceMap("lang/ru.yml")
        val english = resourceMap("lang/en.yml")

        leafKeys(russian).shouldContainExactlyInAnyOrder(leafKeys(english))
    }

    "catalog names, benefits, commands, and GUI surfaces validate together" {
        val root = Files.createTempDirectory("arcranks-locale")
        val catalog = RankCatalogLoader(Config(root, "ranks.yml")).load()
        val weeklyKits = WeeklyKitCatalogLoader(Config(root, "weekly-kits.yml")).load()
        val locale = RankLocale(root, defaultLocale = { "ru" }, useClientLocale = { false })

        locale.validate(catalog, weeklyKits = weeklyKits)
    }

    "rendered GUI item roots explicitly disable italics" {
        val root = Files.createTempDirectory("arcranks-nonitalic")
        val locale = RankLocale(root, defaultLocale = { "ru" }, useClientLocale = { false })

        locale.render("gui.common.back.name").decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
        locale.renderLines("gui.common.back.lore").forEach { line ->
            line.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
        }
        listOf(
            "gui.passport.contracts.name",
            "gui.contracts.offer.name",
            "gui.perks.card.available.name",
            "gui.analytics.cards.health.name",
            "gui.passport.weekly-kit.name",
            "gui.weekly-kit.claim.available.name",
        ).forEach { path ->
            locale.render(path).decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
        }
    }

    "rank-state GUI errors use clean lore lines instead of command messages" {
        val root = Files.createTempDirectory("arcranks-rank-state-lore")
        val locale = RankLocale(root, defaultLocale = { "ru" }, useClientLocale = { false })
        val plain = PlainTextComponentSerializer.plainText()

        listOf("missing", "conflict", "unknown").forEach { state ->
            val lines = locale.renderLines("gui.state.rank-$state.lore")
            lines.size shouldBe 4
            lines.forEach { line ->
                val text = plain.serialize(line)
                ("LF" in text) shouldBe false
                ("Ранги" in text) shouldBe false
                line.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
            }
        }
    }

    "every clickable GUI item ends with the shared triangle action footer" {
        val root = Files.createTempDirectory("arcranks-action-footer")
        val plain = PlainTextComponentSerializer.plainText()

        listOf("ru", "en").forEach { language ->
            val locale = RankLocale(root, defaultLocale = { language }, useClientLocale = { false })
            ACTION_LORE_PATHS.forEach { path ->
                val lines = locale.renderLines(path)
                plain.serialize(lines[lines.lastIndex - 1]).isBlank() shouldBe true
                plain.serialize(lines.last()).startsWith("[▶] ") shouldBe true
            }
        }
    }

    "rank benefits render as a real bullet list" {
        val root = Files.createTempDirectory("arcranks-benefit-list")
        val plain = PlainTextComponentSerializer.plainText()

        listOf("ru", "en").forEach { language ->
            val locale = RankLocale(root, defaultLocale = { language }, useClientLocale = { false })
            RANK_IDS.forEach { rankId ->
                (1..6).forEach { benefit ->
                    plain.serialize(locale.render("ranks.$rankId.benefits.$benefit")).startsWith("• ") shouldBe true
                }
            }
        }
    }

    "large active-time requirements render as readable days and hours" {
        val root = Files.createTempDirectory("arcranks-duration")
        val locale = RankLocale(root, defaultLocale = { "ru" }, useClientLocale = { false })
        val plain = PlainTextComponentSerializer.plainText()

        plain.serialize(locale.renderDurationMinutes(45)) shouldBe "45 мин."
        plain.serialize(locale.renderDurationMinutes(135)) shouldBe "2 ч. 15 мин."
        plain.serialize(locale.renderDurationMinutes(9_553)) shouldBe "6 дн. 15 ч. 13 мин."
        plain.serialize(locale.renderWeekPeriod(LocalDate.parse("2026-08-24"))) shouldBe "24 августа — 30 августа"
    }
})

private val ACTION_LORE_PATHS = listOf(
    "gui.common.back.lore",
    "gui.common.refresh.lore",
    "gui.profile.lore",
    "gui.passport.contracts.lore",
    "gui.passport.paths.lore",
    "gui.passport.perks.lore",
    "gui.passport.weekly-kit.lore",
    "gui.weekly-kit.claim.available.lore",
    "gui.path.available.lore",
    "gui.path.complete.lore",
    "gui.promotion.ready.lore",
    "gui.promotion.blocked.lore",
    "gui.state.error.lore",
    "gui.state.rank-missing.lore",
    "gui.state.rank-conflict.lore",
    "gui.state.rank-unknown.lore",
    "gui.contracts.offer.lore",
    "gui.contracts.claim.ready.lore",
    "gui.contracts.reroll.lore",
    "gui.perks.slot.empty.lore",
    "gui.perks.slot.active.lore",
    "gui.perks.card.available.lore",
    "gui.perks.card.selected.lore",
    "gui.analytics.window.available.lore",
)

private val RANK_IDS = listOf(
    "settler",
    "peasant",
    "citizen",
    "artisan",
    "knight",
    "baron",
    "count",
    "prince",
    "caesar",
)

@Suppress("UNCHECKED_CAST")
private fun resourceMap(path: String): Map<String, Any?> =
    checkNotNull(RankLocaleTest::class.java.classLoader.getResourceAsStream(path)).use { input ->
        Yaml().load<Map<String, Any?>>(input)
    }

private fun leafKeys(value: Any?, prefix: String = ""): List<String> = when (value) {
    is Map<*, *> -> value.entries.flatMap { (key, child) ->
        val path = if (prefix.isEmpty()) key.toString() else "$prefix.$key"
        leafKeys(child, path)
    }
    else -> listOf(prefix)
}
