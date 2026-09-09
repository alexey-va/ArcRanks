package ru.ruscrafting.ranks.perk

import ru.ruscrafting.ranks.domain.MasteryLevel
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.domain.SpecializationPath

@JvmInline
value class PerkId(val value: String) {
    init {
        require(value.matches(Regex("[a-z0-9_-]{1,48}"))) { "Unsafe perk id: $value" }
    }

    override fun toString(): String = value
}

enum class PerkEffectKind {
    PROGRESS_BONUS,
    CONTRACT_TARGET_REDUCTION,
    CONTRACT_REWARD_BONUS,
    HARVEST_BONUS,
    HOE_PRESERVATION,
    PICKAXE_PRESERVATION,
    BUILDING_TOOL_PRESERVATION,
    SMELTING_XP,
    TRADE_XP,
    MOVEMENT_EXHAUSTION_REDUCTION,
    FALL_REDUCTION,
    NEARBY_XP,
    NEARBY_DEFENCE,
}

data class PerkDefinition(
    val id: PerkId,
    val path: SpecializationPath,
    val requiredMastery: MasteryLevel,
    val effect: PerkEffectKind,
    val basisPoints: Int,
    val nameKey: String,
    val descriptionKey: String,
    val progressBasisPoints: Int = 0,
) {
    init {
        require(requiredMastery != MasteryLevel.NONE) { "Perk mastery requirement must not be NONE" }
        require(basisPoints in 1..5_000) { "Perk basis points must be between 1 and 5000" }
        require(progressBasisPoints in 0..5_000) { "Progress basis points must be between 0 and 5000" }
        require(nameKey.isLocaleKey() && descriptionKey.isLocaleKey()) { "Perk locale keys must be safe" }
        require(path != SpecializationPath.TRADE || progressBasisPoints == 0) {
            "Trade path cannot receive a synthetic progress bonus"
        }
        require(effect != PerkEffectKind.PROGRESS_BONUS || path.metric != ProgressMetric.WEALTH_PEAK) {
            "High-water progress cannot receive a synthetic progress bonus"
        }
        require(effect != PerkEffectKind.CONTRACT_REWARD_BONUS || path == SpecializationPath.TRADE) {
            "Contract reward bonuses are reserved for the trade specialization"
        }
        require(perkEffectCap(effect) >= basisPoints) { "${effect.name} basis points exceed its cap" }
    }
}

class PerkCatalog(definitions: List<PerkDefinition>) {
    val perks = definitions.toList()
    private val byId = perks.associateBy(PerkDefinition::id)
    private val byPath = perks.groupBy(PerkDefinition::path)

    init {
        require(perks.size == SpecializationPath.entries.size * PERKS_PER_PATH) {
            "Perk catalog must contain exactly three perks per path"
        }
        require(byId.size == perks.size) { "Perk ids must be unique" }
        SpecializationPath.entries.forEach { path ->
            require(byPath[path]?.size == PERKS_PER_PATH) { "Path ${path.name} must contain exactly three perks" }
        }
    }

    fun require(id: PerkId): PerkDefinition = requireNotNull(byId[id]) { "Unknown perk id: $id" }

    fun contains(id: PerkId): Boolean = id in byId

    fun forPath(path: SpecializationPath): List<PerkDefinition> = byPath[path].orEmpty()

    fun effect(perks: Collection<PerkId>, path: SpecializationPath, kind: PerkEffectKind): Int = perks
        .asSequence()
        .map(::require)
        .filter { it.path == path && (kind == PerkEffectKind.PROGRESS_BONUS || it.effect == kind) }
        .sumOf {
            if (kind == PerkEffectKind.PROGRESS_BONUS) {
                it.progressBasisPoints + if (it.effect == kind) it.basisPoints else 0
            } else {
                it.basisPoints
            }
        }
        .coerceAtMost(5_000)

    fun gameplayEffect(perks: Collection<PerkId>, kind: PerkEffectKind): Int = perks
        .asSequence()
        .mapNotNull { id -> byId[id] }
        .filter { it.effect == kind }
        .maxOfOrNull(PerkDefinition::basisPoints)
        ?.coerceAtMost(perkEffectCap(kind))
        ?: 0

    private companion object {
        const val PERKS_PER_PATH = 3
    }
}

private fun perkEffectCap(effect: PerkEffectKind): Int = when (effect) {
    PerkEffectKind.HARVEST_BONUS -> 1_000
    PerkEffectKind.HOE_PRESERVATION,
    PerkEffectKind.PICKAXE_PRESERVATION,
    PerkEffectKind.BUILDING_TOOL_PRESERVATION -> 2_000
    PerkEffectKind.SMELTING_XP -> 1_500
    PerkEffectKind.TRADE_XP -> 3_000
    PerkEffectKind.MOVEMENT_EXHAUSTION_REDUCTION -> 2_000
    PerkEffectKind.FALL_REDUCTION -> 3_000
    PerkEffectKind.NEARBY_XP,
    PerkEffectKind.NEARBY_DEFENCE -> 1_000
    PerkEffectKind.PROGRESS_BONUS,
    PerkEffectKind.CONTRACT_TARGET_REDUCTION,
    PerkEffectKind.CONTRACT_REWARD_BONUS -> 5_000
}

private fun String.isLocaleKey(): Boolean = matches(Regex("[a-z0-9_.-]{1,120}"))
