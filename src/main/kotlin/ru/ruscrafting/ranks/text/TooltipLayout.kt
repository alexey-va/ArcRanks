package ru.ruscrafting.ranks.text

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
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
        return Component.join(JoinConfiguration.newlines(), listOf(Component.empty()) + rows + Component.empty())
            .decoration(TextDecoration.ITALIC, false)
    }
}
