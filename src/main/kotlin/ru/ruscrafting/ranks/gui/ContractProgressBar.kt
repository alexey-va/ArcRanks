package ru.ruscrafting.ranks.gui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration

object ContractProgressBar {
    private const val SEGMENTS = 10
    private val completeColor = requireNotNull(TextColor.fromHexString("#2bba43"))
    private val remainingColor = requireNotNull(TextColor.fromHexString("#e6fff3"))

    fun render(value: Long, target: Long): Component {
        require(target > 0) { "Contract progress target must be positive" }
        val bounded = value.coerceIn(0, target)
        val complete = ((bounded.toDouble() / target.toDouble()) * SEGMENTS).toInt().coerceIn(0, SEGMENTS)
        return Component.text()
            .decoration(TextDecoration.ITALIC, false)
            .append(Component.text("■".repeat(complete), completeColor))
            .append(Component.text("□".repeat(SEGMENTS - complete), remainingColor))
            .build()
    }
}
