package ru.ruscrafting.ranks.perk

import ru.arc.config.Config
import ru.ruscrafting.ranks.domain.MasteryLevel
import ru.ruscrafting.ranks.domain.SpecializationPath

class PerkCatalogLoader(private val config: Config) {
    fun load(): PerkCatalog = PerkCatalog(
        config.keys("perks").map { key ->
            val root = "perks.$key"
            PerkDefinition(
                id = PerkId(key),
                path = SpecializationPath.valueOf(config.string("$root.path").trim().uppercase()),
                requiredMastery = MasteryLevel.valueOf(config.string("$root.mastery").trim().uppercase()),
                effect = PerkEffectKind.valueOf(config.string("$root.effect").trim().uppercase()),
                basisPoints = config.int("$root.basis-points"),
                nameKey = config.string("$root.name-key").trim(),
                descriptionKey = config.string("$root.description-key").trim(),
            )
        },
    )
}
