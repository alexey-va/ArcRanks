package ru.ruscrafting.ranks.quest

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import ru.arc.config.Config
import ru.ruscrafting.ranks.config.RankCatalogLoader
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.domain.SpecializationPath
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
    "optional preset item rewards parse only as a complete bounded pair" {
        val configured = catalog {
            it.setString("quests.harvest.item-preset", "enchant_supply")
            it.setInt("quests.harvest.item-amount", 2)
        }.pool.single { it.id == "harvest" }
        configured.itemPreset shouldBe "enchant_supply"
        configured.itemAmount shouldBe 2
        catalog().pool.first().itemPreset shouldBe null

        shouldThrow<IllegalArgumentException> {
            catalog { it.setInt("quests.harvest.item-amount", 1) }
        }
        shouldThrow<IllegalArgumentException> {
            catalog { it.setString("quests.harvest.item-preset", "enchant_supply") }
        }
        shouldThrow<IllegalArgumentException> {
            catalog {
                it.setString("quests.harvest.item-preset", "Invalid.Preset")
                it.setInt("quests.harvest.item-amount", 1)
            }
        }
        shouldThrow<IllegalArgumentException> {
            catalog {
                it.setString("quests.harvest.item-preset", "enchant_supply")
                it.setInt("quests.harvest.item-amount", 65)
            }
        }
    }
    "social gate is daily deterministic optional and never admits two account quests" {
        val config = catalog().copy(rareChancePercent = 0, rarePerDay = 0)
        val available = setOf("discord.unlinked", "telegram.unlinked")
        var admitted = 0
        for (id in 1L..100L) {
            val playerId = UUID(0, id)
            val off = config.copy(socialChancePercent = 0).select(playerId, day, "caesar", available = available)
            off.none { it.objective.startsWith("account.") } shouldBe true
            val on = config.copy(socialChancePercent = 100).select(playerId, day, "caesar", available = available)
            on.count { it.objective.startsWith("account.") } shouldBe 1
            val sampled = config.select(playerId, day, "caesar", available = available)
            sampled.count { it.objective.startsWith("account.") } shouldBe
                config.select(playerId, day, "caesar", available = available).count { it.objective.startsWith("account.") }
            if (sampled.any { it.objective.startsWith("account.") }) admitted++
            sampled.size shouldBe 21
        }
        (admitted in 1..35) shouldBe true
        config.copy(socialChancePercent = 100).select(player, day, "caesar")
            .none { it.objective.startsWith("account.") } shouldBe true
    }

    "rank allowance grows from six to twenty one without duplicates" {
        val catalog = catalog()
        catalog.select(player, day, "settler").size shouldBe 6
        catalog.select(player, day, "caesar").size shouldBe 21
        catalog.countByRank.forEach { (rank, count) ->
            val board = catalog.select(player, day, rank)
            board.map { it.id }.distinct().size shouldBe count
            board.count { it.tokens > 0 } shouldBe 2
        }
    }
    "every rank gets two token quests each new day without extra quest slots" {
        val catalog = catalog()
        catalog.countByRank.forEach { (rank, count) ->
            for (seed in 1L..6L) {
                val date = day.plusDays(seed)
                val board = catalog.select(UUID(0, seed), date, rank)
                board.size shouldBe count
                board.count { it.tokens > 0 } shouldBe 2
                board.sumOf { it.tokens } shouldBe 2 * catalog.scalingByRank.getValue(rank).rareTokens!!
                board.filter { it.tokens > 0 }.all { it.rareEligible } shouldBe true
                board shouldBe catalog.select(UUID(0, seed), date, rank)
            }
        }
        val caesar = catalog.select(player, day, "caesar")
        caesar.sumOf { it.money } shouldBe 4375
        catalog.select(player, day, "caesar", allowRare = false, countOverride = 1)
            .single().tokens shouldBe 0
    }

    "feature flags and per-quest enabled gate the pool" {
        val defaults = catalog()
        defaults.pool.none { it.id in setOf(
            "lumber_job", "mine_job", "build_oak_planks", "forge_route", "work_choice", "artisan_collection", "team_shift",
        ) } shouldBe true
        defaults.pool.filter { it.objective.startsWith("dungeon.complete") || it.plan?.steps?.any { step -> step.objective.startsWith("dungeon.complete") } == true }
            .all { !it.scaleTarget } shouldBe true

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
        settler.none { it.id == "expedition_supply" } shouldBe true

        val citizen = catalog.select(player, day, "citizen", available = available, countOverride = 76)
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
        val catalog = catalog().copy(rarePerDay = 0)
        catalog.copy(rareChancePercent = 0).select(player, day, "caesar").count { it.tokens > 0 } shouldBe 0
        val board = catalog.copy(rareChancePercent = 100).select(player, day, "caesar")
        board.count { it.tokens > 0 } shouldBe 1
        board.sumOf { it.money } shouldBe 4025
        board.single { it.tokens > 0 }.tokens shouldBe 3
        val rare = board.single { it.tokens > 0 }
        val base = catalog.pool.single { it.id == rare.id }
        rare.target shouldBe if (base.scaleTarget) base.target * 6 else base.target
    }
    "all authored targets keep scarce actions and composite steps bounded after rank and rare scaling" {
        val allTemplates = catalog {
            it.setBoolean("features.teamwork", true)
            listOf("lumber_job", "mine_job", "build_oak_planks", "forge_route", "work_choice", "artisan_collection", "team_shift")
                .forEach { id -> it.setBoolean("quests.$id.enabled", true) }
        }
        allTemplates.pool.size shouldBe 76
        val crops = setOf("harvest", "harvest_wheat", "harvest_carrots", "harvest_potatoes", "harvest_beetroots")
        val caesar = allTemplates.scalingByRank.getValue("caesar")

        allTemplates.pool.single { it.id == "fish_tropical_fish" }.target shouldBe 1
        allTemplates.pool.single { it.id == "breed_cat" }.target shouldBe 1
        allTemplates.pool.single { it.id == "harvest" }.target shouldBe 64
        allTemplates.pool.single { it.id == "harvest_wheat" }.target shouldBe 48

        allTemplates.pool.forEach { base ->
            val scaled = caesar.apply(base)
            val rare = if (base.rareEligible) allTemplates.rare(scaled, caesar) else scaled
            if (base.id in crops) {
                rare.target shouldBe base.target * 6
            } else {
                base.scaleTarget shouldBe false
                scaled.target shouldBe base.target
                rare.target shouldBe base.target
                if (base.plan != null) rare.plan!!.steps.map { it.target } shouldBe base.plan.steps.map { it.target }
            }
        }
        listOf("bakery_order", "builder_choice", "harvest_basket", "artisan_collection", "angler_collection", "homestead", "team_shift")
            .forEach { id -> allTemplates.pool.single { it.id == id }.scaleTarget shouldBe false }
    }
    "token slot reservation survives non-eligible preferred goals and unavailable candidates" {
        val ordinary = DailyQuest.ALL.first().copy(id = "ordinary", once = true, rareEligible = false)
        val first = DailyQuest.ALL[1].copy(id = "rare-one")
        val second = DailyQuest.ALL[2].copy(id = "rare-two")
        val catalog = DailyQuestCatalog(mapOf("settler" to 2), listOf(ordinary, first, second), rarePerDay = 2)
        catalog.select(player, day, "settler").count { it.tokens > 0 } shouldBe 2
        catalog.copy(pool = listOf(ordinary, first, second.copy(availability = "provider.available")))
            .select(player, day, "settler").count { it.tokens > 0 } shouldBe 1
        runCatching { catalog.copy(rarePerDay = 22) }.isFailure shouldBe true
    }

    "invalid counts cannot silently truncate or create empty boards" {
        val catalog = catalog()
        runCatching { catalog.copy(countByRank = mapOf("settler" to 0)) }.isFailure shouldBe true
        runCatching { catalog.copy(countByRank = mapOf("settler" to 22)) }.isFailure shouldBe true
    }
    "enabled templates include precise materials and species with increasing rank terms" {
        val catalog = catalog().copy(rareChancePercent = 0, rarePerDay = 0)
        catalog.pool.size shouldBe 69
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
                val factor = when {
                    !base.scaleTarget -> 100
                    quest.tokens > 0 -> 600
                    else -> 300
                }
                quest.plan!!.steps.map { it.target } shouldBe base.plan.steps.map { (it.target * factor + 99) / 100 }
            }
        }
        board.filter { it.tokens > 0 }.all { it.rareEligible } shouldBe true
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
        val base = catalog().pool.single { it.id == "harvest" }
        scale.apply(base).target shouldBe 74
        scale.apply(base).money shouldBe 60
        runCatching { DailyQuestScaling(targetPercent = 0) }.isFailure shouldBe true
        runCatching { DailyQuestScaling(rareTokens = 0) }.isFailure shouldBe true
        runCatching { catalog().copy(rareMoney = 1_000_000) }.isFailure shouldBe true
    }
    "focus percent zero preserves the unfocused deterministic selection" {
        val noFocus = catalog().copy(focusPercent = 0)
        noFocus.select(player, day, "caesar") shouldBe
            noFocus.select(player, day, "caesar", focus = SpecializationPath.FARMING)
    }
    "focus reserves main path while retaining another path when available" {
        fun quest(id: String, metric: ProgressMetric) = DailyQuest(
            id, metric, 10, 1, "WHEAT", objective = "focus.$id", family = id,
        )
        val focused = DailyQuestCatalog(
            countByRank = mapOf("settler" to 4),
            pool = listOf(
                quest("farm_a", ProgressMetric.CROPS_HARVESTED),
                quest("farm_b", ProgressMetric.CROPS_HARVESTED),
                quest("farm_c", ProgressMetric.CROPS_HARVESTED),
                quest("industry_a", ProgressMetric.PRODUCTION_ACTIONS),
                quest("industry_b", ProgressMetric.PRODUCTION_ACTIONS),
                quest("industry_c", ProgressMetric.PRODUCTION_ACTIONS),
            ),
            rareChancePercent = 0,
            focusPercent = 100,
        )
        val board = focused.select(player, day, "settler", focus = SpecializationPath.FARMING)
        board.size shouldBe 4
        board.count { SpecializationPath.FARMING.owns(it.metric) } shouldBe 3
        board.any { !SpecializationPath.FARMING.owns(it.metric) } shouldBe true

        val half = focused.copy(focusPercent = 50)
            .select(player, day, "settler", focus = SpecializationPath.FARMING)
        half.count { SpecializationPath.FARMING.owns(it.metric) } shouldBe 2

        val onceFirst = focused.copy(
            pool = focused.pool.map { quest ->
                if (quest.metric == ProgressMetric.PRODUCTION_ACTIONS) quest.copy(once = true) else quest
            },
            focusPercent = 50,
        ).select(player, day, "settler", focus = SpecializationPath.FARMING)
        onceFirst.any { it.once } shouldBe true
    }
    "focus falls back to eligible variety when the focused path is unavailable" {
        fun quest(id: String, metric: ProgressMetric, availability: String? = null) = DailyQuest(
            id, metric, 10, 1, "WHEAT", objective = "gated.$id", family = id, availability = availability,
        )
        val gated = DailyQuestCatalog(
            countByRank = mapOf("settler" to 4),
            pool = listOf(
                quest("farm", ProgressMetric.CROPS_HARVESTED, "focus.available"),
                quest("industry_a", ProgressMetric.PRODUCTION_ACTIONS),
                quest("industry_b", ProgressMetric.PRODUCTION_ACTIONS),
                quest("industry_c", ProgressMetric.PRODUCTION_ACTIONS),
                quest("industry_d", ProgressMetric.PRODUCTION_ACTIONS),
            ),
            rareChancePercent = 0,
            focusPercent = 100,
        )
        val board = gated.select(player, day, "settler", available = emptySet(), focus = SpecializationPath.FARMING)
        board.size shouldBe 4
        board.none { SpecializationPath.FARMING.owns(it.metric) } shouldBe true
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
