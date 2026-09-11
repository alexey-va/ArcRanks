package ru.ruscrafting.ranks.text

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/** Preserve semantic gaps; native tooltips own horizontal padding and soft wrapping. */
object TooltipLayout {
    fun dialog(lines: List<Component>): Component {
        val plain = PlainTextComponentSerializer.plainText()
        val rows = mutableListOf<Component>()
        for (line in lines) {
            if (plain.serialize(line).isBlank()) {
                if (rows.isNotEmpty() && plain.serialize(rows.last()).isNotBlank()) rows += Component.empty()
            } else rows += line // Leading spaces indent only authored rows, not client-wrapped continuations.
        }
        if (rows.lastOrNull()?.let { plain.serialize(it).isBlank() } == true) rows.removeLast()
        if (rows.isEmpty()) return Component.empty()
        val joined = Component.join(JoinConfiguration.newlines(), listOf(Component.empty()) + rows + Component.empty())
        return joined.children(joined.children().map(::adaptNativeColors))
            .decoration(TextDecoration.ITALIC, false)
    }

    /**
     * Inventory and chat copy still use the authored muted color. Native dialog
     * tooltips have a darker client backdrop, so lift only the legacy muted
     * foreground there while retaining every nested semantic accent (and white
     * glyphs such as the reward icon).
     */
    private fun adaptNativeColors(component: Component): Component {
        val color = component.color()
        val adapted = when (color?.value()) {
            LEGACY_MUTED.value(), LEGACY_MUTED_ALT.value() -> component.color(NATIVE_BODY)
            else -> component
        }
        return adapted.children(adapted.children().map(::adaptNativeColors))
    }

    private val LEGACY_MUTED = TextColor.color(0xb8b8b8)
    private val LEGACY_MUTED_ALT = TextColor.color(0xaaa49a)
    private val NATIVE_BODY = TextColor.color(0xe8dfd2)
}
