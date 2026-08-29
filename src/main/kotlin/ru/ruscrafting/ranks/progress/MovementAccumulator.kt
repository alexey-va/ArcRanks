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

class MovementAccumulator(private val maximumStepBlocks: Double) {
    private val residuals = ConcurrentHashMap<UUID, Double>()

    init {
        require(maximumStepBlocks.isFinite() && maximumStepBlocks > 0.0) {
            "Maximum movement step must be finite and positive"
        }
    }

    fun observe(playerId: UUID, from: MovementPoint, to: MovementPoint): Long {
        if (!valid(from) || !valid(to) || from.worldId != to.worldId) {
            residuals.remove(playerId)
            return 0
        }
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        if (!distance.isFinite() || distance > maximumStepBlocks) {
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

    private fun valid(point: MovementPoint): Boolean =
        point.worldId.isNotBlank() && point.x.isFinite() && point.y.isFinite() && point.z.isFinite()
}
