package ru.ruscrafting.ranks.gui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.menu.MenuElementId
import java.nio.file.Files

class RankMenuContractTest : StringSpec({
    val catalog = ArcRanksMenuLayouts.loadConfiguration(Files.createTempDirectory("arcranks-menu-contract"))

    "configured contract board keeps the default balanced composition" {
        val layout = catalog.require(ArcRanksMenuLayouts.CONTRACTS)
        val offers = layout.region("cards").map { it.index }
        val stamps = layout.region("stamps").map { it.index }
        layout.rows shouldBe 6
        offers.shouldContainExactly(20, 22, 24)
        stamps.shouldContainExactly(10, 13, 16)
        offers.shouldBeHorizontallySymmetric()
        stamps.shouldBeHorizontallySymmetric()
        runCatching { layout.slot(MenuElementId.of("claim")) }.isFailure shouldBe true
        listOf(layout.slot("back").index, layout.slot("refresh").index).shouldBeHorizontallySymmetric()
        layout.slot("admin-complete").index shouldBe 48
    }

    "configured perk board exposes two slots and six path groups" {
        val slots = catalog.require(ArcRanksMenuLayouts.PERK_SLOTS).region("slots").map { it.index }
        val selection = catalog.require(ArcRanksMenuLayouts.PERK_SELECTION)
        slots.shouldContainExactly(21, 23)
        val configured = listOf("farming", "industry", "trade", "exploration", "building", "community")
            .map { selection.slot(it).index } + selection.region("offers").map { it.index }
        configured.let {
            it.size shouldBe 24
            it.distinct().size shouldBe 24
            it.shouldBeHorizontallySymmetric()
        }
        slots.shouldBeHorizontallySymmetric()
    }

    "analytics board reserves three configurable cached-window controls" {
        val layout = catalog.require(ArcRanksMenuLayouts.ANALYTICS)
        val windows = layout.region("windows").map { it.index }
        windows.shouldContainExactly(10, 13, 16)
        windows.shouldBeHorizontallySymmetric()
        listOf(layout.slot("back").index, layout.slot("refresh").index).shouldBeHorizontallySymmetric()
    }
})

private fun List<Int>.shouldBeHorizontallySymmetric() {
    groupBy { it / 9 }.values.forEach { rowSlots ->
        val columns = rowSlots.mapTo(sortedSetOf()) { it % 9 }
        columns shouldBe columns.mapTo(sortedSetOf()) { 8 - it }
    }
}
