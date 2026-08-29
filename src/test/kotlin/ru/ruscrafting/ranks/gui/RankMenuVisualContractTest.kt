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
                inventory.id.startsWith("rank-paths-")
        }.forEach { inventory ->
            inventory.items.count { it["name"] == "ranks:gui.common.back.name" } shouldBe 1
            if (!inventory.id.startsWith("rank-paths-")) {
                inventory.items.single { it["name"] == "ranks:gui.common.back.name" }["slot"] shouldBe 45
                inventory.items.single { it["name"] == "ranks:gui.common.refresh.name" }["slot"] shouldBe 53
            }
        }
    }

    "rank overview exposes only three branches and paths move to their own page" {
        val inventories = previewInventories()
        val overview = inventories.single { it.id == "rank-overview-current" }
        val paths = inventories.single { it.id == "rank-paths-current" }

        overview.items.size shouldBe 14
        overview.items.filter { (it["slot"] as Int) in 21..23 }.map { it["slot"] } shouldBe listOf(21, 22, 23)
        overview.items.single { it["slot"] == 22 }["material"] shouldBe "COMPASS"
        overview.items.filter { (it["slot"] as Int) in 27..35 }.map { it["slot"] } shouldBe listOf(31)
        paths.items.count { it["material"] == "COMPASS" } shouldBe 1
        paths.items.filter { (it["slot"] as Int) in 10..16 }.map { it["slot"] } shouldBe (10..16).toList()
    }
})

private data class PreviewInventory(
    val id: String,
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
            items = inventory.getValue("items") as List<Map<String, Any?>>,
        )
    }
}
