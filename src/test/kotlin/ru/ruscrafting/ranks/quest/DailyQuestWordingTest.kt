package ru.ruscrafting.ranks.quest

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import java.nio.file.Files

class DailyQuestWordingTest : StringSpec({
    "every concrete quest and step names an action and explains its scaled target" {
        val root = Files.createTempDirectory("quest-wording")
        val quests = Config(root, "daily-quests.yml")
        val ru = Config(root, "lang/ru.yml")
        val en = Config(root, "lang/en.yml")
        val action = Regex("^(?:[А-ЯЁ][а-яё]+те|[А-ЯЁ][а-яё]+(?:ть|ти))(?=\\s|$)")
        val markup = Regex("<[^>]*>")
        for (id in quests.keys("quests")) {
            val path = "quests.$id"
            val textId = quests.string("$path.text")
            val steps = quests.keys("$path.steps")
            val concrete = if (steps.isEmpty()) listOf(textId) else steps.map { quests.string("$path.steps.$it.text") }
            for (text in concrete) {
                val name = ru.string("daily.$text.name").replace(markup, "")
                check(action.containsMatchIn(name)) { "$id / $text: name does not explain an action: $name" }
                for (locale in if (id in setOf("vote", "discord_link", "telegram_link")) emptyList() else listOf(ru, en)) {
                    check(locale.stringList("daily.$text.lore").any { "<target>" in it }) { "$text: missing scaled target" }
                }
            }
        }
    }

    "bakery instructions describe harvesting followed by crafting rather than a place or oven" {
        val root = Files.createTempDirectory("bakery-wording")
        val ru = Config(root, "lang/ru.yml")
        val bread = ru.stringList("daily.craft_bread.lore").joinToString(" ")
        bread.contains("<target>") shouldBe true
        check("крафт" in bread.lowercase() || "верстак" in bread.lowercase())
        val bakery = ru.stringList("daily.bakery_order.lore").joinToString(" ").lowercase()
        check("затем" in bakery)
        check("испеките" !in bakery)
    }
})
