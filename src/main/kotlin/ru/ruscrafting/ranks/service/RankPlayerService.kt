package ru.ruscrafting.ranks.service

import ru.ruscrafting.ranks.domain.MasteryEvaluator
import ru.ruscrafting.ranks.domain.MasteryLevel
import ru.ruscrafting.ranks.domain.MasteryThresholds
import ru.ruscrafting.ranks.domain.PathAvailability
import ru.ruscrafting.ranks.domain.PlayerProgressProfile
import ru.ruscrafting.ranks.domain.RankEvaluation
import ru.ruscrafting.ranks.domain.RankEvaluator
import ru.ruscrafting.ranks.domain.RankId
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.perk.PerkId
import ru.ruscrafting.ranks.perk.PerkSelection
import ru.ruscrafting.ranks.rankstate.RankState
import ru.ruscrafting.ranks.rankstate.RankStateGateway
import ru.ruscrafting.ranks.storage.ProgressRepository
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

data class RankPlayerSnapshot(
    val rankState: RankState,
    val profile: PlayerProgressProfile,
    val evaluation: RankEvaluation?,
    val mastery: Map<SpecializationPath, MasteryLevel>,
    val availability: PathAvailability,
    val activePerks: Set<PerkId>,
    val perkSlots: Map<Int, PerkId> = activePerks.withIndex().associate { (index, perkId) -> index + 1 to perkId },
)

data class RankPlayerEvaluationConfiguration(
    val evaluator: RankEvaluator,
    val masteryThresholds: Map<SpecializationPath, MasteryThresholds>,
)

data class PerkProgressContext(
    val profile: PlayerProgressProfile,
    val activePerks: Set<PerkId>,
)

class RankSnapshotCache {
    private val snapshots = ConcurrentHashMap<UUID, RankPlayerSnapshot>()
    private val perkProgress = ConcurrentHashMap<UUID, PerkProgressContext>()
    private val generationMonitor = Any()
    private var globalGeneration: Any = Any()
    private val playerGenerations = mutableMapOf<UUID, Any>()

    fun get(playerId: UUID): RankPlayerSnapshot? = snapshots[playerId]

    fun put(playerId: UUID, snapshot: RankPlayerSnapshot) {
        synchronized(generationMonitor) {
            playerGenerations[playerId] = Any()
            snapshots[playerId] = snapshot
            perkProgress[playerId] = snapshot.perkProgressContext()
        }
    }

    fun perkProgress(playerId: UUID): PerkProgressContext? = perkProgress[playerId]

    /** Captures the cache generation that an asynchronous player load belongs to. */
    internal fun captureToken(playerId: UUID): WriteToken = synchronized(generationMonitor) {
        WriteToken(
            owner = this,
            playerId = playerId,
            globalGeneration = globalGeneration,
            playerGeneration = playerGenerations.getOrPut(playerId) { Any() },
        )
    }

    /** Writes only while neither this player nor the whole cache has been invalidated. */
    internal fun putIfCurrent(playerId: UUID, token: WriteToken, snapshot: RankPlayerSnapshot): Boolean =
        synchronized(generationMonitor) {
            if (
                token.owner !== this ||
                token.playerId != playerId ||
                token.globalGeneration !== globalGeneration ||
                playerGenerations[playerId] !== token.playerGeneration
            ) {
                return@synchronized false
            }
            snapshots[playerId] = snapshot
            perkProgress[playerId] = snapshot.perkProgressContext()
            true
        }

    internal class WriteToken internal constructor(
        internal val owner: RankSnapshotCache,
        internal val playerId: UUID,
        internal val globalGeneration: Any,
        internal val playerGeneration: Any,
    )

    fun clear() {
        synchronized(generationMonitor) {
            globalGeneration = Any()
            playerGenerations.clear()
            snapshots.clear()
            perkProgress.clear()
        }
    }

    /** Invalidates derived rank snapshots while preserving last-known perk/progress inputs. */
    fun invalidateSnapshots() {
        synchronized(generationMonitor) {
            globalGeneration = Any()
            playerGenerations.clear()
            snapshots.clear()
        }
    }

    fun remove(playerId: UUID) {
        synchronized(generationMonitor) {
            playerGenerations.remove(playerId)
            snapshots.remove(playerId)
            perkProgress.remove(playerId)
        }
    }

    fun updatePerks(playerId: UUID, selection: PerkSelection) {
        synchronized(generationMonitor) {
            // A selection mutation wins over every load that started before it, including
            // reload warmups where the derived snapshot has already been invalidated.
            playerGenerations[playerId] = Any()
            perkProgress.computeIfPresent(playerId) { _, context ->
                context.copy(activePerks = selection.active.toSet())
            }
            snapshots[playerId]?.let { snapshot ->
                val updated = snapshot.copy(activePerks = selection.active.toSet(), perkSlots = selection.slots)
                snapshots[playerId] = updated
                perkProgress[playerId] = updated.perkProgressContext()
            }
        }
    }

    fun size(): Int = snapshots.size
}

private fun RankPlayerSnapshot.perkProgressContext(): PerkProgressContext =
    PerkProgressContext(profile, activePerks.toSet())

class RankPlayerService(
    private val rankState: RankStateGateway,
    private val progress: ProgressRepository,
    private val evaluationConfiguration: () -> RankPlayerEvaluationConfiguration,
    private val availability: () -> PathAvailability,
    private val cache: RankSnapshotCache,
    private val perks: (UUID) -> CompletableFuture<PerkSelection>,
) {
    constructor(
        rankState: RankStateGateway,
        progress: ProgressRepository,
        evaluator: RankEvaluator,
        masteryThresholds: Map<SpecializationPath, MasteryThresholds>,
        availability: () -> PathAvailability,
        cache: RankSnapshotCache,
        perks: (UUID) -> CompletableFuture<PerkSelection>,
    ) : this(
        rankState = rankState,
        progress = progress,
        evaluationConfiguration = { RankPlayerEvaluationConfiguration(evaluator, masteryThresholds) },
        availability = availability,
        cache = cache,
        perks = perks,
    )

    fun load(playerId: UUID): CompletableFuture<RankPlayerSnapshot> {
        val cacheToken = cache.captureToken(playerId)
        val currentEvaluationConfiguration = evaluationConfiguration()
        return load(playerId, cacheToken, currentEvaluationConfiguration)
    }

    private fun load(
        playerId: UUID,
        cacheToken: RankSnapshotCache.WriteToken,
        evaluationConfiguration: RankPlayerEvaluationConfiguration,
    ): CompletableFuture<RankPlayerSnapshot> {
        val currentAvailability = availability()
        return rankState.load(playerId).thenCombine(progress.load(playerId)) { state, profile -> state to profile }
            .thenCombine(perks(playerId)) { (state, profile), selection ->
            val evaluation = (state as? RankState.Exact)?.let {
                evaluationConfiguration.evaluator.evaluate(it.rankId, profile.progress, currentAvailability)
            }
            RankPlayerSnapshot(
                rankState = state,
                profile = profile,
                evaluation = evaluation,
                mastery = SpecializationPath.entries.associateWith { path ->
                    MasteryEvaluator.level(
                        profile,
                        path,
                        checkNotNull(evaluationConfiguration.masteryThresholds[path]),
                    )
                },
                availability = currentAvailability,
                activePerks = selection.active.toSet(),
                perkSlots = selection.slots,
            ).also { cache.putIfCurrent(playerId, cacheToken, it) }
        }
    }

    fun selectFocus(playerId: UUID, path: SpecializationPath): CompletableFuture<RankPlayerSnapshot> {
        val cacheToken = cache.captureToken(playerId)
        val currentEvaluationConfiguration = evaluationConfiguration()
        return progress.selectFocus(playerId, path).thenCompose {
            load(playerId, cacheToken, currentEvaluationConfiguration)
        }
    }

    fun cachedRank(playerId: UUID): RankId? =
        (cache.get(playerId)?.rankState as? RankState.Exact)?.rankId
}
