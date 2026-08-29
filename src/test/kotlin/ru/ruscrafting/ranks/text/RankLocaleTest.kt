package ru.ruscrafting.ranks.text

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.format.TextDecoration
import org.yaml.snakeyaml.Yaml
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.ruscrafting.ranks.config.RankCatalogLoader
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
        val locale = RankLocale(root, defaultLocale = { "ru" }, useClientLocale = { false })

        locale.validate(catalog)
    }

    "rendered GUI item roots explicitly disable italics" {
        val root = Files.createTempDirectory("arcranks-nonitalic")
        val locale = RankLocale(root, defaultLocale = { "ru" }, useClientLocale = { false })

        locale.render("gui.common.close.name").decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
        locale.renderLines("gui.common.close.lore").forEach { line ->
            line.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
        }
        listOf(
            "gui.passport.contracts.name",
            "gui.contracts.offer.name",
            "gui.perks.card.available.name",
            "gui.analytics.cards.health.name",
        ).forEach { path ->
            locale.render(path).decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
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
