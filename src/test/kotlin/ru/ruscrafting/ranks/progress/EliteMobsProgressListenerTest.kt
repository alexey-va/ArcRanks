package ru.ruscrafting.ranks.progress

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import java.util.UUID

class EliteMobsProgressListenerTest : StringSpec({
    "dungeon progress belongs only to players present at both start and completion" {
        val stayed = UUID.randomUUID()
        val left = UUID.randomUUID()
        val joinedLate = UUID.randomUUID()

        completedDungeonParticipants(setOf(stayed, left), setOf(stayed, joinedLate))
            .shouldContainExactlyInAnyOrder(stayed)
    }
})
