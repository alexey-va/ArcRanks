package ru.ruscrafting.ranks.analytics

import org.bukkit.Bukkit
import ru.arc.paper.api.ArcTelemetryProvider
import java.util.UUID
import java.security.MessageDigest

/** Optional ARC product telemetry; a missing ARC never affects rank gameplay. */
internal object ExternalArcProductTelemetryBridge {
    @Volatile private var telemetry: ArcTelemetryProvider? = null

    /** Resolve the optional ARC sink on the Paper thread before SQL callbacks use it. */
    fun install() {
        telemetry = runCatching { Bukkit.getServicesManager().load(ArcTelemetryProvider::class.java) }.getOrNull()
    }

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

    fun dailyQuestTrackingChanged(playerId: UUID, day: String, questId: String, selected: Boolean) = activity(
        telemetry,
        playerId,
        if (selected) "daily_quest_selected" else "daily_quest_unselected",
        questId,
        null,
        mapOf("quest_day" to day, "outcome" to if (selected) "selected" else "unselected"),
    )

    fun dailyQuestReplaced(playerId: UUID, day: String, oldQuestId: String, newQuestId: String) = activity(
        telemetry,
        playerId,
        "daily_quest_replaced",
        oldQuestId,
        null,
        mapOf("quest_day" to day, "replacement_quest_id" to newQuestId, "outcome" to "replaced"),
    )

    fun dailyQuestProgress(
        playerId: UUID,
        event: String,
        questId: String,
        rewardId: String,
        day: String,
        metric: String,
        progress: Long,
        target: Long,
        percent: Int,
    ) = dailyQuestProgress(playerId, event, questId, rewardId, day, metric, progress, target, percent, telemetry)

    internal fun dailyQuestProgress(
        playerId: UUID,
        event: String,
        questId: String,
        rewardId: String,
        day: String,
        metric: String,
        progress: Long,
        target: Long,
        percent: Int,
        provider: ArcTelemetryProvider?,
    ) = activity(
        provider,
        playerId,
        event,
        questId,
        "$rewardId:${if (percent >= 100) "completed" else "halfway"}",
        mapOf(
            "quest_day" to day,
            "metric" to metric,
            "progress" to progress.toString(),
            "target" to target.toString(),
            "progress_percent" to percent.toString(),
            "outcome" to if (percent >= 100) "completed" else "checkpoint",
        ),
    )

    fun dailyQuestRewardClaimed(playerId: UUID, rewardId: String, questId: String, attributes: Map<String, String>) = activity(
        telemetry,
        playerId,
        "daily_quest_reward_claimed",
        questId,
        "$rewardId:granted",
        attributes + mapOf("outcome" to "granted"),
    )

    fun dailyQuestRewardRecovery(playerId: UUID, rewardId: String, questId: String?, reason: String) = activity(
        telemetry,
        playerId,
        "daily_quest_reward_recovery",
        questId,
        "$rewardId:recovery",
        mapOf("outcome" to "recovery", "reason" to reason.take(64)),
    )


    private fun record(playerId: UUID, feature: String?, outcome: String? = null, operationId: String) {
        runCatching {
            telemetry?.record(playerId, "arcranks", feature, outcome, null, operationId)
        }
    }

    private fun activity(
        provider: ArcTelemetryProvider?,
        playerId: UUID,
        event: String,
        subject: String?,
        operationId: String?,
        attributes: Map<String, String>,
    ): Boolean = runCatching {
        provider?.recordActivity(playerId, "arcranks", event, subject, operationId, attributes.toMap()) == true
    }.getOrDefault(false)

    private fun safe(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return digest
    }
}
