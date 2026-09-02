package ru.ruscrafting.ranks.gui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

class RankMenuVisualContractTest : StringSpec({
    "GUI previews use back navigation without explicit close buttons" {
        val inventories = previewInventories()
        val items = inventories.flatMap { it.items }

        items.count { it["name"] == "ranks:gui.common.close.name" } shouldBe 0

        inventories.filter { inventory ->
            inventory.id.startsWith("contracts-") ||
                inventory.id.startsWith("perks-") ||
                inventory.id.startsWith("analytics-") ||
                inventory.id.startsWith("weekly-kit-") ||
                inventory.id.startsWith("rank-paths-")
        }.forEach { inventory ->
            val back = inventory.items.single { it["name"] == "ranks:gui.common.back.name" }
            back["material"] shouldBe "BLUE_STAINED_GLASS_PANE"
            back["itemsadder"] shouldBe "arc:left_gray"
            back["slot"] shouldBe if (inventory.rows == 5) 36 else 45
            if (inventory.items.any { it["name"] == "ranks:gui.common.refresh.name" }) {
                inventory.items.single { it["name"] == "ranks:gui.common.refresh.name" }["slot"] shouldBe
                    if (inventory.rows == 5) 44 else 53
            }
        }
    }

    "rank overview exposes only three branches and paths move to their own page" {
        val inventories = previewInventories()
        val overview = inventories.single { it.id == "rank-overview-current" }
        val paths = inventories.single { it.id == "rank-paths-current" }

        overview.rows shouldBe 5
        overview.items.size shouldBe 15
        overview.items.filter { (it["slot"] as Int) in 27..35 }.map { it["slot"] } shouldBe listOf(30, 31, 32)
        overview.items.single { it["slot"] == 31 }["material"] shouldBe "COMPASS"
        overview.items.filter { (it["slot"] as Int) in 36..44 }.map { it["slot"] } shouldBe listOf(39, 40)
        overview.items.filter { (it["slot"] as Int) in 13..17 }
            .all { it["name"] == "ranks:gui.rank.locked.name" && it["itemsadder"] == null } shouldBe true
        paths.rows shouldBe 5
        paths.items.count { it["material"] == "COMPASS" } shouldBe 1
        paths.items.filter { (it["slot"] as Int) in 19..25 }.map { it["slot"] } shouldBe (19..25).toList()
        paths.items.none { (it["slot"] as Int) in 9..17 } shouldBe true
        paths.items.none { (it["slot"] as Int) in 27..35 } shouldBe true
        paths.items.single { it["name"] == "ranks:gui.common.back.name" }["slot"] shouldBe 36
    }

    "weekly kit preview is five-row symmetric and has one back action" {
        val inventories = previewInventories().filter { it.id.startsWith("weekly-kit-") }

        inventories.map { it.id }.toSet() shouldBe setOf(
            "weekly-kit-available", "weekly-kit-claimed", "weekly-kit-delivering", "weekly-kit-claiming",
            "weekly-kit-loading", "weekly-kit-error",
        )
        inventories.forEach { inventory ->
            inventory.rows shouldBe 5
            inventory.items.single { it["name"] == "ranks:gui.common.back.name" }["slot"] shouldBe 36
            inventory.items.none { it["material"] == "BARRIER" } shouldBe true
        }
        inventories.first { it.id == "weekly-kit-available" }
            .items.filter { (it["slot"] as Int) in 18..26 }.map { it["slot"] } shouldBe listOf(20, 22, 24)
    }

    "rank error previews cover every failure without barrier items" {
        val inventories = previewInventories().filter { it.id.startsWith("rank-") }

        inventories.map { it.id }.toSet().containsAll(
            setOf(
                "rank-overview-rank-missing",
                "rank-overview-rank-conflict",
                "rank-overview-rank-unknown",
            ),
        ) shouldBe true
        inventories.flatMap { it.items }.none { it["material"] == "BARRIER" } shouldBe true
    }

    "contract previews keep admin controls out of ordinary menus" {
        val inventories = previewInventories().filter { it.id.startsWith("contracts-") }
        val ordinary = inventories.filterNot { it.id.endsWith("-admin") }
        val admin = inventories.filter { it.id.endsWith("-admin") }

        ordinary.flatMap { it.items }.none { it["name"] == "ranks:gui.contracts.admin.complete.name" } shouldBe true
        admin.map { it.id }.toSet() shouldBe setOf("contracts-active-admin", "contracts-ready-admin")
        admin.forEach { inventory ->
            inventory.items.single { (it["name"] as String).startsWith("ranks:gui.contracts.admin.") }["slot"] shouldBe 48
        }
    }

    "perk previews separate two slot cards from three choices per path" {
        val inventories = previewInventories()
        val slots = inventories.single { it.id == "perks-slots-mixed" }
        val selection = inventories.single { it.id == "perks-select-mixed" }

        slots.rows shouldBe 5
        slots.items.filter { it["name"] in setOf("ranks:gui.perks.slot.empty.name", "ranks:gui.perks.slot.active.name") }
            .map { it["slot"] } shouldBe listOf(21, 23)
        selection.rows shouldBe 6
        selection.items.count { it["name"] == "ranks:gui.perks.path.name" } shouldBe 6
        selection.items.count { (it["name"] as? String)?.startsWith("ranks:gui.perks.card.") == true } shouldBe 18
    }
})

private data class PreviewInventory(
    val id: String,
    val rows: Int,
    val items: List<Map<String, Any?>>,
)

@Suppress("UNCHECKED_CAST")
private fun previewInventories(): List<PreviewInventory> {
    val projectDir = Path.of(checkNotNull(System.getProperty("arcranks.projectDir")))
    val root = Files.newInputStream(projectDir.resolve("visual-preview.yml")).use { input ->
        Yaml(LoaderOptions().apply { maxAliasesForCollections = 1_000 }).load<Map<String, Any?>>(input)
    }
    val surfaces = root.getValue("surfaces") as Map<String, Any?>
    return (surfaces.getValue("inventories") as List<Map<String, Any?>>).map { inventory ->
        PreviewInventory(
            id = inventory.getValue("id").toString(),
            rows = inventory.getValue("rows") as Int,
            items = inventory.getValue("items") as List<Map<String, Any?>>,
        )
    }
}
