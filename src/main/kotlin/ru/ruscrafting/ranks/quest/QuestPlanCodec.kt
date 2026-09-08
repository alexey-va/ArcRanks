package ru.ruscrafting.ranks.quest

/** Strict, dependency-free persistence format for immutable quest plans and their values. */
object QuestPlanCodec {
    private const val PLAN_VERSION = "QP1"
    private const val VALUES_VERSION = "QV1"
    private const val MAX_ENCODED_SIZE = 4_096
    private const val MAX_VALUES = 8
    private val UNSIGNED_DECIMAL = Regex("0|[1-9][0-9]*")

    fun encode(plan: QuestPlan): String {
        val encoded = buildString {
            append(PLAN_VERSION).append('|').append(plan.mode.name).append('|').append(plan.requiredDistinct).append('|')
            plan.steps.joinTo(this, ";") { step ->
                "${step.objective}~${step.target}~${step.textId}"
            }
        }
        return encoded.also(::validateSize)
    }

    fun decode(value: String): QuestPlan {
        validateSize(value)
        val fields = split(value, '|', expected = 4)
        require(fields[0] == PLAN_VERSION) { "Unsupported quest plan version" }
        val mode = runCatching { QuestMode.valueOf(fields[1]) }.getOrElse { throw IllegalArgumentException("Unknown quest mode") }
        val requiredDistinct = parseInt(fields[2], "requiredDistinct")
        val steps = split(fields[3], ';', expected = null).map { encodedStep ->
            val step = split(encodedStep, '~', expected = 3)
            QuestStep(step[0], parseLong(step[1], "target"), step[2])
        }
        return QuestPlan(mode, steps, requiredDistinct).also { decoded ->
            require(encode(decoded) == value) { "Non-canonical quest plan encoding" }
        }
    }

    fun encodeValues(values: List<Long>): String {
        require(values.isNotEmpty() && values.size <= MAX_VALUES) { "Quest values must contain 1..$MAX_VALUES entries" }
        values.forEach { require(it in 0..MAX_QUEST_TARGET) { "Quest value is outside supported bounds" } }
        val encoded = buildString {
            append(VALUES_VERSION).append('|')
            values.joinTo(this, ",")
        }
        return encoded.also(::validateSize)
    }

    fun decodeValues(value: String, plan: QuestPlan): List<Long> {
        val values = decodeValues(value, plan.steps.size)
        values.forEachIndexed { index, current ->
            require(current <= plan.steps[index].target) { "Quest value exceeds step target" }
        }
        plan.progress(values)
        return values
    }

    fun decodeValues(value: String, expectedSize: Int): List<Long> {
        require(expectedSize in 1..MAX_VALUES) { "Quest values must contain 1..$MAX_VALUES entries" }
        validateSize(value)
        val fields = split(value, '|', expected = 2)
        require(fields[0] == VALUES_VERSION) { "Unsupported quest values version" }
        val values = fields[1].split(',').map { parseLong(it, "value") }
        require(values.size == expectedSize) { "Quest state must contain exactly $expectedSize values" }
        return values.also { decoded -> require(encodeValues(decoded) == value) { "Non-canonical quest values encoding" } }
    }

    private fun split(value: String, delimiter: Char, expected: Int?): List<String> {
        val fields = value.split(delimiter)
        require(fields.none(String::isEmpty)) { "Malformed quest encoding" }
        if (expected != null) require(fields.size == expected) { "Malformed quest encoding" }
        return fields
    }

    private fun parseInt(value: String, field: String): Int {
        require(value.matches(UNSIGNED_DECIMAL)) { "Malformed quest $field" }
        return value.toIntOrNull() ?: throw IllegalArgumentException("Quest $field is out of bounds")
    }

    private fun parseLong(value: String, field: String): Long {
        require(value.matches(UNSIGNED_DECIMAL)) { "Malformed quest $field" }
        return value.toLongOrNull() ?: throw IllegalArgumentException("Quest $field is out of bounds")
    }

    private fun validateSize(value: String) {
        require(value.length in 1..MAX_ENCODED_SIZE) { "Quest encoding exceeds $MAX_ENCODED_SIZE characters" }
    }
}
