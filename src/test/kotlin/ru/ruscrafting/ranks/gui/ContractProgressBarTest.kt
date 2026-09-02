package ru.ruscrafting.ranks.gui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class ContractProgressBarTest : StringSpec({
    "contract progress bar is bounded and uses ten stable square segments" {
        val plain = PlainTextComponentSerializer.plainText()

        plain.serialize(ContractProgressBar.render(0, 180)) shouldBe "□□□□□□□□□□"
        plain.serialize(ContractProgressBar.render(92, 180)) shouldBe "■■■■■□□□□□"
        plain.serialize(ContractProgressBar.render(180, 180)) shouldBe "■■■■■■■■■■"
        plain.serialize(ContractProgressBar.render(500, 180)) shouldBe "■■■■■■■■■■"
    }

    "completed and remaining segments use the approved green and pale colors" {
        val children = ContractProgressBar.render(92, 180).children()

        children[0].color() shouldBe TextColor.fromHexString("#2bba43")
        children[1].color() shouldBe TextColor.fromHexString("#e6fff3")
    }
})
