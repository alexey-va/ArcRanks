package ru.ruscrafting.ranks.progress

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.sqrt

data class MovementPoint(
    val worldId: String,
    val x: Double,
    val y: Double,
    val z: Double,
)

data class MovementTuning(
    val maximumStepBlocks: Double,
    val includeVertical: Boolean,
) {
    init {
        require(maximumStepBlocks.isFinite() && maximumStepBlocks > 0.0) {
            "Maximum movement step must be finite and positive"
        }
    }
}

class MovementAccumulator(private val tuningProvider: () -> MovementTuning) {
    constructor(maximumStepBlocks: Double) : this(fixedTuning(maximumStepBlocks))

    private val residuals = ConcurrentHashMap<UUID, Double>()

    fun observe(playerId: UUID, from: MovementPoint, to: MovementPoint): Long {
        if (!valid(from) || !valid(to) || from.worldId != to.worldId) {
            residuals.remove(playerId)
            return 0
        }
        val tuning = tuningProvider()
        val dx = to.x - from.x
        val dy = if (tuning.includeVertical) to.y - from.y else 0.0
        val dz = to.z - from.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        if (!distance.isFinite() || distance > tuning.maximumStepBlocks) {
            residuals.remove(playerId)
            return 0
        }
        val total = residuals.getOrDefault(playerId, 0.0) + distance
        val completed = floor(total).toLong()
        residuals[playerId] = total - completed
        return completed
    }

    fun clear(playerId: UUID) {
        residuals.remove(playerId)
    }

    fun clearAll() {
        residuals.clear()
    }

    private fun valid(point: MovementPoint): Boolean =
        point.worldId.isNotBlank() && point.x.isFinite() && point.y.isFinite() && point.z.isFinite()

    private companion object {
        fun fixedTuning(maximumStepBlocks: Double): () -> MovementTuning {
            val tuning = MovementTuning(maximumStepBlocks, includeVertical = true)
            return { tuning }
        }
    }
}
