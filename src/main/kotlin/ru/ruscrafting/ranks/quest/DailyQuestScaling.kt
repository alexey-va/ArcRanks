package ru.ruscrafting.ranks.quest

/** Integer percentages keep assignment rounding stable across backends. */
data class DailyQuestScaling(
    val targetPercent: Int = 100,
    val moneyPercent: Int = 100,
    val bonusPercent: Int = 100,
    val rareTokens: Long? = null,
) {
    init {
        require(targetPercent in 100..1000 && moneyPercent in 100..1000 && bonusPercent in 100..1000)
        require(rareTokens == null || rareTokens in 1..1000)
    }

    fun apply(quest: DailyQuest): DailyQuest = quest.copy(
        target = quest.plan?.target ?: if (quest.scaleTarget) scale(quest.target, targetPercent) else quest.target,
        plan = quest.plan?.let { if (quest.scaleTarget) it.scaled(targetPercent) else it },
        money = money(quest.money),
        bonus = scale(quest.bonus, bonusPercent),
    )

    fun money(base: Long): Long = scale(base, moneyPercent)

    private fun scale(base: Long, percent: Int): Long = (Math.multiplyExact(base, percent.toLong()) + 99) / 100
}
