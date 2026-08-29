package ru.ruscrafting.ranks.progress

import ru.ruscrafting.ranks.domain.ProgressMetric
import java.util.UUID
import java.util.concurrent.CompletableFuture

sealed interface ProgressMutation {
    val metric: ProgressMetric

    data class Add(override val metric: ProgressMetric, val delta: Long) : ProgressMutation {
        init {
            require(delta > 0) { "Progress counter delta must be positive" }
        }
    }

    data class Maximum(override val metric: ProgressMetric, val value: Long) : ProgressMutation {
        init {
            require(value >= 0) { "Progress maximum must not be negative" }
        }
    }
}

class ProgressBuffer(
    private val maximumEntries: Int,
    private val writer: (UUID, List<ProgressMutation>) -> CompletableFuture<Unit>,
) {
    private val lock = Any()
    private val pending = mutableMapOf<UUID, MutableMap<ProgressMetric, ProgressMutation>>()
    private val inFlight = mutableMapOf<UUID, CompletableFuture<Unit>>()
    private val inFlightKeys = mutableSetOf<Pair<UUID, ProgressMetric>>()

    init {
        require(maximumEntries > 0) { "Progress buffer capacity must be positive" }
    }

    fun recordCounter(playerId: UUID, metric: ProgressMetric, delta: Long): Boolean {
        require(delta > 0) { "Progress counter delta must be positive" }
        return record(playerId, ProgressMutation.Add(metric, delta))
    }

    fun recordMaximum(playerId: UUID, metric: ProgressMetric, value: Long): Boolean {
        require(value >= 0) { "Progress maximum must not be negative" }
        return record(playerId, ProgressMutation.Maximum(metric, value))
    }

    fun flush(playerId: UUID): CompletableFuture<Unit> = synchronized(lock) {
        inFlight[playerId]?.let { return@synchronized it }
        val mutations = pending.remove(playerId)?.values?.sortedBy { it.metric.ordinal }.orEmpty()
        if (mutations.isEmpty()) return@synchronized CompletableFuture.completedFuture(Unit)

        mutations.forEach { inFlightKeys += playerId to it.metric }
        val future = runCatching { writer(playerId, mutations) }
            .getOrElse(CompletableFuture<Unit>::failedFuture)
        inFlight[playerId] = future
        future.whenComplete { _, failure ->
            synchronized(lock) {
                inFlight.remove(playerId)
                mutations.forEach { inFlightKeys -= playerId to it.metric }
                if (failure != null) mutations.forEach { merge(playerId, it) }
            }
        }
        future
    }

    fun flushAll(): CompletableFuture<Unit> {
        val players = synchronized(lock) { pending.keys.toList() }
        return CompletableFuture.allOf(*players.map(::flush).toTypedArray()).thenApply { Unit }
    }

    fun pendingCount(): Int = synchronized(lock) { pending.values.sumOf(Map<ProgressMetric, ProgressMutation>::size) }

    private fun record(playerId: UUID, mutation: ProgressMutation): Boolean = synchronized(lock) {
        val player = pending[playerId]
        val keyAlreadyOwned = player?.containsKey(mutation.metric) == true || (playerId to mutation.metric) in inFlightKeys
        val currentEntries = pending.values.sumOf(Map<ProgressMetric, ProgressMutation>::size) + inFlightKeys.size
        if (!keyAlreadyOwned && currentEntries >= maximumEntries) return@synchronized false
        merge(playerId, mutation)
        true
    }

    private fun merge(playerId: UUID, mutation: ProgressMutation) {
        val player = pending.getOrPut(playerId) { mutableMapOf() }
        val current = player[mutation.metric]
        player[mutation.metric] = when {
            current == null -> mutation
            current is ProgressMutation.Add && mutation is ProgressMutation.Add ->
                ProgressMutation.Add(mutation.metric, Math.addExact(current.delta, mutation.delta))
            current is ProgressMutation.Maximum && mutation is ProgressMutation.Maximum ->
                ProgressMutation.Maximum(mutation.metric, maxOf(current.value, mutation.value))
            else -> throw IllegalArgumentException("Metric ${mutation.metric} cannot mix counter and maximum aggregation")
        }
    }
}
