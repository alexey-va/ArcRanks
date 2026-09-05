package ru.ruscrafting.ranks.progress

import java.time.Instant
import java.util.UUID

/** Main-thread anti-repeat gate shared by manual placements and committed tools. */
class BuildingProgressGate(private val maximumPositions: Int = 100_000) {
    init { require(maximumPositions > 0) }
    private data class Position(val world: UUID, val x: Int, val y: Int, val z: Int)
    private val credited = linkedMapOf<Position, Instant>()

    fun release(world: UUID, x: Int, y: Int, z: Int, creditedAt: Instant) {
        credited.remove(Position(world, x, y, z), creditedAt)
    }

    fun credit(world: UUID, x: Int, y: Int, z: Int, now: Instant, repeatWindowSeconds: Long): Boolean {
        require(repeatWindowSeconds > 0)
        val expiredBefore = now.minusSeconds(repeatWindowSeconds)
        val iterator = credited.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.isAfter(expiredBefore)) break
            iterator.remove()
        }
        val position = Position(world, x, y, z)
        if (position in credited) return false
        // Bounded local anti-spam history; durable operation IDs remain owned by RankProgressApi.
        if (credited.size >= maximumPositions) credited.entries.iterator().run { next(); remove() }
        credited[position] = now
        return true
    }
}
