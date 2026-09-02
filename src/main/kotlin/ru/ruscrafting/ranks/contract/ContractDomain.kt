package ru.ruscrafting.ranks.contract

import ru.ruscrafting.ranks.domain.PathAvailability
import ru.ruscrafting.ranks.domain.ProgressSnapshot
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.perk.PerkId
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

@JvmInline
value class ContractId(val value: String) {
    init {
        require(value.matches(Regex("[a-f0-9]{24}"))) { "Unsafe contract id: $value" }
    }

    override fun toString(): String = value
}

data class ContractCycle(val start: LocalDate) {
    init {
        require(start.dayOfWeek == DayOfWeek.MONDAY) { "Contract cycle must start on Monday UTC" }
    }

    val endExclusive: LocalDate = start.plusWeeks(1)

    companion object {
        fun at(instant: Instant): ContractCycle {
            val day = LocalDate.ofInstant(instant, ZoneOffset.UTC)
            return ContractCycle(day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
        }
    }
}

data class ContractOffer(
    val id: ContractId,
    val cycle: ContractCycle,
    val generation: Int,
    val rerollNonce: Int,
    val path: SpecializationPath,
    val targetDelta: Long,
    val rewardDelta: Long,
    val bonusReward: ContractBonusReward = ContractBonusReward.FIRST,
) {
    init {
        require(generation in 0 until MAX_CONTRACTS_PER_CYCLE) { "Contract generation must be between 0 and 2" }
        require(rerollNonce in 0..1) { "Contract reroll nonce must be 0 or 1" }
        require(targetDelta > 0 && rewardDelta > 0) { "Contract target and reward must be positive" }
    }

    companion object {
        const val MAX_CONTRACTS_PER_CYCLE = 3
    }
}

data class ContractBonusReward(
    val money: Long,
    val tokens: Long,
    val tokenCurrency: String,
    val itemPreset: String,
    val itemAmount: Int,
) {
    init {
        require(money in 1..1_000_000) { "Contract money reward must be between 1 and 1000000" }
        require(tokens in 1..1_000) { "Contract token reward must be between 1 and 1000" }
        require(tokenCurrency.matches(Regex("[A-Za-z0-9_-]{1,16}"))) { "Unsafe contract token currency" }
        require(itemPreset.matches(Regex("[a-z0-9_-]{1,64}"))) { "Unsafe contract item preset" }
        require(itemAmount in 1..64) { "Contract item amount must be between 1 and 64" }
    }

    companion object {
        val FIRST = ContractBonusReward(2_000, 1, "tokens", "enchant_token", 1)
    }
}

data class ContractCatalog(
    val baseTargets: Map<SpecializationPath, Long>,
    val rankScaleBasisPoints: Int,
    val rewardBasisPoints: Int,
    val bonusRewards: List<ContractBonusReward>,
) {
    init {
        require(baseTargets.keys == SpecializationPath.entries.toSet()) { "Every contract path needs a base target" }
        require(baseTargets.values.all { it in 1..1_000_000_000 }) { "Contract base targets must be bounded and positive" }
        require(rankScaleBasisPoints in 0..5_000) { "Contract rank scaling must be between 0 and 5000 basis points" }
        require(rewardBasisPoints in 1..5_000) { "Contract reward must be between 1 and 5000 basis points" }
        require(bonusRewards.size == ContractOffer.MAX_CONTRACTS_PER_CYCLE) {
            "Every weekly contract completion needs one bonus reward"
        }
    }

    fun bonusReward(generation: Int): ContractBonusReward = bonusRewards[generation]
}

data class ContractPlayerContext(
    val rankOrder: Int,
    val selectedFocus: SpecializationPath,
    val nearestIncompletePath: SpecializationPath?,
    val progress: ProgressSnapshot,
    val availability: PathAvailability,
    val activePerks: Set<PerkId>,
) {
    init {
        require(rankOrder in 1..100) { "Rank order must be between 1 and 100" }
        require(activePerks.size <= 2) { "Contract context accepts at most two equipped perks" }
    }
}

data class ActiveContract(
    val id: ContractId,
    val cycle: ContractCycle,
    val generation: Int,
    val path: SpecializationPath,
    val baseline: Long,
    val targetDelta: Long,
    val rewardDelta: Long,
    val currentValue: Long,
    val adminCompleted: Boolean = false,
    val bonusReward: ContractBonusReward = ContractBonusReward.FIRST,
) {
    init {
        require(generation in 0 until ContractOffer.MAX_CONTRACTS_PER_CYCLE)
        require(baseline >= 0 && targetDelta > 0 && rewardDelta > 0 && currentValue >= 0)
    }

    val completed: Boolean
        get() = adminCompleted || currentValue >= requiredValue

    val requiredValue: Long
        get() = if (Long.MAX_VALUE - baseline < targetDelta) Long.MAX_VALUE else baseline + targetDelta

    val completedDelta: Long
        get() = if (adminCompleted) targetDelta else (currentValue - baseline).coerceIn(0, targetDelta)
}

data class ContractStoredBoard(
    val cycle: ContractCycle,
    val generation: Int,
    val rerollNonce: Int,
    val claimedStamps: Int,
    val active: ActiveContract?,
    val expiredOnLoad: Boolean = false,
) {
    init {
        require(generation in 0..ContractOffer.MAX_CONTRACTS_PER_CYCLE)
        require(rerollNonce in 0..1)
        require(claimedStamps in 0..ContractOffer.MAX_CONTRACTS_PER_CYCLE)
    }
}

data class ContractBoard(
    val cycle: ContractCycle,
    val generation: Int,
    val claimedStamps: Int,
    val active: ActiveContract?,
    val offers: List<ContractOffer>,
    val rerollAvailable: Boolean,
)
