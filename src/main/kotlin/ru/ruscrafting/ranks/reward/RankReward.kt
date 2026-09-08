package ru.ruscrafting.ranks.reward

import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.onetime.OneTimeUseIdentity
import ru.ruscrafting.ranks.contract.ContractRewardComponent
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture

data class RankReward(
    val id: String,
    val namespace: String,
    val components: List<ContractRewardComponent>,
) {
    init {
        require(id.isNotBlank() && id.length <= 256) { "Rank reward id must be 1..256 characters" }
        require(namespace.matches(SAFE_NAMESPACE)) { "Unsafe rank reward namespace" }
    }

    internal fun identity(component: ContractRewardComponent): OneTimeUseIdentity {
        if (namespace == CONTRACT_NAMESPACE) return legacyContractIdentity(id, component)

        val fingerprint = OneTimeUseFingerprint.sha256Fields(
            "arcranks-rank-reward-v1",
            namespace,
            id,
            component.key,
            component.amount.toString(),
            componentType(component),
        )
        return identityFromSeed("arcranks:$namespace:$id:${component.key}", fingerprint)
    }

    companion object {
        const val CONTRACT_NAMESPACE = "contract"
        private val SAFE_NAMESPACE = Regex("[a-z0-9_-]{1,32}")

        private fun componentType(component: ContractRewardComponent): String = when (component) {
            is ContractRewardComponent.Money -> "vault"
            is ContractRewardComponent.Tokens -> component.currency
            is ContractRewardComponent.Item -> component.preset
        }

        private fun legacyContractIdentity(id: String, component: ContractRewardComponent): OneTimeUseIdentity {
            val fingerprint = OneTimeUseFingerprint.sha256Fields(
                "arcranks-contract-reward-v1",
                id,
                component.key,
                component.amount.toString(),
                componentType(component),
            )
            return identityFromSeed("arcranks:$id:${component.key}", fingerprint)
        }

        private fun identityFromSeed(seed: String, fingerprint: OneTimeUseFingerprint): OneTimeUseIdentity {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest(seed.toByteArray(StandardCharsets.UTF_8))
                .copyOf(16)
            bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
            val buffer = ByteBuffer.wrap(bytes)
            return OneTimeUseIdentity(UUID(buffer.long, buffer.long), fingerprint)
        }
    }
}

interface RankRewardRepository {
    fun pendingRewards(playerId: UUID): CompletableFuture<List<RankReward>>
    fun markRewardGranted(playerId: UUID, rewardId: String): CompletableFuture<Boolean>
    fun markRewardRecovery(playerId: UUID, rewardId: String, failureCode: String): CompletableFuture<Boolean>
}
