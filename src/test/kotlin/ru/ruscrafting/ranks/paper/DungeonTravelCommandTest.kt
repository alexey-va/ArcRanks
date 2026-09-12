package ru.ruscrafting.ranks.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class DungeonTravelCommandTest : StringSpec({
    "specific dungeon objectives route directly while generic or unsafe values use the portal hub" {
        dungeonTravelCommand("dungeon.complete:em_id_the_mines") shouldBe
            "elitemobs:em dungeontp em_id_the_mines"
        dungeonTravelCommand("dungeon.complete") shouldBe "dungeon tp"
        dungeonTravelCommand("dungeon.complete:the_mines say unsafe") shouldBe "dungeon tp"
    }
})
