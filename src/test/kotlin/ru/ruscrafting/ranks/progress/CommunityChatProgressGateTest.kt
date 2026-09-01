package ru.ruscrafting.ranks.progress

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.ranks.config.CommunityChatSourceSettings
import java.time.Instant
import java.util.UUID

class CommunityChatProgressGateTest : StringSpec({
    val playerId = UUID.fromString("b7c211de-6e10-4b41-b847-3a7502206f04")
    val settings = CommunityChatSourceSettings(
        enabled = true,
        amount = 1,
        cooldownSeconds = 60,
        duplicateWindowSeconds = 600,
        minimumLettersOrDigits = 4,
    )

    "a meaningful chat message earns configured community progress" {
        val gate = CommunityChatProgressGate()

        gate.credit(playerId, "Всем привет!", Instant.parse("2026-09-02T10:00:00Z"), settings) shouldBe 1
    }

    "cooldown and duplicate window stop chat farming" {
        val gate = CommunityChatProgressGate()
        val start = Instant.parse("2026-09-02T10:00:00Z")

        gate.credit(playerId, "Всем привет!", start, settings) shouldBe 1
        gate.credit(playerId, "Как ваши дела?", start.plusSeconds(59), settings) shouldBe 0
        gate.credit(playerId, "   ВСЕМ   ПРИВЕТ!   ", start.plusSeconds(60), settings) shouldBe 0
        gate.credit(playerId, "Как ваши дела?", start.plusSeconds(61), settings) shouldBe 1
    }

    "punctuation and very short messages do not earn progress" {
        val gate = CommunityChatProgressGate()
        val start = Instant.parse("2026-09-02T10:00:00Z")

        gate.credit(playerId, "!!!", start, settings) shouldBe 0
        gate.credit(playerId, "ок", start.plusSeconds(60), settings) shouldBe 0
    }

    "disabled collection and separate players are respected" {
        val gate = CommunityChatProgressGate()
        val start = Instant.parse("2026-09-02T10:00:00Z")
        val otherPlayerId = UUID.fromString("ef5bbbe5-c66f-44db-b3e8-9df1bfc5c25a")

        gate.credit(playerId, "Содержательное сообщение", start, settings.copy(enabled = false)) shouldBe 0
        gate.credit(playerId, "Содержательное сообщение", start, settings) shouldBe 1
        gate.credit(otherPlayerId, "Содержательное сообщение", start.plusSeconds(1), settings) shouldBe 1
    }
})
