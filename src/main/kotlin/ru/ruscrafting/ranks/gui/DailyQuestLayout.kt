package ru.ruscrafting.ranks.gui

/** One content row holds seven readable cards; no pages or empty fixed-height board. */
data class DailyQuestLayout(val count: Int) {
    init { require(count in 1..21) }
    val rows: Int = (count + 6) / 7 + 2
    val size: Int = rows * 9
    val slots: List<Int> = (0 until count).map { 10 + (it / 7) * 9 + it % 7 }
    val footerOffset: Int = (rows - 3) * 9
}
