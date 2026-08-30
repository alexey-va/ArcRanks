package ru.ruscrafting.ranks.kit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import ru.ruscrafting.ranks.config.RankCatalogLoader
import java.nio.file.Files

class WeeklyKitCatalogTest : StringSpec({
    "bundled weekly kit catalog matches every rank and uses safe native CMI ids" {
        val root = Files.createTempDirectory("arcranks-weekly-kits")
        val kits = WeeklyKitCatalogLoader(Config(root, "weekly-kits.yml")).load()
        val ranks = RankCatalogLoader(Config(root, "ranks.yml")).load()

        kits.definitions.map { it.rankId }.shouldContainExactly(ranks.ranks.map { it.id })
        kits.definitions.map { it.kitId }.toSet().size shouldBe 9
        kits.definitions.associate { it.rankId.value to it.minimumFreeSlots } shouldBe mapOf(
            "settler" to 2,
            "peasant" to 3,
            "citizen" to 4,
            "artisan" to 5,
            "knight" to 6,
            "baron" to 7,
            "count" to 7,
            "prince" to 7,
            "caesar" to 7,
        )
        kits.definitions.all { it.kitId.matches(Regex("arcranks_weekly_[a-z0-9_]{1,40}")) } shouldBe true
        kits.definitions.all { it.minimumFreeSlots in 1..9 } shouldBe true
        kits.definitions.all { it.contentLines in 3..8 } shouldBe true
    }
})
