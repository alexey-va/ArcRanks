package ru.ruscrafting.ranks.progress

import ru.ruscrafting.ranks.domain.ProgressMetric
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

sealed interface ProgressMutation {
    val metric: ProgressMetric

    data class Add(override val metric: ProgressMetric, val delta: Long, val questDeltas: Map<String, Long> = emptyMap()) : ProgressMutation {
        init {
            require(questDeltas.size <= 32 && questDeltas.all { (key, value) -> key.matches(Regex("[a-z0-9_.:-]{1,96}")) && value > 0 })
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
    private val maximumEntriesProvider: () -> Int,
    private val writer: (UUID, List<ProgressMutation>) -> CompletableFuture<Unit>,
) {
    constructor(
        maximumEntries: Int,
        writer: (UUID, List<ProgressMutation>) -> CompletableFuture<Unit>,
    ) : this(fixedCapacity(maximumEntries), writer)

    private val lock = Any()
    private val pending = mutableMapOf<UUID, MutableMap<ProgressMetric, SequencedMutation>>()
    private val inFlight = mutableMapOf<UUID, InFlightBatch>()
    private val inFlightKeys = mutableSetOf<Pair<UUID, ProgressMetric>>()
    private val acceptedSequences = mutableMapOf<UUID, Long>()
    private val persistedSequences = mutableMapOf<UUID, Long>()
    private val rejectedMutations = AtomicLong()
    private var sequence = 0L

    fun recordCounter(playerId: UUID, metric: ProgressMetric, delta: Long, questDeltas: Map<String, Long> = emptyMap()): Boolean {
        require(delta > 0) { "Progress counter delta must be positive" }
        return record(playerId, ProgressMutation.Add(metric, delta, questDeltas))
    }

    fun recordMaximum(playerId: UUID, metric: ProgressMetric, value: Long): Boolean {
        require(value >= 0) { "Progress maximum must not be negative" }
        return record(playerId, ProgressMutation.Maximum(metric, value))
    }

    /**
     * Persists every mutation accepted before this call, including mutations queued while an
     * earlier batch is already in flight. Mutations accepted afterwards do not extend the barrier.
     */
    fun flush(playerId: UUID): CompletableFuture<Unit> {
        val target = synchronized(lock) { acceptedSequences[playerId] }
            ?: return CompletableFuture.completedFuture(Unit)
        return flushThrough(playerId, target)
    }

    fun flushAll(): CompletableFuture<Unit> {
        val targets = synchronized(lock) {
            acceptedSequences.filter { (playerId, accepted) ->
                accepted > persistedSequences.getOrDefault(playerId, 0L)
            }
        }
        return CompletableFuture.allOf(
            *targets.map { (playerId, target) -> flushThrough(playerId, target) }.toTypedArray(),
        ).thenApply { Unit }
    }

    fun pendingCount(): Int = synchronized(lock) { pending.values.sumOf(Map<ProgressMetric, SequencedMutation>::size) }

    private fun record(playerId: UUID, mutation: ProgressMutation): Boolean = synchronized(lock) {
        val player = pending[playerId]
        val keyAlreadyOwned = player?.containsKey(mutation.metric) == true || (playerId to mutation.metric) in inFlightKeys
        val currentEntries = buildSet {
            pending.forEach { (pendingPlayerId, mutations) ->
                mutations.keys.forEach { metric -> add(pendingPlayerId to metric) }
            }
            addAll(inFlightKeys)
        }.size
        val maximumEntries = maximumEntriesProvider().also {
            require(it > 0) { "Progress buffer capacity must be positive" }
        }
        if (!keyAlreadyOwned && currentEntries >= maximumEntries) {
            rejectedMutations.incrementAndGet()
            return@synchronized false
        }
        sequence = Math.addExact(sequence, 1L)
        acceptedSequences[playerId] = sequence
        merge(playerId, SequencedMutation(mutation, sequence))
        true
    }

    fun rejectedCount(): Long = rejectedMutations.get()

    private fun flushThrough(playerId: UUID, targetSequence: Long): CompletableFuture<Unit> {
        val batchCompletion = synchronized(lock) {
            if (persistedSequences.getOrDefault(playerId, 0L) >= targetSequence) {
                return CompletableFuture.completedFuture(Unit)
            }
            inFlight[playerId]?.let { return@synchronized it.completion }

            val batch = pending.remove(playerId)?.values?.sortedBy { it.mutation.metric.ordinal }.orEmpty()
            if (batch.isEmpty()) {
                return CompletableFuture.failedFuture(
                    IllegalStateException("Progress buffer lost accepted sequence $targetSequence for $playerId"),
                )
            }
            batch.forEach { inFlightKeys += playerId to it.mutation.metric }
            val writerFuture = runCatching { writer(playerId, batch.map(SequencedMutation::mutation)) }
                .getOrElse(CompletableFuture<Unit>::failedFuture)
            val settled = CompletableFuture<Unit>()
            val flight = InFlightBatch(batch.maxOf(SequencedMutation::sequence), settled)
            inFlight[playerId] = flight
            writerFuture.whenComplete { _, failure ->
                synchronized(lock) {
                    if (inFlight[playerId] === flight) inFlight.remove(playerId)
                    batch.forEach { inFlightKeys -= playerId to it.mutation.metric }
                    if (failure == null) {
                        persistedSequences[playerId] = maxOf(
                            persistedSequences.getOrDefault(playerId, 0L),
                            flight.maximumSequence,
                        )
                    } else {
                        batch.forEach { merge(playerId, it) }
                    }
                }
                if (failure == null) settled.complete(Unit) else settled.completeExceptionally(failure)
            }
            settled
        }
        return batchCompletion.thenCompose { flushThrough(playerId, targetSequence) }
    }

    private fun merge(playerId: UUID, incoming: SequencedMutation) {
        val player = pending.getOrPut(playerId) { mutableMapOf() }
        val mutation = incoming.mutation
        val current = player[mutation.metric]
        player[mutation.metric] = when {
            current == null -> incoming
            current.mutation is ProgressMutation.Add && mutation is ProgressMutation.Add ->
                SequencedMutation(
                    ProgressMutation.Add(mutation.metric, Math.addExact(current.mutation.delta, mutation.delta),
                        (current.mutation.questDeltas.keys + mutation.questDeltas.keys).associateWith { key ->
                            Math.addExact(current.mutation.questDeltas[key] ?: 0, mutation.questDeltas[key] ?: 0)
                        }),
                    maxOf(current.sequence, incoming.sequence),
                )
            current.mutation is ProgressMutation.Maximum && mutation is ProgressMutation.Maximum ->
                SequencedMutation(
                    ProgressMutation.Maximum(mutation.metric, maxOf(current.mutation.value, mutation.value)),
                    maxOf(current.sequence, incoming.sequence),
                )
            else -> throw IllegalArgumentException("Metric ${mutation.metric} cannot mix counter and maximum aggregation")
        }
    }

    private data class SequencedMutation(val mutation: ProgressMutation, val sequence: Long)

    private data class InFlightBatch(
        val maximumSequence: Long,
        val completion: CompletableFuture<Unit>,
    )

    private companion object {
        fun fixedCapacity(maximumEntries: Int): () -> Int {
            require(maximumEntries > 0) { "Progress buffer capacity must be positive" }
            return { maximumEntries }
        }
    }
}
