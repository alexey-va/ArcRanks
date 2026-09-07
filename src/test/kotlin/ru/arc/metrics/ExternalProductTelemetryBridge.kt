package ru.arc.metrics

import java.util.UUID

/** Test-only stand-in for the optional ARC runtime class loaded reflectively by ArcRanks. */
object ExternalProductTelemetryBridge {
    data class Call(val playerId: UUID, val source: String, val feature: String?, val outcome: String?, val action: String?, val operationId: String)
    val calls = mutableListOf<Call>()

    @JvmStatic
    fun record(playerId: UUID, source: String, feature: String?, outcome: String?, action: String?, operationId: String): Boolean {
        calls += Call(playerId, source, feature, outcome, action, operationId)
        return true
    }

    @JvmStatic
    fun recordEvent(playerId: UUID, source: String, event: String, operationId: String): Boolean = true
}
