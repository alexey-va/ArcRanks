package ru.ruscrafting.ranks.contract

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.onetime.OneTimeUseIdentity
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.core.LifecycleTaskScope
import ru.ruscrafting.ranks.reward.RankReward
import ru.ruscrafting.ranks.reward.RankRewardDeliveryService
import ru.ruscrafting.ranks.reward.RankRewardRepository
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

enum class ContractRewardDeliveryResult {
    GRANTED,
    PENDING,
    RECOVERY,
}

/** Compatibility adapter for the contract-specific repository and public API. */
class ContractRewardDeliveryService(
    repository: ContractRepository,
    ledger: OneTimeUseLedger,
    provider: ContractRewardProvider,
    tasks: LifecycleTaskScope,
    logger: Logger,
) : Listener {
    private val delivery = RankRewardDeliveryService(
        repository = ContractRankRewardRepository(repository),
        ledger = ledger,
        provider = provider,
        tasks = tasks,
        logger = logger,
    )

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        deliverPending(event.player)
    }

    fun deliverPending(player: Player) {
        delivery.deliverPending(player)
    }

    fun deliver(player: Player, contract: ActiveContract): CompletableFuture<ContractRewardDeliveryResult> =
        delivery.deliver(player, contract.asRankReward())
}

private class ContractRankRewardRepository(
    private val delegate: ContractRepository,
) : RankRewardRepository {
    override fun pendingRewards(playerId: UUID): CompletableFuture<List<RankReward>> =
        delegate.pendingRewards(playerId).thenApply { contracts -> contracts.map(ActiveContract::asRankReward) }

    override fun markRewardGranted(playerId: UUID, rewardId: String): CompletableFuture<Boolean> =
        delegate.markRewardGranted(playerId, ContractId(rewardId))

    override fun markRewardRecovery(
        playerId: UUID,
        rewardId: String,
        failureCode: String,
    ): CompletableFuture<Boolean> = delegate.markRewardRecovery(playerId, ContractId(rewardId), failureCode)
}

private fun ActiveContract.asRankReward(): RankReward =
    RankReward(id = id.value, namespace = "contract", components = bonusReward.components())

/** Stable contract identity retained for compatibility with the original delivery flow. */
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
