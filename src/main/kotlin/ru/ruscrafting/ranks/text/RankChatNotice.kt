package ru.ruscrafting.ranks.text

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.text.TextAlignment
import ru.arc.text.TextLayoutResult

/** Rank chat branding over the shared pack-aware text layout. Never clips long results. */
internal object RankChatNotice {
    private const val GLYPH = '\uE52A' // arc:rank_chevron_large, height 27, ascent 26
    private const val INSET = 2
    private const val GAP = 3
    private val white = TextColor.color(0xFFFFFF)
    private val gold = TextColor.color(0xFFD66A)
    private val spacing = DialogTextLayout.spacing
    private val columnWidth = DialogTextLayout.glyphWidth(GLYPH) + GAP

    fun render(heading: Component, body: Component, keepHeading: Boolean = false): Component {
        val readable = normalizeLeadingWhitespace(normalize(body).colorIfAbsent(white))
        val layout = DialogTextLayout.layout(readable, TextAlignment.LEFT, 253)
        val wrapped = normalizeLeadingWhitespace((layout as? TextLayoutResult.Aligned)?.component ?: readable)
        val rows = (layout as? TextLayoutResult.Aligned)?.lineCount
            ?: net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(wrapped).count { it == '\n' } + 1
        val showHeading = keepHeading || rows < 3
        val renderedHeading = normalizeLeadingWhitespace(normalize(heading).colorIfAbsent(gold))
        var content = if (showHeading) renderedHeading
            .append(Component.newline()).append(wrapped) else wrapped
        repeat((3 - rows - if (showHeading) 1 else 0).coerceAtLeast(0)) {
            content = content.append(Component.newline())
        }
        var row = 0
        val aligned = content.replaceText(TextReplacementConfig.builder().matchLiteral("\n")
            .replacement { _: TextComponent.Builder -> Component.newline().append(prefix(++row)) }.build())
        return Component.newline().append(prefix(0)).append(aligned).append(Component.newline())
    }

    private fun prefix(row: Int): Component = spacing.padding(INSET).append(
        if (row == 2) Component.text(GLYPH, white).font(Key.key("minecraft:default"))
            .decoration(TextDecoration.BOLD, false).append(spacing.padding(GAP))
        else spacing.padding(columnWidth),
    )

    private fun normalize(component: Component): Component {
        val color = component.color()
        return component.decoration(TextDecoration.BOLD, false)
            .color(if (color?.value() in setOf(0x8C8C8C, 0x969696, 0x666666)) white else color)
            .children(component.children().map(::normalize))
    }

    /** Remove source indentation after style/placeholder boundaries, retaining every hard line break. */
    private fun normalizeLeadingWhitespace(component: Component): Component {
        var lineStart = true

        fun visit(current: Component): Component {
            if (current !is TextComponent) {
                // Keep unknown component content and style intact; treat it as visible for following children.
                lineStart = false
                return current
            }

            val source = current.content()
            val content = StringBuilder(source.length)
            var index = 0
            while (index < source.length) {
                val codePoint = source.codePointAt(index)
                val charCount = Character.charCount(codePoint)
                when {
                    codePoint == '\n'.code || codePoint == '\r'.code -> {
                        content.appendCodePoint(codePoint)
                        lineStart = true
                    }
                    lineStart && (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) -> Unit
                    else -> {
                        content.appendCodePoint(codePoint)
                        lineStart = false
                    }
                }
                index += charCount
            }
            return current.content(content.toString()).children(current.children().map(::visit))
        }

        return visit(component)
    }
}
