package ru.ruscrafting.ranks.rankstate

import ru.ruscrafting.ranks.domain.RankCatalog
import ru.ruscrafting.ranks.domain.RankId
import java.util.UUID
import java.util.concurrent.CompletableFuture

sealed interface RankState {
    data class Exact(val rankId: RankId) : RankState

    data object Missing : RankState

    data class Conflict(val groups: List<String>) : RankState

    data class Unknown(val reason: String) : RankState
}

object RankStateClassifier {
    fun resolveGroups(directGroups: Collection<String>, catalog: RankCatalog): RankState {
        val groups = directGroups
            .filter { catalog.byGroup(it) != null }
            .distinct()
            .sortedBy { catalog.byGroup(it)?.order }
        return when (groups.size) {
            0 -> catalog.byGroup(IMPLICIT_LUCKPERMS_GROUP)?.let { RankState.Exact(it.id) } ?: RankState.Missing
            1 -> RankState.Exact(checkNotNull(catalog.byGroup(groups.single())).id)
            else -> RankState.Conflict(groups)
        }
    }

    private const val IMPLICIT_LUCKPERMS_GROUP = "default"
}

enum class RankMutationPlanResult {
    READY,
    MISSING_RANK,
    CONFLICTING_RANKS,
    STALE_EXPECTED_RANK,
}

data class RankMutationPlan(
    val result: RankMutationPlanResult,
    val removals: List<String>,
    val addition: String?,
    val preserved: List<String>,
) {
    companion object {
        fun create(
            directGroups: Collection<String>,
            catalog: RankCatalog,
            expected: RankId,
            target: RankId,
        ): RankMutationPlan {
            val state = RankStateClassifier.resolveGroups(directGroups, catalog)
            val result = when (state) {
                RankState.Missing -> RankMutationPlanResult.MISSING_RANK
                is RankState.Conflict -> RankMutationPlanResult.CONFLICTING_RANKS
                is RankState.Exact -> if (state.rankId == expected) RankMutationPlanResult.READY
                else RankMutationPlanResult.STALE_EXPECTED_RANK
                is RankState.Unknown -> RankMutationPlanResult.STALE_EXPECTED_RANK
            }
            if (result != RankMutationPlanResult.READY) {
                return RankMutationPlan(result, emptyList(), null, directGroups.toList())
            }
            val targetGroup = catalog.require(target).luckPermsGroup
            val removals = directGroups.filter { catalog.byGroup(it) != null }
            val preserved = directGroups.filterNot { it in removals }
            return RankMutationPlan(
                result = RankMutationPlanResult.READY,
                removals = removals,
                addition = targetGroup,
                preserved = preserved,
            )
        }
    }
}

enum class RankReplaceResult {
    APPLIED,
    MISSING_RANK,
    CONFLICTING_RANKS,
    STALE_EXPECTED_RANK,
    VERIFICATION_FAILED,
}

interface RankStateGateway {
    fun load(playerId: UUID): CompletableFuture<RankState>

    fun replaceExact(playerId: UUID, expected: RankId, target: RankId): CompletableFuture<RankReplaceResult>
}
