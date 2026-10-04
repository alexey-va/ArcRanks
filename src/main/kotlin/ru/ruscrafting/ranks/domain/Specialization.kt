package ru.ruscrafting.ranks.domain

enum class MasteryLevel {
    NONE,
    I,
    II,
    III,
    IV,
    V,
    VI,
}

data class MasteryThresholds(
    val levelOne: Long,
    val levelTwo: Long,
    val levelThree: Long,
    val advancedLevels: List<Long> = emptyList(),
    val activeMinutes: List<Long> = List(3 + advancedLevels.size) { 0L },
) {
    val values: List<Long> = listOf(levelOne, levelTwo, levelThree) + advancedLevels

    init {
        require(values.size == 3 || values.size == 6) { "Mastery must define three or six levels" }
        require(values.all { it >= 0 } && values.zipWithNext().all { (a, b) -> a < b }) {
            "Mastery thresholds must be nonnegative and strictly increasing"
        }
        require(activeMinutes.size == values.size && activeMinutes.all { it >= 0 } &&
            activeMinutes.zipWithNext().all { (a, b) -> a <= b }) { "Invalid mastery active-time requirements" }
        require(activeMinutes.take(3).all { it == 0L }) { "Existing mastery levels must retain their eligibility" }
    }

    fun requiredValue(level: MasteryLevel): Long = values[level.ordinal - 1]
    fun requiredActiveMinutes(level: MasteryLevel): Long = activeMinutes[level.ordinal - 1]
}

data class PlayerProgressProfile(
    val progress: ProgressSnapshot,
    val selectedFocus: SpecializationPath,
) {
    fun selectFocus(path: SpecializationPath): PlayerProgressProfile = copy(selectedFocus = path)
}

object MasteryEvaluator {
    fun level(value: Long, thresholds: MasteryThresholds): MasteryLevel =
        MasteryLevel.entries[thresholds.values.indices.takeWhile {
            value >= thresholds.values[it] && thresholds.activeMinutes[it] == 0L
        }.size]

    fun progressValue(profile: PlayerProgressProfile, path: SpecializationPath, level: MasteryLevel): Long =
        if (path == SpecializationPath.TRADE && level.ordinal >= MasteryLevel.IV.ordinal) {
            profile.progress.value(ProgressMetric.TRADE_ACTIONS)
        } else path.progressValue(profile.progress)

    fun level(
        profile: PlayerProgressProfile,
        path: SpecializationPath,
        thresholds: MasteryThresholds,
    ): MasteryLevel = MasteryLevel.entries[thresholds.values.indices.takeWhile { index ->
        progressValue(profile, path, MasteryLevel.entries[index + 1]) >= thresholds.values[index] &&
            profile.progress.value(ProgressMetric.ACTIVE_MINUTES) >= thresholds.activeMinutes[index]
    }.size]
}
