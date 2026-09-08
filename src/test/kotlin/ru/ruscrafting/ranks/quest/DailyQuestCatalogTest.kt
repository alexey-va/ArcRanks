package ru.ruscrafting.ranks.quest

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import ru.arc.config.Config
import ru.ruscrafting.ranks.config.RankCatalogLoader
import ru.ruscrafting.ranks.gui.DailyQuestLayout
import java.nio.file.Files
import java.time.LocalDate
import java.util.UUID

class DailyQuestCatalogTest : StringSpec({
    fun catalog(): DailyQuestCatalog {
        val root = Files.createTempDirectory("daily-catalog")
        val ranks = RankCatalogLoader(Config(root, "ranks.yml")).load().ranks.map { it.id.value }.toSet()
        return DailyQuestCatalog.load(Config(root, "daily-quests.yml"), ranks)
    }
    val player = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val day = LocalDate.parse("2026-09-08")
    "rank allowance grows from six to twenty one without duplicates" {
        val catalog = catalog()
        catalog.select(player, day, "settler").size shouldBe 6
        catalog.select(player, day, "caesar").size shouldBe 21
        catalog.countByRank.forEach { (rank, count) ->
            val board = catalog.select(player, day, rank)
            board.map { it.id }.distinct().size shouldBe count
            (board.count { it.tokens > 0 } <= 1) shouldBe true
        }
    }
    "assignment is stable across config key ordering and changes between days" {
        val catalog = catalog()
        catalog.select(player, day, "settler") shouldBe catalog.copy(pool = catalog.pool.reversed()).select(player, day, "settler")
        catalog.select(player, day, "settler") shouldNotBe catalog.select(player, day.plusDays(1), "settler")
    }
    "rare reward is at most one with explicit zero and hundred percent boundaries" {
        val catalog = catalog()
        catalog.copy(rareChancePercent = 0).select(player, day, "caesar").count { it.tokens > 0 } shouldBe 0
        val board = catalog.copy(rareChancePercent = 100).select(player, day, "caesar")
        board.count { it.tokens > 0 } shouldBe 1
        board.sumOf { it.money } shouldBe 1150
        val rare = board.single { it.tokens > 0 }
        rare.target shouldBe catalog.pool.single { it.id == rare.id }.target * 2
    }
    "invalid counts cannot silently truncate or create empty boards" {
        val catalog = catalog()
        runCatching { catalog.copy(countByRank = mapOf("settler" to 0)) }.isFailure shouldBe true
        runCatching { catalog.copy(countByRank = mapOf("settler" to 22)) }.isFailure shouldBe true
    }
    "geometry fits every allowance with footer separated from cards" {
        (1..21).forEach { count ->
            val geometry = DailyQuestLayout(count)
            geometry.slots.size shouldBe count
            geometry.slots.distinct().size shouldBe count
            (geometry.slots.max() < geometry.size - 9) shouldBe true
        }
        DailyQuestLayout(6).rows shouldBe 3
        DailyQuestLayout(8).rows shouldBe 4
        DailyQuestLayout(21).rows shouldBe 5
    }
})
