package ru.ruscrafting.ranks.analytics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import ru.arc.metrics.ExternalProductTelemetryBridge
import java.util.UUID

class ExternalArcProductTelemetryBridgeTest : StringSpec({
    "hashes the component so the complete operation id stays within ARC's 80 character limit" {
        ExternalProductTelemetryBridge.calls.clear()
        ExternalArcProductTelemetryBridge.contractAccepted(UUID.randomUUID(), "x".repeat(300))
        val operationId = ExternalProductTelemetryBridge.calls.single().operationId
        operationId.length shouldBe 80
        operationId shouldNotBe "contract:accept:" + "x".repeat(300)
    }
})
