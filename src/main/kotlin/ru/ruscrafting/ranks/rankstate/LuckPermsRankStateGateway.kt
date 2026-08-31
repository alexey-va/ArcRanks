package ru.ruscrafting.ranks.rankstate

import net.luckperms.api.LuckPerms
import net.luckperms.api.node.types.InheritanceNode
import ru.ruscrafting.ranks.domain.RankCatalog
import ru.ruscrafting.ranks.domain.RankId
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

class LuckPermsRankStateGateway(
    private val luckPerms: LuckPerms,
    private val catalog: RankCatalog,
) : RankStateGateway {
    override fun load(playerId: UUID): CompletableFuture<RankState> =
        luckPerms.userManager.loadUser(playerId)
            .thenApply { user -> RankStateClassifier.resolveGroups(user.permanentGlobalGroups(), catalog) }
            .exceptionally { failure -> RankState.Unknown(failure.cause?.javaClass?.simpleName ?: failure.javaClass.simpleName) }

    override fun replaceExact(
        playerId: UUID,
        expected: RankId,
        target: RankId,
    ): CompletableFuture<RankReplaceResult> {
        catalog.require(expected)
        catalog.require(target)
        val observed = AtomicReference(RankMutationPlanResult.READY)
        return luckPerms.userManager.modifyUser(playerId) { user ->
            val plan = RankMutationPlan.create(user.permanentGlobalGroups(), catalog, expected, target)
            observed.set(plan.result)
            if (plan.result == RankMutationPlanResult.READY) {
                user.data().clear { node ->
                    node is InheritanceNode && node.isPermanentGlobal() && node.groupName in plan.removals
                }
                user.data().add(InheritanceNode.builder(checkNotNull(plan.addition)).build())
            }
        }.thenCompose {
            when (observed.get()) {
                RankMutationPlanResult.READY -> load(playerId).thenApply { state ->
                    if (state == RankState.Exact(target)) RankReplaceResult.APPLIED
                    else RankReplaceResult.VERIFICATION_FAILED
                }
                RankMutationPlanResult.MISSING_RANK -> completed(RankReplaceResult.MISSING_RANK)
                RankMutationPlanResult.CONFLICTING_RANKS -> completed(RankReplaceResult.CONFLICTING_RANKS)
                RankMutationPlanResult.STALE_EXPECTED_RANK -> completed(RankReplaceResult.STALE_EXPECTED_RANK)
            }
        }
    }

    private fun completed(result: RankReplaceResult): CompletableFuture<RankReplaceResult> =
        CompletableFuture.completedFuture(result)

    private fun net.luckperms.api.model.user.User.permanentGlobalGroups(): List<String> =
        data().toCollection()
            .filterIsInstance<InheritanceNode>()
            .filter(InheritanceNode::isPermanentGlobal)
            .map(InheritanceNode::getGroupName)
}

internal fun InheritanceNode.isPermanentGlobal(): Boolean = value && !hasExpiry() && contexts.isEmpty
