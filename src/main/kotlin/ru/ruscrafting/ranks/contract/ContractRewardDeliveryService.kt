package ru.ruscrafting.ranks.contract

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
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.onetime.OneTimeUseIdentity
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.onetime.OneTimeUseReleaseResult
import ru.arc.onetime.OneTimeUseScope
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

enum class ContractRewardDeliveryResult {
    GRANTED,
    PENDING,
    RECOVERY,
}

/** Delivers every contract reward component once across the whole network. */
class ContractRewardDeliveryService(
    private val repository: ContractRepository,
    private val ledger: OneTimeUseLedger,
    private val provider: ContractRewardProvider,
    private val tasks: LifecycleTaskScope,
    private val logger: Logger,
) : Listener {
    private val activePlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val scope = OneTimeUseScope.parse("network")
    private val debug = StructuredDebugLine("ARCRANKS_CONTRACT_REWARD")

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        deliverPending(event.player)
    }

    fun deliverPending(player: Player) {
        if (!activePlayers.add(player.uniqueId)) return
        repository.pendingRewards(player.uniqueId).whenCompleteSync(tasks) { pending, failure ->
            if (failure != null) {
                logger.log(Level.WARNING, "Could not load pending contract rewards", failure)
                activePlayers.remove(player.uniqueId)
            } else {
                deliverPendingAt(player, pending.orEmpty(), 0)
            }
        }
    }

    fun deliver(player: Player, contract: ActiveContract): CompletableFuture<ContractRewardDeliveryResult> {
        if (!activePlayers.add(player.uniqueId)) {
            return CompletableFuture.completedFuture(ContractRewardDeliveryResult.PENDING)
        }
        val future = CompletableFuture<ContractRewardDeliveryResult>()
        deliverComponent(player, contract, contract.bonusReward.components(), 0, future)
        future.whenComplete { _, _ -> activePlayers.remove(player.uniqueId) }
        return future
    }

    private fun deliverPendingAt(player: Player, contracts: List<ActiveContract>, index: Int) {
        if (!player.isOnline || index >= contracts.size) {
            activePlayers.remove(player.uniqueId)
            return
        }
        val completion = CompletableFuture<ContractRewardDeliveryResult>()
        deliverComponent(player, contracts[index], contracts[index].bonusReward.components(), 0, completion)
        completion.whenCompleteSync(tasks) { _, _ -> deliverPendingAt(player, contracts, index + 1) }
    }

    private fun deliverComponent(
        player: Player,
        contract: ActiveContract,
        components: List<ContractRewardComponent>,
        index: Int,
        completion: CompletableFuture<ContractRewardDeliveryResult>,
    ) {
        if (index >= components.size) {
            repository.markRewardGranted(player.uniqueId, contract.id).whenCompleteSync(tasks) { _, failure ->
                if (failure == null) completion.complete(ContractRewardDeliveryResult.GRANTED)
                else {
                    logger.log(Level.SEVERE, debug.line("contract" to contract.id, "outcome" to "state_unknown"), failure)
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
        val identity = contract.rewardIdentity(component)
        ledger.claim(
            OneTimeUseClaimRequest(identity, identity.useId, player.uniqueId, scope),
        ).whenCompleteSync(tasks) { result, failure ->
            if (failure != null) {
                logger.log(Level.WARNING, debug.line("contract" to contract.id, "component" to component.key, "outcome" to "claim_unknown"), failure)
                completion.complete(ContractRewardDeliveryResult.PENDING)
                return@whenCompleteSync
            }
            when (result) {
                is OneTimeUseClaimResult.Acquired -> if (result.claim.newlyCreated) {
                    applyComponent(player, contract, components, index, result.claim, completion)
                } else {
                    abandonForRecovery(player, contract, component, result.claim, "claim_recovered", completion) {
                        deliverComponent(player, contract, components, index + 1, completion)
                    }
                }
                OneTimeUseClaimResult.AlreadyConsumed -> deliverComponent(player, contract, components, index + 1, completion)
                OneTimeUseClaimResult.Busy -> completion.complete(ContractRewardDeliveryResult.PENDING)
                OneTimeUseClaimResult.IdentityConflict,
                OneTimeUseClaimResult.Missing,
                null,
                -> markRecovery(player, contract, component, "claim_conflict", completion)
            }
        }
    }

    private fun applyComponent(
        player: Player,
        contract: ActiveContract,
        components: List<ContractRewardComponent>,
        index: Int,
        claim: OneTimeUseClaim,
        completion: CompletableFuture<ContractRewardDeliveryResult>,
    ) {
        val component = components[index]
        if (!player.isOnline) {
            release(player, contract, component, claim, completion)
            return
        }
        val result = try {
            provider.apply(player, component)
        } catch (failure: Throwable) {
            logger.log(Level.SEVERE, debug.line("contract" to contract.id, "component" to component.key, "outcome" to "effect_unknown"), failure)
            abandonForRecovery(player, contract, component, claim, "effect_unknown", completion) {
                deliverComponent(player, contract, components, index + 1, completion)
            }
            return
        }
        if (result == ContractRewardApplyResult.REJECTED) {
            release(player, contract, component, claim, completion)
            return
        }
        ledger.commit(claim).whenCompleteSync(tasks) { committed, failure ->
            if (failure == null && committed in setOf(OneTimeUseCommitResult.COMMITTED, OneTimeUseCommitResult.ALREADY_COMMITTED)) {
                deliverComponent(player, contract, components, index + 1, completion)
            } else {
                logger.log(Level.SEVERE, debug.line("contract" to contract.id, "component" to component.key, "outcome" to "commit_unknown"), failure)
                abandonForRecovery(player, contract, component, claim, "commit_unknown", completion) {
                    deliverComponent(player, contract, components, index + 1, completion)
                }
            }
        }
    }

    private fun release(
        player: Player,
        contract: ActiveContract,
        component: ContractRewardComponent,
        claim: OneTimeUseClaim,
        completion: CompletableFuture<ContractRewardDeliveryResult>,
    ) {
        ledger.release(claim).whenCompleteSync(tasks) { released, failure ->
            if (failure == null && released in setOf(OneTimeUseReleaseResult.RELEASED, OneTimeUseReleaseResult.ALREADY_RELEASED)) {
                completion.complete(ContractRewardDeliveryResult.PENDING)
            } else {
                markRecovery(player, contract, component, "release_unknown", completion)
            }
        }
    }

    private fun abandonForRecovery(
        player: Player,
        contract: ActiveContract,
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
                markRecovery(player, contract, component, code, completion)
            }
        }
    }

    private fun markRecovery(
        player: Player,
        contract: ActiveContract,
        component: ContractRewardComponent,
        code: String,
        completion: CompletableFuture<ContractRewardDeliveryResult>,
    ) {
        repository.markRewardRecovery(player.uniqueId, contract.id, code).whenCompleteSync(tasks) { _, failure ->
            if (failure != null) logger.log(Level.SEVERE, "Could not retain contract reward for recovery", failure)
            logger.warning(debug.line("contract" to contract.id, "component" to component.key, "outcome" to "recovery", "code" to code))
            completion.complete(ContractRewardDeliveryResult.RECOVERY)
        }
    }
}

internal fun ActiveContract.rewardIdentity(component: ContractRewardComponent): OneTimeUseIdentity {
    val fingerprint = OneTimeUseFingerprint.sha256Fields(
        "arcranks-contract-reward-v1",
        id.value,
        component.key,
        component.amount.toString(),
        when (component) {
            is ContractRewardComponent.Money -> "vault"
            is ContractRewardComponent.Tokens -> component.currency
            is ContractRewardComponent.Item -> component.preset
        },
    )
    val seed = "arcranks:${id.value}:${component.key}".toByteArray(StandardCharsets.UTF_8)
    val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(seed).copyOf(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val buffer = ByteBuffer.wrap(bytes)
    return OneTimeUseIdentity(UUID(buffer.long, buffer.long), fingerprint)
}
