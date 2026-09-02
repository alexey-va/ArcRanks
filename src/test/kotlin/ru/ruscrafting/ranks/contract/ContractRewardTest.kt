package ru.ruscrafting.ranks.contract

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import ru.ruscrafting.ranks.domain.SpecializationPath
import java.time.LocalDate

class ContractRewardTest : StringSpec({
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

    "bonus reward expands into money tokens and item components" {
        contract.bonusReward.components() shouldBe listOf(
            ContractRewardComponent.Money(2_000),
            ContractRewardComponent.Tokens(1, "tokens"),
            ContractRewardComponent.Item(1, "enchant_token"),
        )
    }

    "one-time identities are stable per contract component and conflict if its value changes" {
        val money = contract.rewardIdentity(ContractRewardComponent.Money(2_000))
        contract.rewardIdentity(ContractRewardComponent.Money(2_000)) shouldBe money
        contract.rewardIdentity(ContractRewardComponent.Tokens(1, "tokens")).useId shouldNotBe money.useId

        val changed = contract.rewardIdentity(ContractRewardComponent.Money(3_000))
        changed.useId shouldBe money.useId
        changed.fingerprint shouldNotBe money.fingerprint
    }
})
