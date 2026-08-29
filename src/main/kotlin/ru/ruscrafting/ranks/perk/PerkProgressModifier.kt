package ru.ruscrafting.ranks.perk

import ru.ruscrafting.ranks.analytics.ProductDimension
import ru.ruscrafting.ranks.analytics.ProductEvent
import ru.ruscrafting.ranks.analytics.ProductTelemetry
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.progress.ProgressBuffer
import java.util.UUID

class PerkProgressModifier(
    private val buffer: ProgressBuffer,
    private val catalog: PerkCatalog,
    private val fractionalBonus: FractionalProgressBonus,
    private val telemetry: ProductTelemetry? = null,
    private val selectedPerks: (UUID) -> Collection<PerkId>,
) {
    fun recordCounter(playerId: UUID, metric: ProgressMetric, baseDelta: Long): Boolean {
        require(baseDelta > 0) { "Progress delta must be positive" }
        val path = SpecializationPath.entries.firstOrNull { it.metric == metric }
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
