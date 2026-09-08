package ru.ruscrafting.ranks.reward

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import ru.ruscrafting.ranks.contract.ActiveContract
import ru.ruscrafting.ranks.contract.ContractBonusReward
import ru.ruscrafting.ranks.contract.ContractCycle
import ru.ruscrafting.ranks.contract.ContractId
import ru.ruscrafting.ranks.contract.ContractRewardComponent
import ru.ruscrafting.ranks.contract.rewardIdentity
import ru.ruscrafting.ranks.domain.SpecializationPath
import java.time.LocalDate

class RankRewardIdentityTest : StringSpec({
    val contract = ActiveContract(
        ContractId("aaaaaaaaaaaaaaaaaaaaaaaa"),
        ContractCycle(LocalDate.parse("2026-08-31")),
        0,
        SpecializationPath.COMMUNITY,
        0,
        30,
        3,
        30,
        bonusReward = ContractBonusReward.FIRST,
    )
    val component = ContractRewardComponent.Money(2_000)

    "contract namespace retains the legacy identity while daily is isolated" {
        val legacy = contract.rewardIdentity(component)
        RankReward(contract.id.value, "contract", listOf(component)).identity(component) shouldBe legacy

        val daily = RankReward(contract.id.value, "daily", listOf(component)).identity(component)
        daily.useId shouldNotBe legacy.useId
        daily.fingerprint shouldNotBe legacy.fingerprint
    }

    "changing a component amount conflicts on the same stable use id" {
        val reward = RankReward("daily-reward", "daily", listOf(component))
        val changed = RankReward("daily-reward", "daily", listOf(ContractRewardComponent.Money(3_000)))

        changed.identity(changed.components.single()).useId shouldBe reward.identity(component).useId
        changed.identity(changed.components.single()).fingerprint shouldNotBe reward.identity(component).fingerprint
    }
})
