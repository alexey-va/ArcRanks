package ru.ruscrafting.ranks.reward

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.observability.StructuredDebugLine
import ru.arc.onetime.OneTimeUseAbandonResult
import ru.arc.onetime.OneTimeUseClaim
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseCommitResult
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.onetime.OneTimeUseReleaseResult
import ru.arc.onetime.OneTimeUseScope
import ru.ruscrafting.ranks.contract.ContractRewardApplyResult
import ru.ruscrafting.ranks.contract.ContractRewardComponent
import ru.ruscrafting.ranks.contract.ContractRewardDeliveryResult
import ru.ruscrafting.ranks.contract.ContractRewardProvider
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/** Delivers rank rewards through one durable, cross-server state machine. */
class RankRewardDeliveryService(
    private val repository: RankRewardRepository,
    private val ledger: OneTimeUseLedger,
    private val provider: ContractRewardProvider,
    private val tasks: LifecycleTaskScope,
    private val logger: Logger,
    private val onGranted: (Player, RankReward) -> Unit = { _, _ -> },
) : Listener {
    private val activePlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val requestedAgain = ConcurrentHashMap.newKeySet<UUID>()
    private val scope = OneTimeUseScope.parse("network")
    private val debug = StructuredDebugLine("ARCRANKS_RANK_REWARD")

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        deliverPending(event.player)
    }

    fun deliverPending(player: Player) {
        if (!activePlayers.add(player.uniqueId)) {
            requestedAgain.add(player.uniqueId)
            return
        }
        repository.pendingRewards(player.uniqueId).whenCompleteSync(tasks) { pending, failure ->
            if (failure != null) {
                logger.log(Level.WARNING, "Could not load pending rank rewards", failure)
                requestedAgain.remove(player.uniqueId)
                activePlayers.remove(player.uniqueId)
            } else {
                deliverPendingAt(player, pending.orEmpty(), 0)
            }
        }
    }

    fun deliver(player: Player, reward: RankReward): CompletableFuture<ContractRewardDeliveryResult> {
        if (!activePlayers.add(player.uniqueId)) {
            return CompletableFuture.completedFuture(ContractRewardDeliveryResult.PENDING)
        }
        val future = CompletableFuture<ContractRewardDeliveryResult>()
        deliverComponent(player, reward, reward.components, 0, future)
        future.whenComplete { _, _ -> activePlayers.remove(player.uniqueId) }
        return future
    }

    private fun deliverPendingAt(player: Player, rewards: List<RankReward>, index: Int) {
        if (!player.isOnline || index >= rewards.size) {
            activePlayers.remove(player.uniqueId)
            if (requestedAgain.remove(player.uniqueId) && player.isOnline) deliverPending(player)
            return
        }
        val completion = CompletableFuture<ContractRewardDeliveryResult>()
        deliverComponent(player, rewards[index], rewards[index].components, 0, completion)
        completion.whenCompleteSync(tasks) { _, _ -> deliverPendingAt(player, rewards, index + 1) }
    }

    private fun deliverComponent(
        player: Player,
        reward: RankReward,
        components: List<ContractRewardComponent>,
        index: Int,
        completion: CompletableFuture<ContractRewardDeliveryResult>,
    ) {
        if (index >= components.size) {
            repository.markRewardGranted(player.uniqueId, reward.id).whenCompleteSync(tasks) { marked, failure ->
                if (failure == null && marked == true) {
                    try {
                        onGranted(player, reward)
                    } catch (callbackFailure: Throwable) {
                        logger.log(
                            Level.SEVERE,
                            debug.line("namespace" to reward.namespace, "reward" to reward.id, "outcome" to "granted_callback_failed"),
                            callbackFailure,
                        )
                    }
                    completion.complete(ContractRewardDeliveryResult.GRANTED)
                } else {
                    logger.log(Level.SEVERE, debug.line("namespace" to reward.namespace, "reward" to reward.id, "outcome" to "state_unknown"), failure)
                    completion.complete(ContractRewardDeliveryResult.RECOVERY)
                }
            }
            return
        }
        if (!player.isOnline) {
            completion.complete(ContractRewardDeliveryResult.PENDING)
            return
        }
        val component = components[index]
        val identity = reward.identity(component)
        ledger.claim(
            OneTimeUseClaimRequest(identity, identity.useId, player.uniqueId, scope),
        ).whenCompleteSync(tasks) { result, failure ->
            if (failure != null) {
                logger.log(Level.WARNING, debug.line("namespace" to reward.namespace, "reward" to reward.id, "component" to component.key, "outcome" to "claim_unknown"), failure)
                completion.complete(ContractRewardDeliveryResult.PENDING)
                return@whenCompleteSync
            }
            when (result) {
                is OneTimeUseClaimResult.Acquired -> if (result.claim.newlyCreated) {
                    applyComponent(player, reward, components, index, result.claim, completion)
                } else {
                    abandonForRecovery(player, reward, component, result.claim, "claim_recovered", completion) {
                        deliverComponent(player, reward, components, index + 1, completion)
                    }
                }
                OneTimeUseClaimResult.AlreadyConsumed -> deliverComponent(player, reward, components, index + 1, completion)
                OneTimeUseClaimResult.Busy -> completion.complete(ContractRewardDeliveryResult.PENDING)
                OneTimeUseClaimResult.IdentityConflict,
                OneTimeUseClaimResult.Missing,
                null,
                -> markRecovery(player, reward, component, "claim_conflict", completion)
            }
        }
    }

    private fun applyComponent(
        player: Player,
        reward: RankReward,
        components: List<ContractRewardComponent>,
        index: Int,
        claim: OneTimeUseClaim,
        completion: CompletableFuture<ContractRewardDeliveryResult>,
    ) {
        val component = components[index]
        if (!player.isOnline) {
            release(player, reward, component, claim, completion)
            return
        }
        val auditToken = ArcAuditRewardBridge.mark(
            player.uniqueId,
            reward.namespace,
            component,
            claim.identity.useId.toString(),
        )
        val result = try {
            provider.apply(player, component)
        } catch (failure: Throwable) {
            ArcAuditRewardBridge.cancel(player.uniqueId, auditToken)
            logger.log(Level.SEVERE, debug.line("namespace" to reward.namespace, "reward" to reward.id, "component" to component.key, "outcome" to "effect_unknown"), failure)
            abandonForRecovery(player, reward, component, claim, "effect_unknown", completion) {
                deliverComponent(player, reward, components, index + 1, completion)
            }
            return
        }
        if (result == ContractRewardApplyResult.REJECTED) {
            ArcAuditRewardBridge.cancel(player.uniqueId, auditToken)
            release(player, reward, component, claim, completion)
            return
        }
        ledger.commit(claim).whenCompleteSync(tasks) { committed, failure ->
            if (failure == null && committed in setOf(OneTimeUseCommitResult.COMMITTED, OneTimeUseCommitResult.ALREADY_COMMITTED)) {
                deliverComponent(player, reward, components, index + 1, completion)
            } else {
                logger.log(Level.SEVERE, debug.line("namespace" to reward.namespace, "reward" to reward.id, "component" to component.key, "outcome" to "commit_unknown"), failure)
                abandonForRecovery(player, reward, component, claim, "commit_unknown", completion) {
                    deliverComponent(player, reward, components, index + 1, completion)
                }
            }
        }
    }

    private fun release(
        player: Player,
        reward: RankReward,
        component: ContractRewardComponent,
        claim: OneTimeUseClaim,
        completion: CompletableFuture<ContractRewardDeliveryResult>,
    ) {
        ledger.release(claim).whenCompleteSync(tasks) { released, failure ->
            if (failure == null && released in setOf(OneTimeUseReleaseResult.RELEASED, OneTimeUseReleaseResult.ALREADY_RELEASED)) {
                completion.complete(ContractRewardDeliveryResult.PENDING)
            } else {
                markRecovery(player, reward, component, "release_unknown", completion)
            }
        }
    }

    private fun abandonForRecovery(
        player: Player,
        reward: RankReward,
        component: ContractRewardComponent,
        claim: OneTimeUseClaim,
        code: String,
        completion: CompletableFuture<ContractRewardDeliveryResult>,
        onAlreadyCommitted: () -> Unit,
    ) {
        ledger.abandon(claim).whenCompleteSync(tasks) { abandoned, failure ->
            if (failure == null && abandoned == OneTimeUseAbandonResult.ALREADY_COMMITTED) {
                onAlreadyCommitted()
            } else {
                markRecovery(player, reward, component, code, completion)
            }
        }
    }

    private fun markRecovery(
        player: Player,
        reward: RankReward,
        component: ContractRewardComponent,
        code: String,
        completion: CompletableFuture<ContractRewardDeliveryResult>,
    ) {
        repository.markRewardRecovery(player.uniqueId, reward.id, code).whenCompleteSync(tasks) { _, failure ->
            if (failure != null) logger.log(Level.SEVERE, "Could not retain rank reward for recovery", failure)
            logger.warning(debug.line("namespace" to reward.namespace, "reward" to reward.id, "component" to component.key, "outcome" to "recovery", "code" to code))
            completion.complete(ContractRewardDeliveryResult.RECOVERY)
        }
    }
}

internal object ArcAuditRewardBridge {
    private val markMethod = lazy {
        Class.forName("ru.arc.audit.ExternalEconomyAuditBridge").getMethod(
            "markExternalReward",
            UUID::class.java,
            String::class.java,
            String::class.java,
            Double::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
        )
    }
    private val cancelMethod = lazy {
        Class.forName("ru.arc.audit.ExternalEconomyAuditBridge").getMethod("cancel", UUID::class.java, String::class.java)
    }

    fun mark(
        playerId: UUID,
        namespace: String,
        component: ContractRewardComponent,
        rewardId: String,
    ): String? {
        val currency = when (component) {
            is ContractRewardComponent.Money -> "vault"
            is ContractRewardComponent.Tokens -> component.currency
            is ContractRewardComponent.Item -> return null
        }
        val reason = when (namespace) {
            RankReward.CONTRACT_NAMESPACE -> "contract_reward"
            "daily" -> "daily_reward"
            else -> "${namespace}_reward"
        }
        return runCatching {
            markMethod.value.invoke(null, playerId, "ranks", reason, component.amount.toDouble(), currency, rewardId) as String?
        }.getOrNull()
    }

    fun cancel(playerId: UUID, token: String?) {
        if (token == null) return
        runCatching { cancelMethod.value.invoke(null, playerId, token) }
    }
}
