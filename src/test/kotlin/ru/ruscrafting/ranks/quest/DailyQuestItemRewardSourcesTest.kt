package ru.ruscrafting.ranks.quest

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import ru.ruscrafting.ranks.api.RankQuestApi
import ru.ruscrafting.ranks.api.RankQuestApi.ItemRewardSource
import ru.ruscrafting.ranks.api.RankQuestApi.ItemRewardSourceSnapshot
import ru.ruscrafting.ranks.config.RankCatalogLoader
import ru.ruscrafting.ranks.storage.ExternalProgressResult
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture

class DailyQuestItemRewardSourcesTest : StringSpec({
    val itemQuest = DailyQuest.ALL.first().copy(
        id = "enchant", textId = "enchant", itemPreset = "enchant_supply", itemAmount = 1,
    )

    "legacy functional providers keep recording and report no source support" {
        val api = RankQuestApi { _, _, _, _, _ -> CompletableFuture.completedFuture(ExternalProgressResult.APPLIED) }
        api.record("test", "event", UUID.randomUUID(), "enchant", 1).join() shouldBe ExternalProgressResult.APPLIED
        api.itemRewardSources() shouldBe ItemRewardSourceSnapshot(false, emptyList())
    }

    "snapshots copy and expose immutable source rows" {
        val rows = mutableListOf(ItemRewardSource("enchant", "Enchant an item", "enchant_supply", 1))
        val snapshot = ItemRewardSourceSnapshot(true, rows)
        rows.clear()
        snapshot.sources().size shouldBe 1
        shouldThrow<UnsupportedOperationException> { (snapshot.sources() as MutableList).clear() }
        shouldThrow<IllegalArgumentException> { ItemRewardSourceSnapshot(false, snapshot.sources()) }
    }

    "current catalog changes replace source terms without changing previous snapshots" {
        val catalog = DailyQuestCatalog(mapOf("settler" to 1), listOf(
            itemQuest,
            itemQuest.copy(id = "weekly", period = QuestPeriod.WEEKLY),
            itemQuest.copy(id = "no_item", itemPreset = null, itemAmount = 0),
        ))
        val before = dailyQuestItemRewardSources(catalog) { "Loaded ${it.textId}" }
        before.available() shouldBe true
        before.sources() shouldBe listOf(ItemRewardSource("enchant", "Loaded enchant", "enchant_supply", 1))

        val changed = catalog.copy(pool = listOf(itemQuest.copy(itemPreset = "new_supply", itemAmount = 2)))
        dailyQuestItemRewardSources(changed) { "New title" }.sources() shouldBe
            listOf(ItemRewardSource("enchant", "New title", "new_supply", 2))
        before.sources().single().presetId() shouldBe "enchant_supply"
    }

    "disabled native features and impossible selection modes are not sources" {
        val root = Files.createTempDirectory("daily-item-reward-sources")
        val ranks = RankCatalogLoader(Config(root, "ranks.yml")).load().ranks.map { it.id.value }.toSet()
        val config = Config(root, "daily-quests.yml").apply {
            setBoolean("quests.enchant.enabled", false)
            setBoolean("features.ordinary", false)
        }
        val loaded = DailyQuestCatalog.load(config, ranks)
        dailyQuestItemRewardSources(loaded) { it.textId }.sources().none { it.questId() == "enchant" || it.questId() == "active" } shouldBe true

        val composite = itemQuest.copy(id = "composite", plan = QuestPlan(QuestMode.CHAIN, listOf(QuestStep("enchant", 1, "enchant"))))
        val social = itemQuest.copy(id = "social", objective = "account.link")
        val catalog = DailyQuestCatalog(mapOf("settler" to 1), listOf(itemQuest, composite, social), advancedPerDay = 0, socialChancePercent = 0)
        dailyQuestItemRewardSources(catalog) { it.textId }.sources().map { it.questId() } shouldBe listOf("enchant")
        dailyQuestItemRewardSources(catalog.copy(pool = listOf(composite, social))) { it.textId } shouldBe
            ItemRewardSourceSnapshot(true, emptyList())
    }
})
