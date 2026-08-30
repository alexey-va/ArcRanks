package ru.ruscrafting.ranks.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.ruscrafting.ranks.domain.RankId
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

    "mastery thresholds are parsed for every specialization" {
        val root = Files.createTempDirectory("arcranks-mastery")
        val loaded = RankCatalogLoader(Config(root, "ranks.yml")).loadWithMastery()

        loaded.mastery.size shouldBe 6
        loaded.mastery.values.all { it.levelOne < it.levelTwo && it.levelTwo < it.levelThree } shouldBe true
    }
})

private fun copyBundledRanks(root: java.nio.file.Path) {
    val bytes = checkNotNull(RankCatalogLoaderTest::class.java.classLoader.getResourceAsStream("ranks.yml")).use { it.readAllBytes() }
    Files.write(root.resolve("ranks.yml"), bytes)
}
