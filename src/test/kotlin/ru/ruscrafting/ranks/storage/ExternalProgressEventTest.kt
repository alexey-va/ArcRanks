package ru.ruscrafting.ranks.storage

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import ru.ruscrafting.ranks.domain.ProgressMetric
import java.util.UUID

class ExternalProgressEventTest : StringSpec({
    "external event accepts a bounded stable identity" {
        val event = ExternalProgressEvent(
            playerId = UUID.randomUUID(),
            source = "arcgiveaways",
            eventId = "giveaway:019f-example",
            metric = ProgressMetric.COMMUNITY_MINUTES,
            delta = 5,
        )

        event.source shouldBe "arcgiveaways"
        event.delta shouldBe 5
    }

    "external event rejects unsafe identity and non-positive progress" {
        shouldThrow<IllegalArgumentException> {
            ExternalProgressEvent(UUID.randomUUID(), "bad source", "event", ProgressMetric.BLOCKS_PLACED, 1)
        }
        shouldThrow<IllegalArgumentException> {
            ExternalProgressEvent(UUID.randomUUID(), "source", "../event", ProgressMetric.BLOCKS_PLACED, 1)
        }
        shouldThrow<IllegalArgumentException> {
            ExternalProgressEvent(UUID.randomUUID(), "source", "event", ProgressMetric.BLOCKS_PLACED, 0)
        }
        shouldThrow<IllegalArgumentException> {
            ExternalProgressEvent(UUID.randomUUID(), "source", "event", ProgressMetric.WEALTH_PEAK, 1)
        }
    }
})
