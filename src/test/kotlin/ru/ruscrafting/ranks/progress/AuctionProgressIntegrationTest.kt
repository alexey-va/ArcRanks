package ru.ruscrafting.ranks.progress

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.util.UUID

class AuctionProgressIntegrationTest : StringSpec({
    "completed auction sales credit both identities using capped whole-price progress" {
        val seller = UUID.randomUUID()
        val buyer = UUID.randomUUID()

        decodeAuctionSale(
            FakeAuctionEvent(FakeAuctionItem(42, seller, buyer, BigDecimal("125000.99"), FakeStatus.PURCHASED)),
            maximumProgress = 100_000,
        ) shouldBe AuctionSaleProgress(42, seller, buyer, 100_000)
    }

    "non-purchased and zero-price removals do not create trade progress" {
        val seller = UUID.randomUUID()
        val buyer = UUID.randomUUID()

        decodeAuctionSale(
            FakeAuctionEvent(FakeAuctionItem(1, seller, buyer, BigDecimal.TEN, FakeStatus.REMOVED)),
            maximumProgress = 100_000,
        ) shouldBe null
        decodeAuctionSale(
            FakeAuctionEvent(FakeAuctionItem(2, seller, buyer, BigDecimal.ZERO, FakeStatus.PURCHASED)),
            maximumProgress = 100_000,
        ) shouldBe null
        decodeAuctionSale(
            FakeAuctionEvent(FakeAuctionItem(3, seller, seller, BigDecimal.TEN, FakeStatus.PURCHASED)),
            maximumProgress = 100_000,
        ) shouldBe null
    }
})

data class FakeAuctionEvent(val item: FakeAuctionItem)

data class FakeAuctionItem(
    val id: Int,
    val sellerUniqueId: UUID,
    val buyerUniqueId: UUID,
    val price: BigDecimal,
    val status: FakeStatus,
)

enum class FakeStatus { PURCHASED, REMOVED }
