package ru.ruscrafting.ranks.contract

import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.perk.PerkCatalog
import ru.ruscrafting.ranks.perk.PerkEffectKind
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

class ContractOfferGenerator(
    private val contracts: ContractCatalog,
    private val perks: PerkCatalog,
) {
    fun offers(
        playerId: UUID,
        cycle: ContractCycle,
        generation: Int,
        rerollNonce: Int,
        context: ContractPlayerContext,
    ): List<ContractOffer> {
        require(generation in 0..ContractOffer.MAX_CONTRACTS_PER_CYCLE) { "Contract generation must be between 0 and 3" }
        require(rerollNonce in 0..1) { "Contract reroll nonce must be 0 or 1" }
        if (generation == ContractOffer.MAX_CONTRACTS_PER_CYCLE) return emptyList()

        val available = SpecializationPath.entries.filter(context.availability::isAvailable)
        val ordered = buildList {
            if (context.selectedFocus in available) add(context.selectedFocus)
            context.nearestIncompletePath?.takeIf { it in available && it !in this }?.let(::add)
            available.asSequence()
                .filterNot { it in this }
                .sortedBy { stableHash("$playerId|${cycle.start}|$generation|$rerollNonce|${it.name}") }
                .forEach(::add)
        }.take(OFFER_COUNT)

        return ordered.map { path ->
            val target = target(path, context)
            val reward = reward(path, target, context)
            val identity = stableHash(
                "$playerId|${cycle.start}|$generation|$rerollNonce|${path.name}|$target|$reward",
            ).take(24)
            ContractOffer(
                ContractId(identity),
                cycle,
                generation,
                rerollNonce,
                path,
                target,
                reward,
            )
        }
    }

    private fun target(path: SpecializationPath, context: ContractPlayerContext): Long {
        val rankBasisPoints = Math.addExact(
            BASIS_POINT_SCALE,
            Math.multiplyExact((context.rankOrder - 1).toLong(), contracts.rankScaleBasisPoints.toLong()),
        )
        val scaled = multiplyDivideCeil(contracts.baseTargets.getValue(path), rankBasisPoints)
        val reduction = perks.effect(context.activePerks, path, PerkEffectKind.CONTRACT_TARGET_REDUCTION)
        return multiplyDivideCeil(scaled, BASIS_POINT_SCALE - reduction).coerceAtLeast(1)
    }

    private fun reward(path: SpecializationPath, target: Long, context: ContractPlayerContext): Long {
        val base = Math.multiplyExact(target, contracts.rewardBasisPoints.toLong()) / BASIS_POINT_SCALE
        val reward = base.coerceAtLeast(1)
        val bonus = perks.effect(context.activePerks, path, PerkEffectKind.CONTRACT_REWARD_BONUS)
        return Math.addExact(reward, Math.multiplyExact(reward, bonus.toLong()) / BASIS_POINT_SCALE)
    }

    private fun multiplyDivideCeil(value: Long, basisPoints: Long): Long {
        val product = Math.multiplyExact(value, basisPoints)
        return Math.addExact(product, BASIS_POINT_SCALE - 1) / BASIS_POINT_SCALE
    }

    private fun stableHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val OFFER_COUNT = 3
        const val BASIS_POINT_SCALE = 10_000L
    }
}
