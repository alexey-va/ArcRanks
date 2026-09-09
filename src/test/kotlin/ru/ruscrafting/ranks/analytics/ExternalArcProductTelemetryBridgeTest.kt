package ru.ruscrafting.ranks.analytics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class ExternalArcProductTelemetryBridgeTest : StringSpec({
    "hashes the component so the complete operation id stays within ARC's 80 character limit" {
        val operationId = ExternalArcProductTelemetryBridge.contractAcceptOperationId("x".repeat(300))
        operationId.length shouldBe 80
        operationId shouldNotBe "contract:accept:" + "x".repeat(300)
    }
})
