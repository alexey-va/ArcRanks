package ru.ruscrafting.ranks.progress

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class BuilderProgressIntegrationTest : StringSpec({
    "public Builder event material names match Bukkit placement filters" {
        val operation = decodeBuilderProgress(BuilderEventFixture(placements = listOf(BuilderPlacementFixture(1, 64, 2, "minecraft:stone"))))
        operation.placements.single().material shouldBe "STONE"
        operation.operationId shouldBe "operation-1"
    }
    "largest permitted Builder plan is accepted by the public bridge" {
        val operation = decodeBuilderProgress(BuilderEventFixture(placements = List(10000) { BuilderPlacementFixture(it, 64, 2, "minecraft:stone") }))
        operation.placements.size shouldBe 10000
    }
})

data class BuilderPlacementFixture(val x: Int, val y: Int, val z: Int, val material: String)
data class BuilderEventFixture(
    val operationId: String = "operation-1",
    val playerId: UUID = UUID.randomUUID(),
    val worldId: UUID = UUID.randomUUID(),
    val placements: List<BuilderPlacementFixture>,
)
