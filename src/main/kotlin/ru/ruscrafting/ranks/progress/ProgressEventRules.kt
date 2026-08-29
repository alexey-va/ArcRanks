package ru.ruscrafting.ranks.progress

import org.bukkit.GameMode
import org.bukkit.Material

object ProgressEventRules {
    fun eligible(gameMode: GameMode): Boolean = gameMode == GameMode.SURVIVAL || gameMode == GameMode.ADVENTURE

    fun activeSample(gameMode: GameMode, idleSeconds: Long, maximumIdleSeconds: Long): Boolean {
        require(idleSeconds >= 0) { "Idle seconds must not be negative" }
        require(maximumIdleSeconds >= 0) { "Maximum idle seconds must not be negative" }
        return eligible(gameMode) && idleSeconds <= maximumIdleSeconds
    }

    fun isMatureCrop(material: Material, age: Int?, maximumAge: Int?): Boolean =
        material.name in CROP_MATERIALS && age != null && maximumAge != null && maximumAge >= 0 && age >= maximumAge

    private val CROP_MATERIALS = setOf(
        "WHEAT",
        "CARROTS",
        "POTATOES",
        "BEETROOTS",
        "NETHER_WART",
        "COCOA",
        "SWEET_BERRY_BUSH",
        "PITCHER_CROP",
        "TORCHFLOWER_CROP",
    )
}
