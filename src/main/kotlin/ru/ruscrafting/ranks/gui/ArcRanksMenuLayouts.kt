package ru.ruscrafting.ranks.gui

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import ru.arc.config.Config
import ru.arc.menu.MenuCatalog
import ru.arc.menu.MenuCatalogRepository
import ru.arc.menu.MenuContract
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.arc.menu.MenuLayoutParser
import ru.arc.menu.MenuRegionId
import ru.ruscrafting.ranks.domain.SpecializationPath
import java.nio.file.Path

/** Validated semantic topology for the async ArcRanks inventory controllers. */
class ArcRanksMenuLayouts(dataRoot: Path) {
    private val repository = MenuCatalogRepository(loadConfiguration(dataRoot))

    fun prepare(dataRoot: Path): MenuCatalog = loadConfiguration(dataRoot)

    fun replace(candidate: MenuCatalog) {
        repository.replace(candidate)
    }

    fun create(holder: InventoryHolder, menu: MenuId, title: Component): Inventory =
        Bukkit.createInventory(holder, repository.current().require(menu).rows * 9, title)

    fun slot(menu: MenuId, element: String): Int =
        repository.current().require(menu).slot(MenuElementId.of(element)).index

    fun region(menu: MenuId, region: String): List<Int> =
        repository.current().require(menu).region(MenuRegionId.of(region)).map { it.index }

    fun set(inventory: Inventory, menu: MenuId, element: String, item: ItemStack?) {
        inventory.setItem(slot(menu, element), item)
    }

    companion object {
        val PASSPORT = MenuId.of("passport")
        val PATHS = MenuId.of("paths")
        val CONTRACTS = MenuId.of("contracts")
        val PERK_SLOTS = MenuId.of("perk-slots")
        val PERK_SELECTION = MenuId.of("perk-selection")
        val WEEKLY_KIT = MenuId.of("weekly-kit")
        val ANALYTICS = MenuId.of("analytics")

        private fun elements(vararg values: String) = values.mapTo(linkedSetOf(), MenuElementId::of)
        private fun regions(vararg values: String) = values.mapTo(linkedSetOf(), MenuRegionId::of)

        val CONTRACTS_BY_MENU = linkedMapOf(
            PASSPORT to MenuContract(
                requiredElements = elements("profile", "contracts", "paths", "perks", "weekly-kit", "promotion"),
                requiredRegions = regions("ranks"),
            ),
            PATHS to MenuContract(requiredElements = elements("profile", "guide", "back"), requiredRegions = regions("paths")),
            CONTRACTS to MenuContract(
                requiredElements = elements("status", "reroll", "admin-complete", "back", "refresh"),
                requiredRegions = regions("stamps", "cards"),
            ),
            PERK_SLOTS to MenuContract(requiredElements = elements("status", "back", "refresh"), requiredRegions = regions("slots")),
            PERK_SELECTION to MenuContract(
                requiredElements = elements("status", "farming", "industry", "trade", "exploration", "building", "community", "back", "refresh"),
                requiredRegions = regions("offers"),
            ),
            WEEKLY_KIT to MenuContract(requiredElements = elements("summary", "contents", "claim", "back")),
            ANALYTICS to MenuContract(
                requiredElements = elements("status", "overview", "contracts", "perks", "promotions", "recommendations", "health", "back", "refresh"),
                requiredRegions = regions("windows"),
            ),
        )

        fun loadConfiguration(dataRoot: Path): MenuCatalog =
            MenuLayoutParser.require(Config(dataRoot, "config.yml"), "gui.layouts", CONTRACTS_BY_MENU).also { catalog ->
                mapOf(
                    PASSPORT to mapOf("ranks" to 9),
                    PATHS to mapOf("paths" to SpecializationPath.entries.size),
                    CONTRACTS to mapOf("stamps" to 3, "cards" to 3),
                    PERK_SLOTS to mapOf("slots" to 2),
                    PERK_SELECTION to mapOf("offers" to SpecializationPath.entries.size * PerkMenu.PERKS_PER_PATH),
                    ANALYTICS to mapOf("windows" to 3),
                ).forEach { (menu, regions) ->
                    regions.forEach { (region, required) ->
                        val actual = catalog.require(menu).region(MenuRegionId.of(region)).size
                        require(actual >= required) {
                            "ArcRanks menu '$menu' region '$region' needs at least $required slots, got $actual"
                        }
                    }
                }
            }
    }
}
