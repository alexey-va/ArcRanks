package ru.ruscrafting.ranks.text

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.config.ConfigManager
import ru.arc.paper.menu.DialogTextLayout
import java.nio.file.Files

class RankChatNoticeTest : FunSpec({
    val plain = PlainTextComponentSerializer.plainText()
    val player = mockk<Player>()
    afterTest { ConfigManager.clear() }

    test("ordinary rank notifications use three aligned rows and one white chevron in both locales") {
        val root = Files.createTempDirectory("rank-chat")
        try {
            for (language in listOf("ru", "en")) {
                val locale = RankLocale(root, { language }, { false })
                for (path in listOf("commands.promotion.success", "commands.focus.selected",
                    "commands.contracts.accepted", "commands.weekly-kit.claimed", "commands.perks.selected",
                    "commands.storage-unavailable", "reminders.rank.ready")) {
                    val output = locale.chat(path, player, mapOf(
                        "rank" to locale.text("Ремесленник"), "next-rank" to locale.text("Ремесленник"),
                        "path" to locale.text("Промышленность"), "target" to locale.text(250),
                        "perk" to locale.text("Быстрые руки"), "action" to locale.text("/rank"),
                    ))
                    val rows = plain.serialize(output).trim('\n').split('\n')
                    rows.size shouldBe 3
                    rows.all { !it.startsWith(" ") } shouldBe true
                    val expected = plain.serialize(DialogTextLayout.spacing.padding(2)) + "\uE52A" +
                        plain.serialize(DialogTextLayout.spacing.padding(3))
                    rows[2].startsWith(expected) shouldBe true
                    plain.serialize(output).count { it == '\uE52A' } shouldBe 1
                    output.nodes().filterIsInstance<TextComponent>().single { it.content() == "\uE52A" }
                        .color() shouldBe TextColor.color(0xFFFFFF)
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    test("rank reminder retains its clickable action while console and action bar remain plain") {
        val root = Files.createTempDirectory("rank-chat-action")
        try {
            val locale = RankLocale(root, { "ru" }, { false })
            val click = ClickEvent.runCommand("/rank")
            val message = locale.chat("reminders.rank.ready", player, mapOf(
                "next-rank" to locale.text("Ремесленник"), "action" to Component.text("/rank").clickEvent(click),
            ))
            message.nodes().any { it.clickEvent() == click } shouldBe true
            plain.serialize(locale.chat("commands.loading", null)).contains('\uE52A') shouldBe false
            plain.serialize(locale.render("daily.tracking.counter", player, mapOf(
                "quest-name" to locale.text("Пшеница"), "progress" to locale.text(1), "target" to locale.text(4),
            ))).contains('\uE52A') shouldBe false
            plain.serialize(locale.chat("commands.help", player)).contains("/rankup") shouldBe true
        } finally { root.toFile().deleteRecursively() }
    }

    test("a long quest reward never loses its completed outcome or reward values") {
        val body = Component.text("Очень длинное название задания ".repeat(5))
            .append(Component.newline()).append(Component.text("+250 монет и +3 жетона"))
        val output = RankChatNotice.render(Component.text("Задание выполнено!"), body, keepHeading = true)
        val text = plain.serialize(output)
        text.contains("Задание выполнено!") shouldBe true
        text.contains("+250 монет и +3 жетона") shouldBe true
        text.count { it == '\uE52A' } shouldBe 1
    }
})

private fun Component.nodes(): Sequence<Component> = sequence {
    yield(this@nodes)
    children().forEach { yieldAll(it.nodes()) }
}
