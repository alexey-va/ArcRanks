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
    fun catalog(configure: (Config) -> Unit = {}): DailyQuestCatalog {
        val root = Files.createTempDirectory("daily-catalog")
        val ranks = RankCatalogLoader(Config(root, "ranks.yml")).load().ranks.map { it.id.value }.toSet()
        return DailyQuestCatalog.load(Config(root, "daily-quests.yml").also(configure), ranks)
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
    "feature flags and per-quest enabled gate the pool" {
        val chainsDisabled = catalog { it.setBoolean("features.chains", false) }
        chainsDisabled.pool.none { it.id in setOf("bakery_order", "forge_route", "expedition_supply") } shouldBe true

        val voteDisabled = catalog { it.setBoolean("features.voting", false) }
        voteDisabled.pool.none { it.id == "vote" } shouldBe true

        val questDisabled = catalog { it.setBoolean("quests.vote.enabled", false) }
        questDisabled.pool.none { it.id == "vote" } shouldBe true
        questDisabled.pool.any { it.id == "discord_link" } shouldBe true
    }
    "availability and minimum rank gate contextual and advanced quests" {
        val catalog = catalog().copy(advancedPerDay = 21)
        val available = setOf("contract.open:forge_iron_ingot")
        val settler = catalog.select(player, day, "settler", available = available, countOverride = 76)
        settler.none { it.id == "forge_route" } shouldBe true

        val citizen = catalog.select(player, day, "citizen", available = available, countOverride = 76)
        citizen.any { it.id == "forge_route" } shouldBe true
        citizen.none { it.id == "expedition_supply" } shouldBe true

        val knight = catalog.select(player, day, "knight", available = available, countOverride = 76)
        knight.any { it.id == "expedition_supply" } shouldBe true
        knight.none { it.id == "town_coal" } shouldBe true
    }
    "family limits and recent history steer deterministic selection" {
        val catalog = catalog()
        val first = catalog.select(player, day, "caesar", countOverride = 10)
        first.groupingBy { it.family }.eachCount().values.all { it <= 2 } shouldBe true
        val recent = first.associate { it.id to day.minusDays(1) }
        val next = catalog.select(player, day, "caesar", recent = recent, countOverride = 10)
        next.none { it.id in recent } shouldBe true
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
        board.sumOf { it.money } shouldBe 4025
        board.single { it.tokens > 0 }.tokens shouldBe 3
        val rare = board.single { it.tokens > 0 }
        rare.target shouldBe catalog.pool.single { it.id == rare.id }.target * 6
    }
    "invalid counts cannot silently truncate or create empty boards" {
        val catalog = catalog()
        runCatching { catalog.copy(countByRank = mapOf("settler" to 0)) }.isFailure shouldBe true
        runCatching { catalog.copy(countByRank = mapOf("settler" to 22)) }.isFailure shouldBe true
    }
    "seventy six templates include precise materials and species with increasing rank terms" {
        val catalog = catalog().copy(rareChancePercent = 0)
        catalog.pool.size shouldBe 76
        catalog.pool.map { it.objective }.containsAll(listOf("breed:cow", "fish:cod", "smelt:iron_ingot", "craft:bread", "build:glass")) shouldBe true
        var previousTarget = 0L
        var previousMoney = 0L
        catalog.countByRank.keys.forEach { rank ->
            val board = catalog.select(player, day, rank)
            val quest = board.first()
            val base = catalog.pool.single { it.id == quest.id }
            quest shouldBe catalog.scalingByRank.getValue(rank).apply(base)
            (quest.target >= previousTarget) shouldBe true
            (quest.money > previousMoney) shouldBe true
            previousTarget = quest.target
            previousMoney = quest.money
        }
        val last = catalog.scalingByRank.getValue("caesar").apply(catalog.pool.single { it.id == "harvest" })
        last.target shouldBe 192
        last.money shouldBe 175
        last.bonus shouldBe 20
    }
    "plans scale by step targets and rare selection excludes vote and social quests" {
        val catalog = catalog().copy(rareChancePercent = 100)
        val available = setOf("vote.enabled", "discord.unlinked", "telegram.unlinked")
        val board = catalog.select(player, day, "caesar", available = available, countOverride = 76)
        val scaling = catalog.scalingByRank.getValue("caesar")
        board.forEach { quest ->
            val base = catalog.pool.single { it.id == quest.id }
            if (base.plan != null) {
                val factor = if (quest.tokens > 0 && base.scaleTarget) 600 else 300
                quest.plan!!.steps.map { it.target } shouldBe base.plan.steps.map { (it.target * factor + 99) / 100 }
            }
        }
        val rare = board.single { it.tokens > 0 }
        rare.rareEligible shouldBe true
        board.filter { it.id in setOf("vote", "discord_link", "telegram_link") }.forEach { quest ->
            quest.tokens shouldBe 0
            quest.target shouldBe catalog.pool.single { it.id == quest.id }.target
        }
        scaling.rareTokens shouldBe 3L
    }
    "qualified action matches a general quest and only the matching specific variant" {
        val pool = catalog().pool
        pool.single { it.id == "fish" }.matchesObjective("fish:cod") shouldBe true
        pool.single { it.id == "fish_cod" }.matchesObjective("fish:cod") shouldBe true
        pool.single { it.id == "fish_salmon" }.matchesObjective("fish:cod") shouldBe false
        pool.single { it.id == "fish_cod" }.matchesObjective("fish") shouldBe false
        pool.single { it.id == "fish" }.matchesObjective("fishing:cod") shouldBe false
    }
    "scaling rounds objectives upward and rejects unsafe reward settings" {
        val scale = DailyQuestScaling(targetPercent = 115, moneyPercent = 120)
        val base = catalog().pool.single { it.id == "breed" }
        scale.apply(base).target shouldBe 5
        scale.apply(base).money shouldBe 60
        runCatching { DailyQuestScaling(targetPercent = 0) }.isFailure shouldBe true
        runCatching { DailyQuestScaling(rareTokens = 0) }.isFailure shouldBe true
        runCatching { catalog().copy(rareMoney = 1_000_000) }.isFailure shouldBe true
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
