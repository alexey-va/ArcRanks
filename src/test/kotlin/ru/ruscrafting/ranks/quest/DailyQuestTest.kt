package ru.ruscrafting.ranks.quest

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDate

class DailyQuestTest : StringSpec({
    "partial progress pays nothing and crossing the target pays exactly once" {
        val quest = DailyQuest.ALL.first()
        var progress = 0L
        var paid = 0L
        listOf(30L, 69L, 1L, 100L, Long.MAX_VALUE).forEach { delta ->
            val next = quest.advance(progress, delta)
            paid += quest.completionBonus(progress, next)
            progress = next
        }
        progress shouldBe quest.target
        paid shouldBe quest.bonus
    }
    "large progress batches saturate without overflow" {
        DailyQuest.ALL.forEach { quest ->
            quest.advance(quest.target - 1, Long.MAX_VALUE) shouldBe quest.target
            quest.advance(quest.target, Long.MAX_VALUE) shouldBe quest.target
        }
    }
    "new UTC day is independent of server timezone and daylight saving" {
        DailyQuest.day(Instant.parse("2026-09-08T23:59:59.999Z")) shouldBe LocalDate.parse("2026-09-08")
        DailyQuest.day(Instant.parse("2026-09-09T00:00:00Z")) shouldBe LocalDate.parse("2026-09-09")
    }
    "daily board has three independent optional goals and ten percent bonuses" {
        DailyQuest.ALL.size shouldBe 3
        DailyQuest.ALL.map { it.metric }.distinct().size shouldBe 3
        DailyQuest.ALL.forEach { it.bonus * 10 shouldBe it.target }
    }
})
