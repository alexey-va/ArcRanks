package ru.ruscrafting.ranks.quest

internal const val MAX_QUEST_TARGET = 1_000_000_000L

enum class QuestMode {
    CHAIN,
    ALL,
    ANY,
    DISTINCT,
}

data class QuestStep(
    val objective: String,
    val target: Long,
    val textId: String,
) {
    init {
        require(objective.matches(OBJECTIVE_PATTERN)) { "Unsafe quest objective" }
        require(target in 1..MAX_QUEST_TARGET) { "Quest target must be between 1 and $MAX_QUEST_TARGET" }
        require(textId.matches(TEXT_ID_PATTERN)) { "Unsafe quest text id" }
    }

    private companion object {
        val OBJECTIVE_PATTERN = Regex("[a-z0-9_.:-]{1,96}")
        val TEXT_ID_PATTERN = Regex("[a-z0-9_-]{1,32}")
    }
}

data class QuestPlan(
    val mode: QuestMode,
    val steps: List<QuestStep>,
    val requiredDistinct: Int = steps.size,
) {
    init {
        require(steps.isNotEmpty() && steps.size <= MAX_STEPS) { "Quest plan must contain 1..$MAX_STEPS steps" }
        require(requiredDistinct in 1..steps.size) { "Required distinct steps must be between 1 and ${steps.size}" }
        if (mode != QuestMode.DISTINCT) {
            require(requiredDistinct == steps.size) { "Only DISTINCT plans may change requiredDistinct" }
        }
        require(steps.indices.none { index ->
            steps.drop(index + 1).any { overlaps(steps[index].objective, it.objective) }
        }) { "Quest plan objectives must not overlap" }
    }

    val initialValues: List<Long>
        get() = steps.map { 0L }

    val target: Long
        get() = when (mode) {
            QuestMode.ANY -> 1L
            QuestMode.DISTINCT -> requiredDistinct.toLong()
            QuestMode.CHAIN,
            QuestMode.ALL,
            -> steps.size.toLong()
        }

    fun advance(values: List<Long>, objective: String, amount: Long): List<Long> {
        validateValues(values)
        require(objective.matches(OBJECTIVE_PATTERN)) { "Unsafe quest objective" }
        require(amount > 0) { "Quest advance amount must be positive" }
        return when (mode) {
            QuestMode.CHAIN -> {
                val index = steps.indices.firstOrNull { values[it] < steps[it].target }
                if (index == null || !matches(steps[index].objective, objective)) values
                else values.toMutableList().also { updated ->
                    updated[index] = advanceValue(updated[index], steps[index].target, amount)
                }
            }
            QuestMode.ALL,
            QuestMode.ANY,
            QuestMode.DISTINCT,
            -> values.mapIndexed { index, value ->
                if (matches(steps[index].objective, objective)) advanceValue(value, steps[index].target, amount) else value
            }
        }
    }

    fun completed(values: List<Long>): Boolean {
        validateValues(values)
        val completed = completedSteps(values)
        return when (mode) {
            QuestMode.ANY -> completed > 0
            QuestMode.DISTINCT -> completed >= requiredDistinct
            QuestMode.CHAIN,
            QuestMode.ALL,
            -> completed == steps.size
        }
    }

    fun progress(values: List<Long>): Long {
        validateValues(values)
        val completed = completedSteps(values).toLong()
        return when (mode) {
            QuestMode.ANY -> completed.coerceAtMost(1L)
            QuestMode.DISTINCT -> completed.coerceAtMost(requiredDistinct.toLong())
            QuestMode.CHAIN,
            QuestMode.ALL,
            -> completed
        }
    }

    fun scaled(percent: Int): QuestPlan {
        require(percent in 1..MAX_SCALE_PERCENT) { "Quest scale must be between 1 and $MAX_SCALE_PERCENT percent" }
        return copy(steps = steps.map { step ->
            step.copy(target = ((step.target * percent) + 99L) / 100L)
        })
    }

    private fun validateValues(values: List<Long>) {
        require(values.size == steps.size) { "Quest state must contain exactly ${steps.size} values" }
        values.forEachIndexed { index, value ->
            require(value in 0..steps[index].target) { "Quest value is outside step target" }
        }
        if (mode == QuestMode.CHAIN) {
            require(steps.indices.drop(1).none { values[it] >= steps[it].target && values[it - 1] < steps[it - 1].target }) {
                "Quest chain state contains a completed step before its predecessor"
            }
        }
    }

    private fun completedSteps(values: List<Long>): Int = values.indices.count { values[it] >= steps[it].target }

    private fun advanceValue(value: Long, target: Long, amount: Long): Long =
        if (Long.MAX_VALUE - value < amount) target else (value + amount).coerceAtMost(target)

    private companion object {
        const val MAX_STEPS = 8
        const val MAX_SCALE_PERCENT = 10_000
        val OBJECTIVE_PATTERN = Regex("[a-z0-9_.:-]{1,96}")

        fun matches(stepObjective: String, eventObjective: String): Boolean =
            stepObjective == eventObjective || eventObjective.startsWith("$stepObjective:")

        fun overlaps(first: String, second: String): Boolean =
            first == second || first.startsWith("$second:") || second.startsWith("$first:")
    }
}
