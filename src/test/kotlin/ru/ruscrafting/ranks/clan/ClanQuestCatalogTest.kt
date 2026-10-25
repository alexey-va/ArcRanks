package ru.ruscrafting.ranks.clan

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import java.nio.file.Files
import java.time.LocalDate

class ClanQuestCatalogTest : StringSpec({
    fun load(change: (Config) -> Unit = {}): ClanQuestCatalog {
        val config = Config(Files.createTempDirectory("clan-catalog"), "clan-quests.yml")
        change(config)
        return ClanQuestCatalog.load(config)
    }

    "bundled combat catalogue matches the dependency-free defaults with no redeemable reward" {
        val catalog = load()
        catalog shouldBe ClanQuestCatalog.defaults()
        catalog.pool.size shouldBe 6
        catalog.pool.all { it.groupActivity && it.minContributors == 2 && it.renown == 1L } shouldBe true
        catalog.pool.map { it.id }.toSet() shouldBe setOf("elite_hunt", "boss_hunt", "dungeon_run", "arena_training", "combat_patrol", "battle_campaign")
    }

    "weekly selection is stable across servers pool ordering and clan renames and rotates over time" {
        val catalog = load()
        val monday = LocalDate.parse("2026-10-19")
        val first = catalog.select("justteams:42", monday)
        first.size shouldBe 3
        first.map { it.id }.distinct().size shouldBe 3
        catalog.select("justteams:42", monday) shouldBe first
        catalog.copy(pool = catalog.pool.reversed()).select("justteams:42", monday) shouldBe first
        val rotations = (0L..12L).map { catalog.select("justteams:42", monday.plusWeeks(it)).map { quest -> quest.id } }
        (rotations.distinct().size > 1) shouldBe true
        (1..12).map { catalog.select("justteams:$it", monday) }.distinct().size.let { it > 1 } shouldBe true
    }

    "a reload cannot introduce currency rewards unsupported objectives or impossible project plans" {
        shouldThrow<IllegalArgumentException> { load { it.setLong("rewards.money", 50) } }
        shouldThrow<IllegalArgumentException> { load { it.setLong("rewards.renown", 5) } }
        shouldThrow<IllegalArgumentException> { load { it.setInt("rules.contribution-cap-percent", 100) } }
        shouldThrow<IllegalArgumentException> { load { it.setInt("rules.minimum-contributors", 1) } }
        shouldThrow<IllegalArgumentException> { load { it.setInt("selection.projects-per-week", 4) } }
        shouldThrow<IllegalArgumentException> { load { it.setString("projects.elite_hunt.steps.1.objective", "player.kill") } }
        shouldThrow<IllegalArgumentException> { load { it.setString("projects.elite_hunt.steps.1.objective", "craft:bread") } }
        shouldThrow<IllegalArgumentException> { load { it.setString("projects.elite_hunt.material", "MISSING_MATERIAL") } }
        shouldThrow<IllegalArgumentException> { load { it.setString("projects.elite_hunt.mode", "any") } }
    }
})
