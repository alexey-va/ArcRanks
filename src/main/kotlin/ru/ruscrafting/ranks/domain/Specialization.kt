package ru.ruscrafting.ranks.domain

enum class MasteryLevel {
    NONE,
    I,
    II,
    III,
}

data class MasteryThresholds(
    val levelOne: Long,
    val levelTwo: Long,
    val levelThree: Long,
) {
    init {
        require(levelOne >= 0) { "Mastery thresholds must not be negative" }
        require(levelOne < levelTwo && levelTwo < levelThree) { "Mastery thresholds must be strictly increasing" }
    }
}

data class PlayerProgressProfile(
    val progress: ProgressSnapshot,
    val selectedFocus: SpecializationPath,
) {
    fun selectFocus(path: SpecializationPath): PlayerProgressProfile = copy(selectedFocus = path)
}

object MasteryEvaluator {
    fun level(value: Long, thresholds: MasteryThresholds): MasteryLevel = when {
        value >= thresholds.levelThree -> MasteryLevel.III
        value >= thresholds.levelTwo -> MasteryLevel.II
        value >= thresholds.levelOne -> MasteryLevel.I
        else -> MasteryLevel.NONE
    }

    fun level(
        profile: PlayerProgressProfile,
        path: SpecializationPath,
        thresholds: MasteryThresholds,
    ): MasteryLevel = level(path.progressValue(profile.progress), thresholds)
}
