package ru.ruscrafting.ranks.contract

import ru.arc.config.Config
import ru.ruscrafting.ranks.domain.SpecializationPath

class ContractCatalogLoader(private val config: Config) {
    fun load(): ContractCatalog = ContractCatalog(
        baseTargets = SpecializationPath.entries.associateWith { path ->
            config.long("targets.${path.name.lowercase()}")
        },
        rankScaleBasisPoints = config.int("rank-scale-basis-points"),
        rewardBasisPoints = config.int("reward-basis-points"),
    )
}
