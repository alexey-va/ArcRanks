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
)

class RankSnapshotCache {
    private val snapshots = ConcurrentHashMap<UUID, RankPlayerSnapshot>()

    fun get(playerId: UUID): RankPlayerSnapshot? = snapshots[playerId]

    fun put(playerId: UUID, snapshot: RankPlayerSnapshot) {
        snapshots[playerId] = snapshot
    }

    fun remove(playerId: UUID) {
        snapshots.remove(playerId)
    }

    fun updatePerks(playerId: UUID, selection: PerkSelection) {
        snapshots.computeIfPresent(playerId) { _, snapshot -> snapshot.copy(activePerks = selection.active.toSet()) }
    }

    fun size(): Int = snapshots.size
}

class RankPlayerService(
    private val rankState: RankStateGateway,
    private val progress: ProgressRepository,
    private val evaluator: RankEvaluator,
    private val masteryThresholds: Map<SpecializationPath, MasteryThresholds>,
    private val availability: () -> PathAvailability,
    private val cache: RankSnapshotCache,
    private val perks: (UUID) -> CompletableFuture<PerkSelection>,
) {
    fun load(playerId: UUID): CompletableFuture<RankPlayerSnapshot> {
        val currentAvailability = availability()
        return rankState.load(playerId).thenCombine(progress.load(playerId)) { state, profile -> state to profile }
            .thenCombine(perks(playerId)) { (state, profile), selection ->
            val evaluation = (state as? RankState.Exact)?.let { evaluator.evaluate(it.rankId, profile.progress, currentAvailability) }
            RankPlayerSnapshot(
                rankState = state,
                profile = profile,
                evaluation = evaluation,
                mastery = SpecializationPath.entries.associateWith { path ->
                    MasteryEvaluator.level(profile, path, checkNotNull(masteryThresholds[path]))
                },
                availability = currentAvailability,
                activePerks = selection.active.toSet(),
            ).also { cache.put(playerId, it) }
        }
    }

    fun selectFocus(playerId: UUID, path: SpecializationPath): CompletableFuture<RankPlayerSnapshot> =
        progress.selectFocus(playerId, path).thenCompose { load(playerId) }

    fun cachedRank(playerId: UUID): RankId? =
        (cache.get(playerId)?.rankState as? RankState.Exact)?.rankId
}
