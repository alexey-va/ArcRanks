package ru.ruscrafting.ranks.analytics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import ru.arc.paper.api.ArcTelemetryProvider
import java.util.UUID

class ExternalArcProductTelemetryBridgeTest : StringSpec({
    "hashes the component so the complete operation id stays within ARC's 80 character limit" {
        val operationId = ExternalArcProductTelemetryBridge.contractAcceptOperationId("x".repeat(300))
        operationId.length shouldBe 80
        operationId shouldNotBe "contract:accept:" + "x".repeat(300)
    }

    "captures only a bounded daily quest checkpoint snapshot" {
        val playerId = UUID.randomUUID()
        val provider = ActivityCollector(accept = false)

        ExternalArcProductTelemetryBridge.dailyQuestProgress(
            playerId = playerId,
            event = "daily_quest_progress_checkpoint",
            questId = "harvest_wheat",
            rewardId = "daily-reward-17",
            day = "2026-10-04",
            metric = "farm",
            progress = 5L,
            target = 10L,
            percent = 50,
            provider = provider,
        )

        provider.activity shouldBe Activity(
            playerId,
            "arcranks",
            "daily_quest_progress_checkpoint",
            "harvest_wheat",
            "daily-reward-17:halfway",
            mapOf(
                "quest_day" to "2026-10-04",
                "metric" to "farm",
                "progress" to "5",
                "target" to "10",
                "progress_percent" to "50",
                "outcome" to "checkpoint",
            ),
        )
        provider.accepted shouldBe false
    }

    "contains optional provider failures" {
        val provider = ActivityCollector(failure = IllegalStateException("optional sink unavailable"))

        ExternalArcProductTelemetryBridge.dailyQuestProgress(
            UUID.randomUUID(), "daily_quest_completed", "harvest_wheat", "daily-reward-17",
            "2026-10-04", "farm", 10L, 10L, 100, provider,
        )

        provider.calls shouldBe 1
    }
})

private data class Activity(
    val playerId: UUID,
    val source: String,
    val event: String,
    val subject: String?,
    val operationId: String?,
    val attributes: Map<String, String>,
)

private class ActivityCollector(
    private val accept: Boolean = true,
    private val failure: Throwable? = null,
) : ArcTelemetryProvider {
    var calls = 0
        private set
    var accepted: Boolean? = null
        private set
    var activity: Activity? = null
        private set

    override fun recordActivity(
        playerId: UUID,
        source: String,
        event: String,
        subject: String?,
        operationId: String?,
        attributes: Map<String, String>,
    ): Boolean {
        calls += 1
        failure?.let { throw it }
        activity = Activity(playerId, source, event, subject, operationId, attributes.toMap())
        accepted = accept
        return accept
    }
}
