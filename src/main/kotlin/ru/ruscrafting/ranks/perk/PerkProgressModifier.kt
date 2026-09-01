package ru.ruscrafting.ranks.perk

import ru.ruscrafting.ranks.analytics.ProductDimension
import ru.ruscrafting.ranks.analytics.ProductEvent
import ru.ruscrafting.ranks.analytics.ProductTelemetry
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.domain.MasteryEvaluator
import ru.ruscrafting.ranks.domain.MasteryThresholds
import ru.ruscrafting.ranks.domain.PlayerProgressProfile
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.progress.ProgressBuffer
import java.util.UUID

class PerkProgressModifier(
    private val buffer: ProgressBuffer,
    private val catalogProvider: () -> PerkCatalog,
    private val fractionalBonus: FractionalProgressBonus,
    private val telemetry: ProductTelemetry? = null,
    private val selectedPerks: (UUID) -> Collection<PerkId>,
) {
    constructor(
        buffer: ProgressBuffer,
        catalog: PerkCatalog,
        fractionalBonus: FractionalProgressBonus,
        telemetry: ProductTelemetry? = null,
        selectedPerks: (UUID) -> Collection<PerkId>,
    ) : this(
        buffer = buffer,
        catalogProvider = { catalog },
        fractionalBonus = fractionalBonus,
        telemetry = telemetry,
        selectedPerks = selectedPerks,
    )

    fun recordCounter(playerId: UUID, metric: ProgressMetric, baseDelta: Long): Boolean {
        require(baseDelta > 0) { "Progress delta must be positive" }
        val catalog = catalogProvider()
        val path = SpecializationPath.entries.firstOrNull { it.owns(metric) }
        val basisPoints = if (path == null || metric == ProgressMetric.WEALTH_PEAK) {
            0
        } else {
            catalog.effect(selectedPerks(playerId), path, PerkEffectKind.PROGRESS_BONUS)
        }
        val bonus = fractionalBonus.apply(playerId, metric, baseDelta, basisPoints)
        val accepted = buffer.recordCounter(playerId, metric, Math.addExact(baseDelta, bonus))
        if (accepted && bonus > 0 && path != null) {
            telemetry?.record(
                ProductEvent.PERK_BONUS_PROGRESS,
                ProductDimension("path:${path.name.lowercase()}"),
                bonus,
            )
        }
        return accepted
    }

    fun clear(playerId: UUID) {
        fractionalBonus.clear(playerId)
    }
}

fun PerkCatalog.eligibleForProgress(
    selected: Collection<PerkId>,
    profile: PlayerProgressProfile,
    masteryThresholds: Map<SpecializationPath, MasteryThresholds>,
): Set<PerkId> = selected.filterTo(linkedSetOf()) { perkId ->
    val perk = require(perkId)
    MasteryEvaluator.level(profile, perk.path, masteryThresholds.getValue(perk.path)).ordinal >=
        perk.requiredMastery.ordinal
}
