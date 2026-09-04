package ru.ruscrafting.ranks.text

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldContainAll
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

    "every contract path explains the concrete actions that advance it" {
        listOf("ru", "en").forEach { language ->
            val keys = leafKeys(resourceMap("lang/$language.yml"))
            keys.shouldContainAll(
                listOf("farming", "industry", "trade", "exploration", "building", "community")
                    .flatMap { path ->
                        listOf(
                            "gui.contracts.actions.$path.goal",
                            "gui.contracts.actions.$path.first",
                            "gui.contracts.actions.$path.second",
                            "gui.contracts.actions.$path.third",
                        )
                    },
            )
        }
    }

    "contract actions are separate readable lines and paths consistently use focus terminology" {
        listOf("ru", "en").forEach { language ->
            val locale = resourceMap("lang/$language.yml")
            listOf("farming", "industry", "trade", "exploration", "building", "community").forEach { path ->
                listOf("first", "second", "third").forEach { action ->
                    val text = resourceValue(locale, "gui.contracts.actions.$path.$action").toString()
                    ("•" in text) shouldBe false
                }
            }
        }

        val russian = resourceMap("lang/ru.yml")
        resourceValue(russian, "gui.contracts.actions.community.goal").toString() shouldBe
            "<italic:false>Общайтесь и делайте общие дела"
        resourceValue(russian, "gui.contracts.actions.community.third").toString().contains("Общайтесь в чате") shouldBe true
        leafValues(russian).any { "ориентир" in it.lowercase() } shouldBe false
        listOf(
            "gui.path.complete.name",
            "gui.path.selected.name",
            "gui.contracts.ready.name",
            "gui.contracts.stamp.name",
        ).forEach { path ->
            resourceValue(russian, path).toString().contains("✓") shouldBe false
        }
    }

    "home points grow gradually from one to five across ranks" {
        val root = Files.createTempDirectory("arcranks-home-benefits")
        val locale = RankLocale(root, defaultLocale = { "ru" }, useClientLocale = { false })
        val plain = PlainTextComponentSerializer.plainText()
        val expected = linkedMapOf(
            "settler" to 1,
            "peasant" to 2,
            "citizen" to 2,
            "artisan" to 3,
            "knight" to 3,
            "baron" to 4,
            "count" to 4,
            "prince" to 5,
            "caesar" to 5,
        )

        expected.forEach { (rank, homePoints) ->
            val benefit = if (rank == "settler") 3 else 2
            plain.serialize(locale.render("ranks.$rank.benefits.$benefit")) shouldBe "• Точки дома: $homePoints"
        }
    }

    "builder selection limits grow through five rank tiers" {
        val root = Files.createTempDirectory("arcranks-builder-benefits")
        val locale = RankLocale(root, defaultLocale = { "ru" }, useClientLocale = { false })
        val plain = PlainTextComponentSerializer.plainText()
        val expected = linkedMapOf(
            "settler" to (9 to 20),
            "peasant" to (9 to 20),
            "citizen" to (9 to 40),
            "artisan" to (10 to 40),
            "knight" to (11 to 60),
            "baron" to (13 to 60),
            "count" to (15 to 80),
            "prince" to (16 to 80),
            "caesar" to (16 to 100),
        )

        expected.forEach { (rank, benefitAndLimit) ->
            val (benefit, limit) = benefitAndLimit
            plain.serialize(locale.render("ranks.$rank.benefits.$benefit")) shouldBe
                "• Выделение строителя: до $limit блоков по стороне"
        }
    }

    "rank benefit accents decorate values instead of whole rows" {
        val russian = resourceMap("lang/ru.yml")
        val ranks = listOf("settler", "peasant", "citizen", "artisan", "knight", "baron", "count", "prince", "caesar")

        ranks.forEach { rank ->
            val benefits = resourceValue(russian, "ranks.$rank.benefits") as Map<*, *>
            benefits.values.forEach { benefit ->
                benefit.toString().startsWith(
                    "<italic:false><#8c8c8c>•</color> <#fff0d8>",
                ) shouldBe true
            }
        }
        listOf(
            "citizen" to 6,
            "artisan" to 6,
            "knight" to 7,
            "baron" to 8,
            "count" to 9,
            "prince" to 9,
            "caesar" to 9,
        ).forEach { (rank, benefit) ->
            val benefits = resourceValue(russian, "ranks.$rank.benefits") as Map<*, *>
            benefits[benefit].toString().contains("<#92bed8>") shouldBe true
        }
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

    "paths button explains what paths are and why they matter" {
        val root = Files.createTempDirectory("arcranks-paths-explanation")
        val locale = RankLocale(root, defaultLocale = { "ru" }, useClientLocale = { false })
        val plain = PlainTextComponentSerializer.plainText()

        val lore = locale.renderLines("gui.passport.paths.lore").map(plain::serialize)

        lore.first() shouldBe "Пути — это шесть направлений постоянного прогресса."
        lore.any { it.startsWith("Чтобы открыть ранг «") } shouldBe true
        ("• Поднимайте ступени путей — открывайте пассивные усиления." in lore) shouldBe true
        lore.none { "Все шесть специализаций" in it } shouldBe true
    }

    "player menus explain weekly contracts and upgrades without internal shorthand" {
        val root = Files.createTempDirectory("arcranks-self-contained-menu-copy")
        val locale = RankLocale(root, defaultLocale = { "ru" }, useClientLocale = { false })
        val plain = PlainTextComponentSerializer.plainText()
        val stamp = locale.renderLines(
            "gui.contracts.stamp.lore",
            values = mapOf(
                "contract-number" to locale.text(2),
                "stamps" to locale.text(2),
                "remaining" to locale.text(1),
            ),
        ).map(plain::serialize)

        stamp.any { "награды за контракт № 2" in it } shouldBe true
        stamp.any { "Выполнено контрактов: 2 из 3" in it } shouldBe true
        stamp.any { "Отдельной награды за завершение всех трёх нет" in it } shouldBe true

        val russianGui = resourceValue(resourceMap("lang/ru.yml"), "gui")
        leafValues(russianGui).none { "недельная отмет" in it.lowercase() } shouldBe true
        leafValues(russianGui).none { "недельный цикл" in it.lowercase() } shouldBe true

        val upgrades = locale.renderLines("gui.passport.perks.lore").map(plain::serialize)
        upgrades.any { "Усиление — это пассивный бонус" in it } shouldBe true
        upgrades.any { "Одновременно работают не больше двух" in it } shouldBe true
    }

    "rank benefits render as a real bullet list" {
        val root = Files.createTempDirectory("arcranks-benefit-list")
        val plain = PlainTextComponentSerializer.plainText()

        listOf("ru", "en").forEach { language ->
            val locale = RankLocale(root, defaultLocale = { language }, useClientLocale = { false })
            val catalog = RankCatalogLoader(Config(root, "ranks.yml")).load()
            catalog.ranks.forEach { rank ->
                rank.benefitKeys.forEach { benefit ->
                    plain.serialize(locale.render(benefit)).startsWith("• ") shouldBe true
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
    "gui.profile.action",
    "gui.passport.contracts.lore",
    "gui.passport.paths.lore",
    "gui.passport.paths.top-lore",
    "gui.passport.perks.lore",
    "gui.passport.weekly-kit.lore",
    "gui.weekly-kit.claim.available.lore",
    "gui.path.available.lore",
    "gui.path.complete.lore",
    "gui.promotion.ready.action",
    "gui.promotion.blocked.action",
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

private fun leafValues(value: Any?): List<String> = when (value) {
    is Map<*, *> -> value.values.flatMap(::leafValues)
    is List<*> -> value.flatMap(::leafValues)
    else -> listOf(value.toString())
}

private fun resourceValue(root: Map<String, Any?>, path: String): Any? =
    path.split('.').fold(root as Any?) { current, key -> (current as Map<*, *>)[key] }
