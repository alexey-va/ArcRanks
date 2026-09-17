package ru.ruscrafting.ranks.presentation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class CelebrationCatalogTest : StringSpec({
    val scenes = CelebrationRecipe.entries.associate { recipe ->
        recipe.name.lowercase() to CelebrationSceneSettings(recipe)
    }
    val catalog = CelebrationCatalog(
        enabled = true,
        scenes = scenes,
        routes = CelebrationRoutes(
            questDefault = "burst",
            questRare = "crown",
            questAdvanced = "helix",
            questById = mapOf("first_steps" to "starfall"),
            rankDefault = "ascension",
            rankById = mapOf("king" to "firework_finale"),
        ),
    )

    "routes choose exact quest, advanced, rare and default scenes in order" {
        catalog.forQuest(CelebrationQuestContext("first_steps", rare = true, advanced = true)).id shouldBe "starfall"
        catalog.forQuest(CelebrationQuestContext("other", rare = true, advanced = true)).id shouldBe "helix"
        catalog.forQuest(CelebrationQuestContext("other", rare = true, advanced = false)).id shouldBe "crown"
        catalog.forQuest(CelebrationQuestContext("other", rare = false, advanced = false)).id shouldBe "burst"
    }

    "rank routes support exact overrides and a default" {
        catalog.forRank("king").id shouldBe "firework_finale"
        catalog.forRank("citizen").id shouldBe "ascension"
    }

    "all built in recipes produce distinct bounded animation frames" {
        val signatures = CelebrationRecipe.entries.map { recipe ->
            val frame = CelebrationGeometry.frame(recipe, tick = 7, durationTicks = 60, count = 18, radius = 1.5, height = 2.4)
            frame.size shouldBe 18
            frame.all { point -> point.x in -4.0..4.0 && point.y in -1.0..5.0 && point.z in -4.0..4.0 } shouldBe true
            frame.take(4).joinToString("|") { point -> "%.2f,%.2f,%.2f".format(point.x, point.y, point.z) }
        }
        signatures.distinct().size shouldBe CelebrationRecipe.entries.size
    }

    "scene ids remain stable for admin preview" {
        catalog.sceneIds() shouldContainExactly scenes.keys.sorted()
        catalog.scene("orbit").recipe shouldNotBe CelebrationRecipe.BURST
    }
})
