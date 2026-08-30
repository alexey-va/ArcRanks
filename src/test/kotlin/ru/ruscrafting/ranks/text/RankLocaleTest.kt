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
            lines.size shouldBe 2
            lines.forEach { line ->
                val text = plain.serialize(line)
                ("LF" in text) shouldBe false
                ("Ранги" in text) shouldBe false
                line.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
            }
        }
    }
})

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
