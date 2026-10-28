package ru.ruscrafting.ranks.contract

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

class NativeCrateKeyRewardTest : StringSpec({
    val key = mockk<ItemStack> {
        every { amount } returns 2
        every { maxStackSize } returns 64
    }
    fun stack(amount: Int, similar: Boolean) = mockk<ItemStack> {
        every { type } returns Material.TRIPWIRE_HOOK
        every { this@mockk.amount } returns amount
        every { isSimilar(key) } returns similar
    }

    "full inventory defers the whole key reward without partial mutation" {
        NativeCrateKeyReward.canFit(arrayOf(stack(64, true), stack(1, false)), key, 64) shouldBe false
        NativeCrateKeyReward.canFit(arrayOf(stack(63, true), stack(1, false)), key, 64) shouldBe false
    }

    "matching native metadata or an empty slot can hold the full reward" {
        NativeCrateKeyReward.canFit(arrayOf(stack(62, true)), key, 64) shouldBe true
        NativeCrateKeyReward.canFit(arrayOf(null), key, 64) shouldBe true
        NativeCrateKeyReward.canFit(arrayOf(stack(62, false)), key, 64) shouldBe false
    }
})
