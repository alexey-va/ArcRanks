package ru.ruscrafting.ranks.kit

import ru.arc.config.Config
import ru.ruscrafting.ranks.config.GuiItemSpec
import ru.ruscrafting.ranks.domain.RankId

data class WeeklyKitCatalog(val definitions: List<WeeklyKitDefinition>) {
    private val byRank = definitions.associateBy { it.rankId }

    init {
        require(definitions.isNotEmpty()) { "Weekly kit catalog is empty" }
        require(byRank.size == definitions.size) { "Duplicate weekly kit rank" }
        require(definitions.map { it.kitId }.toSet().size == definitions.size) { "Duplicate CMI kit id" }
    }

    fun require(rankId: RankId): WeeklyKitDefinition =
        requireNotNull(byRank[rankId]) { "Missing weekly kit for ${rankId.value}" }
}

class WeeklyKitCatalogLoader(private val config: Config) {
    fun load(): WeeklyKitCatalog = WeeklyKitCatalog(
        config.keys("kits").map { rank ->
            val root = "kits.$rank"
            val contentKeys = config.stringList("$root.contents")
            WeeklyKitDefinition(
                rankId = RankId(rank),
                kitId = config.string("$root.cmi-id"),
                minimumFreeSlots = config.int("$root.minimum-free-slots"),
                contentLines = contentKeys.size,
                icon = GuiItemSpec(
                    config.string("$root.icon.material"),
                    config.int("$root.icon.custom-model-data"),
                ),
                summaryKey = config.string("$root.summary-key"),
                contentKeys = contentKeys,
            )
        },
    )
}
