package ru.ruscrafting.ranks.quest

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class QuestIconTest : StringSpec({
    "specific successful action selects its icon without changing the path" {
        questIcon("harvest:carrots", "WHEAT") shouldBe "CARROT"
        questIcon("mine.job:team", "CRAFTING_TABLE") shouldBe "IRON_PICKAXE"
        questIcon("dungeon.complete:em_id_the_mines", "COMPASS") shouldBe "IRON_SWORD"
        questIcon("vote.confirmed", "CAMPFIRE") shouldBe "SUNFLOWER"
        questIcon("account.discord", "CAMPFIRE") shouldBe "NAME_TAG"
        questIcon("contract.items:forge_iron_ingot", "EMERALD") shouldBe "EMERALD"
    }
    "composite and custom objectives retain the path fallback" {
        questIcon("quest.harvest_basket", "WHEAT") shouldBe "WHEAT"
        questIcon("custom.action", "COMPASS") shouldBe "COMPASS"
    }
})
