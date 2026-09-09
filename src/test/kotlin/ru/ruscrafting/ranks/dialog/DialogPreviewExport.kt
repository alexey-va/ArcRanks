package ru.ruscrafting.ranks.dialog

import com.google.gson.GsonBuilder
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.paper.menu.PaperDialogScreen
import java.nio.file.Files
import java.nio.file.Path

/** Opt-in export of the actual presenter output, including ARC's measured table components. */
internal fun exportDialogPreview(screen: PaperDialogScreen) {
    val directory = System.getProperty("arcranks.dialogPreview") ?: return
    if (screen.id !in setOf("ranks.benefits.detail", "ranks.perks.path", "ranks.root", "ranks.daily", "ranks.daily.detail", "ranks.paths", "ranks.path", "ranks.perks", "ranks.weekly-kit")) return
    if (screen.id == "ranks.daily" && screen.buttons.any { it.id.value == "previous" }) return
    val plain = PlainTextComponentSerializer.plainText()
    if (screen.body.none { body -> plain.serialize(body.text).any { it.code in 0xE570..0xE58E } }) return
    val mm = LegacyComponentSerializer.builder().character('&').hexColors().build()
    val content = mutableMapOf("title" to mm.serialize(screen.title))
    val body = screen.body.mapIndexed { index, item ->
        val key = "body-$index"
        content[key] = mm.serialize(item.text)
        mapOf("id" to key, "text-key" to key, "width" to item.width)
    }
    fun button(item: ru.arc.paper.menu.PaperDialogButton): Map<String, Any> {
        val key = item.id.value
        content[key] = mm.serialize(item.label)
        content["$key-tooltip"] = mm.serialize(item.tooltip)
        return mapOf("id" to key, "text-key" to key, "tooltip-key" to "$key-tooltip", "width" to item.width)
    }
    val dialog = mapOf(
        "id" to screen.id, "source" to screen.id, "format" to "reference-multi-action",
        "name-key" to "title", "external-title-key" to "title", "columns" to screen.columns,
        "body" to body, "buttons" to screen.buttons.map(::button),
        "exit-button" to screen.exitButton?.let(::button), "viewport" to listOf(854, 480),
        "variants" to listOf(mapOf("id" to "example")),
    )
    val output = Path.of(directory)
    Files.createDirectories(output)
    val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    val suffix = if (screen.id == "ranks.benefits.detail" || screen.id == "ranks.perks.path")
        "." + plain.serialize(screen.title).replace(Regex("[^\\p{L}0-9]"), "_") + "." + (screen.buttons.firstOrNull { plain.serialize(it.label).startsWith("✔") }?.id?.value ?: "main") else ""
    Files.writeString(output.resolve("${screen.id}$suffix.json"), gson.toJson(mapOf("content" to content, "dialog" to dialog)))
}

internal fun previewLocale(): ru.ruscrafting.ranks.text.RankLocale? =
    System.getProperty("arcranks.dialogPreview")?.let {
        ru.ruscrafting.ranks.text.RankLocale.fresh(
            Path.of(System.getProperty("arcranks.projectDir"), "src/main/resources"), { "ru" }, { false },
        )
    }

internal fun previewQuests(): List<ru.ruscrafting.ranks.quest.DailyQuest>? =
    System.getProperty("arcranks.dialogPreview")?.let {
        ru.ruscrafting.ranks.quest.DailyQuestCatalog.load(
            ru.arc.config.Config(Path.of(System.getProperty("arcranks.projectDir"), "src/main/resources"), "daily-quests.yml"),
            setOf("settler", "peasant", "citizen", "artisan", "knight", "baron", "count", "prince", "caesar"),
        ).pool
    }
