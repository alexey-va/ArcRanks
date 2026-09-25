package ru.ruscrafting.ranks.text

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.text.TextAlignment
import ru.arc.text.TextLayoutResult
import kotlin.math.abs

/** Rank chat branding over the shared pack-aware text layout. Never clips long results. */
internal object RankChatNotice {
    private const val GLYPH = '\uE52A' // arc:rank_chevron_large, height 27, ascent 26
    private const val INSET = 0
    private const val GAP = 3
    private const val WRAP_WIDTH = 253
    private val white = TextColor.color(0xFFFFFF)
    private val gold = TextColor.color(0xFFD66A)
    private val spacing = DialogTextLayout.spacing
    private val columnWidth = DialogTextLayout.glyphWidth(GLYPH) + GAP
    private val plainText = PlainTextComponentSerializer.plainText()

    fun render(heading: Component, body: Component, keepHeading: Boolean = false): Component {
        val readable = normalizeLeadingWhitespace(normalize(body).colorIfAbsent(white))
        val layout = DialogTextLayout.layout(readable, TextAlignment.LEFT, WRAP_WIDTH)
        val wrapped = normalizeLeadingWhitespace((layout as? TextLayoutResult.Aligned)?.component ?: readable)
        val originalBodyRows = (layout as? TextLayoutResult.Aligned)?.lineCount
            ?: plainText.serialize(wrapped).count { it == '\n' } + 1
        val splitBody = if (originalBodyRows == 1) splitAtMiddleWordBoundary(wrapped) else null
        val visibleBody = splitBody ?: wrapped
        val bodyRows = originalBodyRows + if (splitBody != null) 1 else 0
        val bodyIsEmpty = plainText.serialize(visibleBody).isBlank()
        val showHeading = keepHeading || bodyRows < 3
        val renderedHeading = normalizeLeadingWhitespace(normalize(heading).colorIfAbsent(gold))
        val headingRows = if (showHeading) plainText.serialize(renderedHeading).count { it == '\n' } + 1 else 0
        val content = when {
            showHeading && !bodyIsEmpty -> renderedHeading.append(Component.newline()).append(visibleBody)
            showHeading -> renderedHeading
            else -> visibleBody
        }
        val contentRows = headingRows + if (bodyIsEmpty && showHeading) 0 else bodyRows
        val glyphRow = (contentRows - 1).coerceAtLeast(0).coerceAtMost(2)
        var row = 0
        val aligned = content.replaceText(TextReplacementConfig.builder().matchLiteral("\n")
            .replaceInsideHoverEvents(false)
            .replacement { _: TextComponent.Builder -> Component.newline().append(prefix(++row, glyphRow)) }.build())
        return Component.newline().append(prefix(0, glyphRow)).append(aligned).append(Component.newline())
    }

    /** Rank notices balance a one-line body into two useful rows; shared width wrapping remains in DialogTextLayout. */
    private fun splitAtMiddleWordBoundary(component: Component): Component? {
        val text = plainText.serialize(component)
        if ('\n' in text || '\r' in text) return null

        val hasTextAfter = BooleanArray(text.length + 1)
        var hasText = false
        for (index in text.lastIndex downTo 0) {
            if (!text[index].isWhitespace()) hasText = true
            hasTextAfter[index] = hasText
        }
        val candidates = mutableListOf<Int>()
        hasText = false
        for (index in text.indices) {
            if (text[index] == ' ' && (index == 0 || text[index - 1] != ' ') && hasText && hasTextAfter[index + 1]) {
                candidates += index
            }
            if (!text[index].isWhitespace()) hasText = true
        }
        val splitIndex = candidates.minByOrNull { abs(it - text.trimEnd().length / 2.0) } ?: return null
        val splitOrdinal = text.take(splitIndex + 1).count { it == ' ' }
        var seenSpaces = 0
        var replaced = false
        val split = component.replaceText(TextReplacementConfig.builder().matchLiteral(" ")
            .replaceInsideHoverEvents(false)
            .replacement { matched ->
                if (++seenSpaces == splitOrdinal) {
                    replaced = true
                    Component.newline()
                } else {
                    matched.build()
                }
            }.build())
        return if (replaced) normalizeLeadingWhitespace(split) else null
    }

    private fun prefix(row: Int, glyphRow: Int): Component = spacing.padding(INSET).append(
        if (row == glyphRow) Component.text(GLYPH, white).font(Key.key("minecraft:default"))
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
