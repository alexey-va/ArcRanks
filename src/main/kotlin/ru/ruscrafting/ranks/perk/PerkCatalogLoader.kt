package ru.ruscrafting.ranks.perk

import ru.arc.config.Config
import ru.ruscrafting.ranks.domain.MasteryLevel
import ru.ruscrafting.ranks.domain.SpecializationPath

class PerkCatalogLoader(private val config: Config) {
    fun load(): PerkCatalog = PerkCatalog(
        config.keys("perks").map { key ->
            val root = "perks.$key"
            val effect = PerkEffectKind.valueOf(config.string("$root.effect").trim().uppercase())
            PerkDefinition(
                id = PerkId(key),
                path = SpecializationPath.valueOf(config.string("$root.path").trim().uppercase()),
                requiredMastery = MasteryLevel.valueOf(config.string("$root.mastery").trim().uppercase()),
                effect = effect,
                basisPoints = if (effect.mechanic) {
                    require("basis-points" !in config.keys(root)) { "Mechanic perk $key must not define basis-points" }
                    1
                } else config.int("$root.basis-points"),
                nameKey = config.string("$root.name-key").trim(),
                descriptionKey = config.string("$root.description-key").trim(),
                progressBasisPoints = config.int("$root.progress-basis-points", 0),
            )
        },
    )
}
