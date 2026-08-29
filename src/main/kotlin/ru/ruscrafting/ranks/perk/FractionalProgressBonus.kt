package ru.ruscrafting.ranks.perk

import ru.ruscrafting.ranks.domain.ProgressMetric
import java.util.UUID

class FractionalProgressBonus {
    private data class Key(val playerId: UUID, val metric: ProgressMetric)

    private val lock = Any()
    private val remainders = mutableMapOf<Key, Long>()

    fun apply(
        playerId: UUID,
        metric: ProgressMetric,
        delta: Long,
        basisPoints: Int,
    ): Long {
        require(delta > 0) { "Progress delta must be positive" }
        require(basisPoints in 0..MAX_BASIS_POINTS) { "Progress bonus must be between 0 and 5000 basis points" }
        if (basisPoints == 0) return 0
        require(metric != ProgressMetric.WEALTH_PEAK) { "High-water metrics cannot receive a fractional bonus" }
        val numerator = Math.multiplyExact(delta, basisPoints.toLong())
        return synchronized(lock) {
            val key = Key(playerId, metric)
            val total = Math.addExact(remainders[key] ?: 0, numerator)
            val bonus = total / BASIS_POINT_SCALE
            val remainder = total % BASIS_POINT_SCALE
            if (remainder == 0L) remainders.remove(key) else remainders[key] = remainder
            bonus
        }
    }

    fun clear(playerId: UUID) {
        synchronized(lock) { remainders.keys.removeIf { it.playerId == playerId } }
    }

    private companion object {
        const val BASIS_POINT_SCALE = 10_000L
        const val MAX_BASIS_POINTS = 5_000
    }
}
