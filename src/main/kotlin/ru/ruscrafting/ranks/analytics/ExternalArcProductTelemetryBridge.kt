package ru.ruscrafting.ranks.analytics

import org.bukkit.Bukkit
import ru.arc.paper.api.ArcTelemetryProvider
import java.util.UUID
import java.security.MessageDigest

/** Optional ARC product telemetry; a missing ARC never affects rank gameplay. */
internal object ExternalArcProductTelemetryBridge {
    private val telemetry by lazy { Bukkit.getServicesManager().load(ArcTelemetryProvider::class.java) }

    fun contractClaimed(playerId: UUID, contractId: String) {
        record(playerId, feature = "contracts", outcome = "contract_complete", operationId = "contract:claim:${safe(contractId)}")
    }

    fun contractAccepted(playerId: UUID, contractId: String) {
        record(playerId, feature = "contracts", operationId = contractAcceptOperationId(contractId))
    }

    internal fun contractAcceptOperationId(contractId: String): String = "contract:accept:${safe(contractId)}"

    fun contractMenuOpened(playerId: UUID, operationId: String) {
        record(playerId, feature = "contracts", operationId = "contract:menu:${safe(operationId)}")
    }

    fun promotionSucceeded(playerId: UUID, operationId: String) {
        runCatching { telemetry?.recordEvent(playerId, "arcranks", "rank_promotion_succeeded", "promotion:${safe(operationId)}") }
    }

    fun kitClaimed(playerId: UUID, claimId: String) {
        runCatching { telemetry?.recordEvent(playerId, "arcranks", "rank_kit_claimed", "kit:${safe(claimId)}") }
    }


    private fun record(playerId: UUID, feature: String?, outcome: String? = null, operationId: String) {
        runCatching {
            telemetry?.record(playerId, "arcranks", feature, outcome, null, operationId)
        }
    }

    private fun safe(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return digest
    }
}
