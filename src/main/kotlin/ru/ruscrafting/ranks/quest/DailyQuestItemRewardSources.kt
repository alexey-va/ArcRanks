package ru.ruscrafting.ranks.quest

import ru.ruscrafting.ranks.api.RankQuestApi.ItemRewardSource
import ru.ruscrafting.ranks.api.RankQuestApi.ItemRewardSourceSnapshot

/** The loaded catalog already excludes disabled quests and feature groups. */
internal fun dailyQuestItemRewardSources(
    catalog: DailyQuestCatalog,
    title: (DailyQuest) -> String,
): ItemRewardSourceSnapshot = ItemRewardSourceSnapshot(
    true,
    catalog.pool.asSequence()
        .filter { it.period == QuestPeriod.DAILY && it.itemPreset != null }
        .filter { it.plan == null || catalog.advancedPerDay > 0 }
        .filter { !it.objective.startsWith("account.") || catalog.socialChancePercent > 0 }
        .sortedBy(DailyQuest::id)
        .map { quest ->
            ItemRewardSource(quest.id, title(quest).ifBlank { quest.id }, quest.itemPreset, quest.itemAmount)
        }
        .toList(),
)
