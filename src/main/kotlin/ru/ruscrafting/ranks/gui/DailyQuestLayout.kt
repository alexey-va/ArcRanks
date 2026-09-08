package ru.ruscrafting.ranks.gui

/** One content row holds seven readable cards; no pages or empty fixed-height board. */
data class DailyQuestLayout(val count: Int) {
    init { require(count in 1..21) }
    val rows: Int = (count + 6) / 7 + 2
    val size: Int = rows * 9
    val slots: List<Int> = (0 until (count + 6) / 7).flatMap { row ->
        val remaining = minOf(7, count - row * 7)
        val columns = when (remaining) {
            1 -> listOf(4)
            2 -> listOf(3, 5)
            3 -> listOf(3, 4, 5)
            4 -> listOf(2, 3, 5, 6)
            5 -> (2..6).toList()
            6 -> listOf(1, 2, 3, 5, 6, 7)
            else -> (1..7).toList()
        }
        columns.map { (row + 1) * 9 + it }
    }
    val footerOffset: Int = (rows - 3) * 9
}
