package ru.ruscrafting.ranks.config

import ru.arc.config.Config
import ru.ruscrafting.ranks.domain.MasteryThresholds
import ru.ruscrafting.ranks.domain.RankCatalog
import ru.ruscrafting.ranks.domain.RankBenefitSection
import ru.ruscrafting.ranks.domain.RankDefinition
import ru.ruscrafting.ranks.domain.RankId
import ru.ruscrafting.ranks.domain.SpecializationPath

data class LoadedRankCatalog(
    val catalog: RankCatalog,
    val mastery: Map<SpecializationPath, MasteryThresholds>,
)

class RankCatalogLoader(private val config: Config) {
    fun load(): RankCatalog = loadWithMastery().catalog

    fun loadWithMastery(): LoadedRankCatalog {
        val ranks = config.keys("ranks").map { key ->
            val root = "ranks.$key"
            RankDefinition(
                id = RankId(key),
                luckPermsGroup = config.string("$root.group"),
                order = config.int("$root.order"),
                displayNameKey = config.string("$root.name-key"),
                activeMinutesRequired = config.long("$root.active-minutes"),
                requiredChoices = config.int("$root.required-paths"),
                pathGoals = SpecializationPath.entries.associateWith { path ->
                    config.long("$root.goals.${path.key()}")
                },
                benefitKeys = config.stringList("$root.benefits"),
                benefitSections = config.keys("$root.benefit-sections")
                    .map { start ->
                        RankBenefitSection(
                            startIndex = start.toInt() - 1,
                            titleKey = config.string("$root.benefit-sections.$start"),
                        )
                    }
                    .sortedBy(RankBenefitSection::startIndex),
            )
        }
        val mastery = SpecializationPath.entries.associateWith { path ->
            val values = config.list<Any>("mastery.${path.key()}").map { value ->
                when (value) {
                    is Number -> value.toLong()
                    else -> value.toString().toLongOrNull()
                        ?: throw IllegalArgumentException("Mastery threshold for ${path.key()} is not a number")
                }
            }
            require(values.size == 3 || values.size == 6) { "Mastery path ${path.key()} must define three or six thresholds" }
            val activeMinutes = config.list<Any>("mastery-active-minutes").map { value ->
                value.toString().toLongOrNull()
                    ?: throw IllegalArgumentException("Mastery active minutes must be whole numbers")
            }.let { configured ->
                if (configured.isEmpty()) {
                    require(values.size == 3) { "Six-level mastery requires active-time requirements" }
                    List(3) { 0L }
                }
                else {
                    require(configured.size in listOf(3, 6) && configured.size >= values.size) { "Missing mastery active-time requirements" }
                    configured.take(values.size)
                }
            }
            MasteryThresholds(values[0], values[1], values[2], values.drop(3), activeMinutes)
        }
        return LoadedRankCatalog(RankCatalog(ranks), mastery)
    }
}

private fun SpecializationPath.key(): String = name.lowercase()
