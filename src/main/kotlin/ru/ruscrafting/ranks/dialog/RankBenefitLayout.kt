package ru.ruscrafting.ranks.dialog

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/** Presentation only: split authored field/value text while retaining component styles. */
internal object RankBenefitLayout {
    fun row(component: Component): Pair<Component, Component>? {
        val runs = mutableListOf<TextComponent>()
        fun collect(node: Component, inherited: Style): Boolean {
            if (node !is TextComponent) return false
            val style = node.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
            if (node.content().isNotEmpty()) runs += Component.text(node.content()).style(style)
            return node.children().all { collect(it, style) }
        }
        if (!collect(component, Style.empty())) return null
        val text = PlainTextComponentSerializer.plainText().serialize(component)
        val start = if (text.trimStart().startsWith("•")) text.indexOf('•') + 1 else 0
        val labelStart = text.indexOfFirstFrom(start) { !it.isWhitespace() }
        val separator = text.indexOf(": ", labelStart)
        if (separator <= labelStart) return null
        fun slice(from: Int, to: Int): Component {
            var offset = 0
            val parts = runs.mapNotNull { run ->
                val a = (from - offset).coerceIn(0, run.content().length)
                val b = (to - offset).coerceIn(0, run.content().length)
                offset += run.content().length
                if (a >= b) null else run.content(run.content().substring(a, b))
            }
            return Component.empty().children(parts)
        }
        return slice(labelStart, separator) to slice(separator + 2, text.length)
    }

    private inline fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int =
        (start until length).firstOrNull { predicate(this[it]) } ?: length
}
