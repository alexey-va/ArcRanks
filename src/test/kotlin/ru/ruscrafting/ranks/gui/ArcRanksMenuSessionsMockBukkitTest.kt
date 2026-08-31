package ru.ruscrafting.ranks.gui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.inventory.Inventory
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID

class ArcRanksMenuSessionsMockBukkitTest : StringSpec({
    "reload closes only open ArcRanks menu sessions" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val ranksPlayer = paper.addPlayer("RanksMenuPlayer")
                val otherPlayer = paper.addPlayer("OtherMenuPlayer")
                val generation = 42L
                val holder = TestArcRanksInventoryHolder(ranksPlayer.uniqueId, generation)
                val ranksInventory = Bukkit.createInventory(holder, 9, Component.text("ArcRanks test"))
                    .also(holder::bind)
                val otherInventory = Bukkit.createInventory(null, 9, Component.text("Other plugin test"))

                requireNotNull(ranksPlayer.openInventory(ranksInventory))
                requireNotNull(otherPlayer.openInventory(otherInventory))

                try {
                    val openHolder = ranksPlayer.openInventory.topInventory.holder as ArcRanksInventoryHolder
                    openHolder.playerId shouldBe ranksPlayer.uniqueId
                    openHolder.configGeneration shouldBe generation

                    ArcRanksMenuSessions.closeOpen(paper.server) shouldBe 1

                    ranksPlayer.openInventory.topInventory shouldNotBe ranksInventory
                    otherPlayer.openInventory.topInventory shouldBe otherInventory
                    ArcRanksMenuSessions.closeOpen(paper.server) shouldBe 0
                } finally {
                    ranksPlayer.closeInventory()
                    otherPlayer.closeInventory()
                }
            }
        }
    }
})

private class TestArcRanksInventoryHolder(
    override val playerId: UUID,
    override val configGeneration: Long,
) : ArcRanksInventoryHolder {
    private lateinit var ownedInventory: Inventory

    fun bind(inventory: Inventory) {
        check(!::ownedInventory.isInitialized) { "Test inventory is already bound" }
        ownedInventory = inventory
    }

    override fun getInventory(): Inventory {
        check(::ownedInventory.isInitialized) { "Test inventory has not been bound" }
        return ownedInventory
    }
}
