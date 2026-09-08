package ru.ruscrafting.ranks.progress

import ru.ruscrafting.ranks.domain.ProgressMetric
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

// A pending batch can span every vanilla material/species during a storage outage.
private const val MAX_QUEST_OBJECTIVES = 4096
// The queued action list is capped globally at MAX_QUEST_OBJECTIVES, including batches
// handed to the writer but not settled yet. Keeping this fixed also bounds one metric's
// merged list when maximumEntries is configured above the action safety limit.

data class QuestAction(
    val sequence: Long,
    val objective: String,
    val amount: Long,
) {
    init {
        require(sequence > 0L) { "Quest action sequence must be positive" }
        require(objective.matches(QUEST_OBJECTIVE_PATTERN)) { "Invalid quest objective: $objective" }
        require(amount > 0L) { "Quest action amount must be positive" }
    }

    private companion object {
        val QUEST_OBJECTIVE_PATTERN = Regex("[a-z0-9_.:-]{1,96}")
    }
}

sealed interface ProgressMutation {
    val metric: ProgressMetric

    data class Add(
        override val metric: ProgressMetric,
        val delta: Long,
        val questDeltas: Map<String, Long> = emptyMap(),
        val questActions: List<QuestAction> = emptyList(),
    ) : ProgressMutation {
        init {
            require(questDeltas.size <= MAX_QUEST_OBJECTIVES && questDeltas.all { (key, value) -> key.matches(QUEST_OBJECTIVE_PATTERN) && value > 0 })
            require(questActions.size <= MAX_QUEST_OBJECTIVES)
            require(delta > 0) { "Progress counter delta must be positive" }
        }

        private companion object {
            val QUEST_OBJECTIVE_PATTERN = Regex("[a-z0-9_.:-]{1,96}")
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
        val add = mutation as? ProgressMutation.Add
        val generatedActionCount = add?.takeIf { it.questActions.isEmpty() }?.questDeltas?.size ?: 0
        val incomingActionCount = add?.questActions?.size?.takeIf { it > 0 } ?: generatedActionCount
        val maximumQuestActions = MAX_QUEST_OBJECTIVES.toLong()
        if (queuedQuestActions() > maximumQuestActions - incomingActionCount.toLong()) {
            rejectedMutations.incrementAndGet()
            return@synchronized false
        }
        val acceptedMutation: ProgressMutation
        val acceptedSequence: Long
        if (add != null && generatedActionCount > 0) {
            val actions = add.questDeltas.entries.map { (objective, amount) ->
                QuestAction(nextSequence(), objective, amount)
            }
            acceptedMutation = add.copy(questActions = actions)
            acceptedSequence = actions.last().sequence
        } else {
            acceptedMutation = mutation
            acceptedSequence = nextSequence()
        }
        acceptedSequences[playerId] = acceptedSequence
        merge(playerId, SequencedMutation(acceptedMutation, acceptedSequence))
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
            val flight = InFlightBatch(
                batch.maxOf(SequencedMutation::sequence),
                batch.sumOf { it.mutation.questActionCount() },
                settled,
            )
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
                        },
                        mergeQuestActions(current.mutation.questActions, mutation.questActions)),
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
        val questActionCount: Int,
        val completion: CompletableFuture<Unit>,
    )

    private fun queuedQuestActions(): Long =
        pending.values.sumOf { mutations -> mutations.values.sumOf { it.mutation.questActionCount().toLong() } } +
            inFlight.values.sumOf { it.questActionCount.toLong() }

    private fun nextSequence(): Long {
        sequence = Math.addExact(sequence, 1L)
        return sequence
    }

    private fun ProgressMutation.questActionCount(): Int =
        (this as? ProgressMutation.Add)?.questActions?.size ?: 0

    private fun mergeQuestActions(current: List<QuestAction>, incoming: List<QuestAction>): List<QuestAction> {
        if (current.isEmpty()) return incoming
        if (incoming.isEmpty()) return current
        val ordered = (current + incoming).sortedBy(QuestAction::sequence)
        val merged = ArrayList<QuestAction>(ordered.size)
        ordered.forEach { action ->
            val previous = merged.lastOrNull()
            if (previous != null && previous.objective == action.objective && previous.sequence + 1L == action.sequence) {
                merged[merged.lastIndex] = previous.copy(amount = Math.addExact(previous.amount, action.amount))
            } else {
                merged += action
            }
        }
        return merged
    }

    private companion object {
        fun fixedCapacity(maximumEntries: Int): () -> Int {
            require(maximumEntries > 0) { "Progress buffer capacity must be positive" }
            return { maximumEntries }
        }
    }
}
