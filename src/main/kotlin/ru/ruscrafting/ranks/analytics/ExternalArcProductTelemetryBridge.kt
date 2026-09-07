package ru.ruscrafting.ranks.analytics

import java.util.UUID
import java.security.MessageDigest

/** Optional ARC product telemetry; a missing ARC never affects rank gameplay. */
internal object ExternalArcProductTelemetryBridge {
    private val recordMethod = lazy {
        Class.forName("ru.arc.metrics.ExternalProductTelemetryBridge").getMethod(
            "record",
            UUID::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
        )
    }
    private val recordEventMethod = lazy {
        Class.forName("ru.arc.metrics.ExternalProductTelemetryBridge").getMethod(
            "recordEvent", UUID::class.java, String::class.java, String::class.java, String::class.java,
        )
    }

    fun contractClaimed(playerId: UUID, contractId: String) {
        record(playerId, feature = "contracts", outcome = "contract_complete", operationId = "contract:claim:${safe(contractId)}")
    }

    fun contractAccepted(playerId: UUID, contractId: String) {
        record(playerId, feature = "contracts", operationId = "contract:accept:${safe(contractId)}")
    }

    fun contractMenuOpened(playerId: UUID, operationId: String) {
        record(playerId, feature = "contracts", operationId = "contract:menu:${safe(operationId)}")
    }

    fun promotionSucceeded(playerId: UUID, operationId: String) {
        runCatching { recordEventMethod.value.invoke(null, playerId, "arcranks", "rank_promotion_succeeded", "promotion:${safe(operationId)}") }
    }

    fun kitClaimed(playerId: UUID, claimId: String) {
        runCatching { recordEventMethod.value.invoke(null, playerId, "arcranks", "rank_kit_claimed", "kit:${safe(claimId)}") }
    }


    private fun record(playerId: UUID, feature: String?, outcome: String? = null, operationId: String) {
        runCatching {
            recordMethod.value.invoke(null, playerId, "arcranks", feature, outcome, null, operationId)
        }
    }

    private fun safe(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return digest
    }
}
