package ru.ruscrafting.ranks.quest

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class QuestProgressDiagnosticsTest : StringSpec({
    "latest supports generic prefix and accepted clears only the matching event" {
        val clock = MutableDiagnosticsClock()
        val diagnostics = QuestProgressDiagnostics(clock)
        val player = UUID.randomUUID()

        diagnostics.rejected(player, "harvest:wheat", QuestProgressRejectReason.MATERIAL_FILTERED)
        diagnostics.rejected(player, "harvest:carrots", QuestProgressRejectReason.NOT_MATURE)

        diagnostics.latest(player, "harvest:wheat") shouldBe "material_filtered"
        diagnostics.latest(player, "harvest") shouldBe "not_mature"
        diagnostics.latest(player, "harvest:potatoes") shouldBe null

        diagnostics.accepted(player, "harvest:carrots")
        diagnostics.latest(player, "harvest:carrots") shouldBe null
        diagnostics.latest(player, "harvest:wheat") shouldBe "material_filtered"
        diagnostics.latest(player, "harvest") shouldBe null

        diagnostics.rejected(player, "harvest:wheat", QuestProgressRejectReason.NOT_MATURE)
        diagnostics.latest(player, "harvest") shouldBe "not_mature"
    }

    "expired observations are omitted after the thirty second TTL" {
        val clock = MutableDiagnosticsClock()
        val diagnostics = QuestProgressDiagnostics(clock)
        val player = UUID.randomUUID()

        diagnostics.rejected(player, "craft:bread", QuestProgressRejectReason.BUFFER_FULL)
        clock.advanceSeconds(30)

        diagnostics.latest(player, "craft:bread") shouldBe null
    }

    "player and objective caps evict the oldest entries" {
        val clock = MutableDiagnosticsClock()
        val diagnostics = QuestProgressDiagnostics(clock)
        val firstPlayer = UUID.randomUUID()
        diagnostics.rejected(firstPlayer, "objective_0", QuestProgressRejectReason.SOURCE_DISABLED)
        repeat(256) { index ->
            diagnostics.rejected(UUID.randomUUID(), "objective_$index", QuestProgressRejectReason.SOURCE_DISABLED)
        }
        diagnostics.latest(firstPlayer, "objective_0") shouldBe null

        val player = UUID.randomUUID()
        repeat(33) { index ->
            diagnostics.rejected(player, "objective_$index", QuestProgressRejectReason.CONTEXT_INELIGIBLE)
        }
        diagnostics.latest(player, "objective_0") shouldBe null
        diagnostics.latest(player, "objective_32") shouldBe "context_ineligible"
    }
})

private class MutableDiagnosticsClock(
    private var current: Instant = Instant.parse("2026-01-01T00:00:00Z"),
) : Clock() {
    override fun instant(): Instant = current

    override fun withZone(zone: java.time.ZoneId): Clock = this

    override fun getZone(): java.time.ZoneId = ZoneOffset.UTC

    fun advanceSeconds(seconds: Long) {
        current = current.plusSeconds(seconds)
    }
}
