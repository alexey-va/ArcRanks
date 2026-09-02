package ru.ruscrafting.ranks.contract

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import ru.ruscrafting.ranks.domain.PathAvailability
import ru.ruscrafting.ranks.domain.ProgressSnapshot
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.perk.PerkCatalogLoader
import ru.ruscrafting.ranks.perk.PerkId
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class ContractOfferGeneratorTest : StringSpec({
    val contracts = ContractCatalogLoader(Config(Files.createTempDirectory("arcranks-contracts"), "contracts.yml")).load()
    val perks = PerkCatalogLoader(Config(Files.createTempDirectory("arcranks-contract-perks"), "perks.yml")).load()
    val generator = ContractOfferGenerator(contracts, perks)
    val player = UUID.fromString("00000000-0000-0000-0000-000000000001")

    "cycles start on Monday UTC and offers are stable" {
        ContractCycle.at(Instant.parse("2026-08-30T23:59:59Z")).start shouldBe LocalDate.parse("2026-08-24")
        ContractCycle.at(Instant.parse("2026-08-31T00:00:00Z")).start shouldBe LocalDate.parse("2026-08-31")
        val cycle = ContractCycle(LocalDate.parse("2026-08-24"))
        val context = context()

        generator.offers(player, cycle, 0, 0, context) shouldBe
            generator.offers(player, cycle, 0, 0, context)
        generator.offers(player, cycle, 3, 0, context) shouldBe emptyList()
    }

    "focus and nearest incomplete paths lead three distinct available offers" {
        val offers = generator.offers(
            player,
            ContractCycle(LocalDate.parse("2026-08-24")),
            0,
            0,
            context(
                selected = SpecializationPath.BUILDING,
                nearest = SpecializationPath.EXPLORATION,
                unavailable = setOf(SpecializationPath.TRADE),
            ),
        )

        offers.map(ContractOffer::path).take(2).shouldContainExactly(
            SpecializationPath.BUILDING,
            SpecializationPath.EXPLORATION,
        )
        offers.map(ContractOffer::path).distinct().size shouldBe 3
        offers.none { it.path == SpecializationPath.TRADE } shouldBe true
    }

    "reroll changes identities and rank plus perk scale target deterministically" {
        val cycle = ContractCycle(LocalDate.parse("2026-08-24"))
        val base = generator.offers(player, cycle, 0, 0, context(rankOrder = 1))
        val scaled = generator.offers(
            player,
            cycle,
            0,
            0,
            context(rankOrder = 3, perks = setOf(PerkId("farming_contract"))),
        )
        val rerolled = generator.offers(player, cycle, 0, 1, context(rankOrder = 1))

        rerolled.map(ContractOffer::id) shouldBe rerolled.map(ContractOffer::id).distinct()
        (rerolled.map(ContractOffer::id) == base.map(ContractOffer::id)) shouldBe false
        (scaled.first { it.path == SpecializationPath.FARMING }.targetDelta <
            generator.offers(player, cycle, 0, 0, context(rankOrder = 3))
                .first { it.path == SpecializationPath.FARMING }.targetDelta) shouldBe true
        (scaled.first { it.path == SpecializationPath.FARMING }.targetDelta >
            base.first { it.path == SpecializationPath.FARMING }.targetDelta) shouldBe true
    }

    "each weekly completion snapshots a visible escalating bonus bundle" {
        val cycle = ContractCycle(LocalDate.parse("2026-08-24"))

        val first = generator.offers(player, cycle, 0, 0, context()).first().bonusReward
        val second = generator.offers(player, cycle, 1, 0, context()).first().bonusReward
        val third = generator.offers(player, cycle, 2, 0, context()).first().bonusReward

        first shouldBe ContractBonusReward(2_000, 1, "tokens", "enchant_token", 1)
        second shouldBe ContractBonusReward(3_500, 2, "tokens", "potion_token", 1)
        third shouldBe ContractBonusReward(5_000, 3, "tokens", "sf_lootbox", 1)
    }

    "one coherent contract and perk configuration is captured per offer board" {
        val reloadedContracts = contracts.copy(
            baseTargets = contracts.baseTargets.mapValues { (_, target) -> target * 2 },
        )
        val reloadedPerks = ru.ruscrafting.ranks.perk.PerkCatalog(
            perks.perks.map { perk ->
                if (perk.id == PerkId("farming_contract")) perk.copy(basisPoints = 5_000) else perk
            },
        )
        var currentConfiguration = ContractOfferConfiguration(contracts, perks)
        var configurationReads = 0
        val dynamicGenerator = ContractOfferGenerator {
            configurationReads++
            currentConfiguration
        }
        val cycle = ContractCycle(LocalDate.parse("2026-08-24"))
        val offerContext = context(perks = setOf(PerkId("farming_contract")))

        val initial = dynamicGenerator.offers(player, cycle, 0, 0, offerContext)
        currentConfiguration = ContractOfferConfiguration(reloadedContracts, reloadedPerks)
        val reloaded = dynamicGenerator.offers(player, cycle, 0, 0, offerContext)

        configurationReads shouldBe 2
        initial.first { it.path == SpecializationPath.FARMING }.targetDelta shouldBe 102L
        reloaded.first { it.path == SpecializationPath.FARMING }.targetDelta shouldBe 120L
    }
})

private fun context(
    rankOrder: Int = 1,
    selected: SpecializationPath = SpecializationPath.FARMING,
    nearest: SpecializationPath? = SpecializationPath.INDUSTRY,
    unavailable: Set<SpecializationPath> = emptySet(),
    perks: Set<PerkId> = emptySet(),
) = ContractPlayerContext(
    rankOrder = rankOrder,
    selectedFocus = selected,
    nearestIncompletePath = nearest,
    progress = ProgressSnapshot.EMPTY,
    availability = PathAvailability(unavailable),
    activePerks = perks,
)
