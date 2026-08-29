package ru.ruscrafting.ranks.domain

enum class ProgressMetric {
    ACTIVE_MINUTES,
    CROPS_HARVESTED,
    PRODUCTION_ACTIONS,
    WEALTH_PEAK,
    TRAVEL_BLOCKS,
    BLOCKS_PLACED,
    COMMUNITY_MINUTES,
}

enum class SpecializationPath(val metric: ProgressMetric) {
    FARMING(ProgressMetric.CROPS_HARVESTED),
    INDUSTRY(ProgressMetric.PRODUCTION_ACTIONS),
    TRADE(ProgressMetric.WEALTH_PEAK),
    EXPLORATION(ProgressMetric.TRAVEL_BLOCKS),
    BUILDING(ProgressMetric.BLOCKS_PLACED),
    COMMUNITY(ProgressMetric.COMMUNITY_MINUTES),
}

data class ProgressSnapshot(private val values: Map<ProgressMetric, Long>) {
    init {
        require(values.values.none { it < 0 }) { "Progress values must not be negative" }
    }

    fun value(metric: ProgressMetric): Long = values[metric] ?: 0L

    fun asMap(): Map<ProgressMetric, Long> = values.toMap()

    companion object {
        val EMPTY = ProgressSnapshot(emptyMap())
    }
}

data class PathAvailability(val unavailable: Set<SpecializationPath>) {
    fun isAvailable(path: SpecializationPath): Boolean = path !in unavailable

    companion object {
        fun allAvailable(): PathAvailability = PathAvailability(emptySet())
    }
}
