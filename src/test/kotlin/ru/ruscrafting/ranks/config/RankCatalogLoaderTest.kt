package ru.ruscrafting.ranks.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.ruscrafting.ranks.domain.RankId
import ru.ruscrafting.ranks.domain.SpecializationPath
import java.nio.file.Files

class RankCatalogLoaderTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "bundled catalog exposes nine ordered permanent ranks" {
        val root = Files.createTempDirectory("arcranks-catalog")
        val catalog = RankCatalogLoader(Config(root, "ranks.yml")).load()

        catalog.ranks.map { it.id.value }.shouldContainExactly(
            "settler", "peasant", "citizen", "artisan", "knight", "baron", "count", "prince", "caesar",
        )
        catalog.ranks.map { it.luckPermsGroup }.shouldContainExactly(
            "default", "rank_peasant", "rank_citizen", "rank_artisan", "rank_knight",
            "rank_baron", "rank_count", "rank_prince", "rank_caesar",
        )
        catalog.ranks.all { it.benefitKeys.size >= 5 } shouldBe true
        catalog.require(RankId("artisan")).benefitSections.map { it.startIndex to it.titleKey }.shouldContainExactly(
            0 to "gui.rank.sections.limits",
            6 to "gui.rank.sections.features",
            8 to "gui.rank.sections.commands-and-rewards",
        )
        catalog.require(RankId("citizen")).benefitSections shouldBe emptyList()
    }

    "duplicate LuckPerms progression group is rejected" {
        val root = Files.createTempDirectory("arcranks-duplicate")
        copyBundledRanks(root)
        val config = Config(root, "ranks.yml")
        config.setString("ranks.peasant.group", "default")
        config.saveStrict()

        shouldThrow<IllegalArgumentException> { RankCatalogLoader(config).load() }
    }

    "decreasing path requirement is rejected" {
        val root = Files.createTempDirectory("arcranks-decreasing")
        copyBundledRanks(root)
        val config = Config(root, "ranks.yml")
        config.setLong("ranks.citizen.goals.farming", 1)
        config.saveStrict()

        shouldThrow<IllegalArgumentException> { RankCatalogLoader(config).load() }
    }

    "legacy three-level mastery configuration remains valid" {
        val root = Files.createTempDirectory("arcranks-legacy-mastery")
        copyBundledRanks(root)
        val file = root.resolve("ranks.yml")
        val rankSection = Files.readString(file).substringAfter("\nranks:")
        Files.writeString(file, "mastery:\n" + SpecializationPath.entries.joinToString("\n") {
            "  ${it.name.lowercase()}: [100, 500, 1000]"
        } + "\nranks:" + rankSection)
        val config = Config(root, "ranks.yml")

        val loaded = RankCatalogLoader(config).loadWithMastery()
        loaded.mastery.values.all { it.values == listOf(100L, 500L, 1000L) &&
            it.activeMinutes == listOf(0L, 0L, 0L) } shouldBe true
    }

    "six-level mastery rejects missing active-time requirements" {
        val root = Files.createTempDirectory("arcranks-mastery-time-missing")
        copyBundledRanks(root)
        val file = root.resolve("ranks.yml")
        Files.writeString(file, Files.readString(file).replace(
            "mastery-active-minutes: [0, 0, 0, 7200, 14400, 21600]", "mastery-active-minutes: []"))
        val config = Config(root, "ranks.yml")
        shouldThrow<IllegalArgumentException> { RankCatalogLoader(config).loadWithMastery() }
    }

    "mastery thresholds are parsed for every specialization" {
        val root = Files.createTempDirectory("arcranks-mastery")
        val loaded = RankCatalogLoader(Config(root, "ranks.yml")).loadWithMastery()

        loaded.mastery.size shouldBe 6
        loaded.mastery.values.all { it.levelOne < it.levelTwo && it.levelTwo < it.levelThree } shouldBe true
        loaded.mastery.values.all { it.values.size == 6 && it.activeMinutes == listOf(0L, 0L, 0L, 7200L, 14400L, 21600L) } shouldBe true
    }
})

private fun copyBundledRanks(root: java.nio.file.Path) {
    val bytes = checkNotNull(RankCatalogLoaderTest::class.java.classLoader.getResourceAsStream("ranks.yml")).use { it.readAllBytes() }
    Files.write(root.resolve("ranks.yml"), bytes)
}
