package ru.ruscrafting.ranks.progress

/**
 * Keeps elapsed server ticks across live period changes. A reload can therefore shorten or
 * lengthen a cadence without cancelling a nearly-complete interval.
 */
class DynamicTickCadence(private val quantumTicks: Long) {
    private var carriedTicks = 0L

    init {
        require(quantumTicks > 0L) { "Cadence quantum must be positive" }
    }

    @Synchronized
    fun advance(periodTicks: Long): Long {
        require(periodTicks > 0L && periodTicks % quantumTicks == 0L) {
            "Cadence period must be a positive multiple of its quantum"
        }
        carriedTicks = Math.addExact(carriedTicks, quantumTicks)
        if (carriedTicks < periodTicks) return 0L
        val completed = (carriedTicks / periodTicks) * periodTicks
        carriedTicks -= completed
        return completed
    }

    @Synchronized
    fun carriedTicks(): Long = carriedTicks
}
