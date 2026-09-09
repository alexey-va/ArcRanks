package ru.ruscrafting.ranks.dialog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.format.NamedTextColor

class RankBenefitLayoutTest : FunSpec({
    val plain = PlainTextComponentSerializer.plainText()
    test("field and value span styled siblings without losing amounts or formatting") {
        val source = MiniMessage.miniMessage().deserialize("<gray>•</gray> <white>Приваты: </white><gold>5</gold><gray> · бесплатно </gray><gold>256 чанков</gold>")
        val row = requireNotNull(RankBenefitLayout.row(source))
        plain.serialize(row.first) shouldBe "Приваты"
        plain.serialize(row.second) shouldBe "5 · бесплатно 256 чанков"
        row.second.children().first().color() shouldBe NamedTextColor.GOLD
    }
    test("prose and non-text components remain whole rather than inventing a value") {
        RankBenefitLayout.row(Component.text("Все пути открыты")) shouldBe null
        RankBenefitLayout.row(Component.translatable("item.minecraft.diamond")) shouldBe null
        RankBenefitLayout.row(Component.text("• ")) shouldBe null
    }
})
