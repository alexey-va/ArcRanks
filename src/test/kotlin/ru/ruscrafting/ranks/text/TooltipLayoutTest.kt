package ru.ruscrafting.ranks.text

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class TooltipLayoutTest : FunSpec({
    test("long fishing tooltip leaves authored and client-wrapped rows at the same native inset") {
        // Screenshot regression: the client soft-wraps long prose, without repeating server-added spaces.
        val description = "Поймайте два разных вида рыбы из списка."
        val mode = "Разные виды считаются отдельно:"
        val hints = "Разные виды считаются отдельными шагами."
        val rows = listOf(
            description, "", "Прогресс: 0/2\nБонус пути: +11", "", "",
            mode, "1. Улов трески — 0/3", "2. Лососёвый улов — 0/3",
            "3. Колючий улов — 0/2", "4. Тропический улов — 0/2", "", hints, "", "Награда: 60 💰",
        )
        val component = TooltipLayout.dialog(rows.map(Component::text))
        val codec = GsonComponentSerializer.gson()
        val delivered = PlainTextComponentSerializer.plainText().serialize(codec.deserialize(codec.serialize(component)))
        delivered shouldBe "\n" + rows.joinToString("\n").replace("\n\n\n", "\n\n") + "\n"
        delivered.lines().filter(String::isNotBlank).all { it == it.trimStart() } shouldBe true
    }

    test("native tooltip lifts legacy muted text but preserves semantic child colors") {
        val muted = TextColor.color(0xb8b8b8)
        val accent = TextColor.color(0x22d3ee)
        val white = TextColor.color(0xffffff)
        val component = TooltipLayout.dialog(listOf(
            Component.text("Body").color(muted)
                .append(Component.text(" accent").color(accent))
                .append(Component.text(" reward").color(white)),
        ))

        val colors = buildList {
            fun collect(node: Component) {
                node.color()?.value()?.let(::add)
                node.children().forEach(::collect)
            }
            collect(component)
        }
        (0xe8dfd2 in colors) shouldBe true
        (accent.value() in colors) shouldBe true
        (white.value() in colors) shouldBe true
        colors shouldBe colors.filter { it != muted.value() }
    }
})
