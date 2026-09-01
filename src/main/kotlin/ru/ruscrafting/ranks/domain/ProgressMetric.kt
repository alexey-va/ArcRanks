package ru.ruscrafting.ranks.domain

enum class ProgressMetric {
    ACTIVE_MINUTES,
    CROPS_HARVESTED,
    PRODUCTION_ACTIONS,
    WEALTH_PEAK,
    TRADE_ACTIONS,
    TRAVEL_BLOCKS,
    BLOCKS_PLACED,
    COMMUNITY_MINUTES,
}

enum class SpecializationPath(
    val metric: ProgressMetric,
    val alternativeMetrics: Set<ProgressMetric> = emptySet(),
) {
    FARMING(ProgressMetric.CROPS_HARVESTED),
    INDUSTRY(ProgressMetric.PRODUCTION_ACTIONS),
    TRADE(ProgressMetric.WEALTH_PEAK, setOf(ProgressMetric.TRADE_ACTIONS)),
    EXPLORATION(ProgressMetric.TRAVEL_BLOCKS),
    BUILDING(ProgressMetric.BLOCKS_PLACED),
    COMMUNITY(ProgressMetric.COMMUNITY_MINUTES),
    ;

    fun owns(metric: ProgressMetric): Boolean = metric == this.metric || metric in alternativeMetrics

    /** Alternatives are independent routes to the same goal, never additive double progress. */
    fun progressValue(progress: ProgressSnapshot): Long =
        (sequenceOf(metric) + alternativeMetrics.asSequence()).maxOf(progress::value)
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
