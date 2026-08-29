package ru.ruscrafting.ranks.gui

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.ruscrafting.ranks.analytics.ProductDimension
import ru.ruscrafting.ranks.analytics.ProductEvent
import ru.ruscrafting.ranks.analytics.ProductTelemetry
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.config.GuiItemSpec
import ru.ruscrafting.ranks.domain.MasteryLevel
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.perk.PerkCatalog
import ru.ruscrafting.ranks.perk.PerkDefinition
import ru.ruscrafting.ranks.perk.PerkId
import ru.ruscrafting.ranks.perk.PerkSelection
import ru.ruscrafting.ranks.perk.PerkSelectionResult
import ru.ruscrafting.ranks.perk.PerkSelectionService
import ru.ruscrafting.ranks.service.RankPlayerService
import ru.ruscrafting.ranks.service.RankPlayerSnapshot
import ru.ruscrafting.ranks.text.RankLocale
import java.util.UUID

class PerkMenu(
    private val settings: () -> ArcRanksSettings,
    private val locale: () -> RankLocale,
    private val catalog: PerkCatalog,
    private val players: RankPlayerService,
    private val perks: PerkSelectionService,
    private val tasks: LifecycleTaskScope,
    private val telemetry: ProductTelemetry?,
    private val back: (Player) -> Unit,
) : Listener {
    private val items = RankMenuItemFactory { settings().gui.background }

    fun open(player: Player) {
        val holder = PerkMenuHolder(player.uniqueId)
        val inventory = Bukkit.createInventory(holder, INVENTORY_SIZE, locale().render("gui.perks.title", player))
        holder.menuInventory = inventory
        renderLoading(player, inventory)
        player.openInventory(inventory)
        telemetry?.record(ProductEvent.PERK_BOARD_OPEN, ProductDimension.NONE)
        refresh(player, holder)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? PerkMenuHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory !== event.view.topInventory || holder.actionPending) return
        val player = event.whoClicked as? Player ?: return
        if (holder.playerId != player.uniqueId) return
        when (event.rawSlot) {
            BACK_SLOT -> back(player)
            REFRESH_SLOT -> refresh(player, holder)
            in PERK_SLOTS -> holder.perkIds[event.rawSlot]?.let { toggle(player, holder, it) }
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is PerkMenuHolder && event.rawSlots.any { it < INVENTORY_SIZE }) {
            event.isCancelled = true
        }
    }

    private fun refresh(player: Player, holder: PerkMenuHolder) {
        holder.actionPending = true
        holder.generation++
        val generation = holder.generation
        renderLoading(player, holder.menuInventory)
        players.load(player.uniqueId).whenCompleteSync(tasks) { snapshot, failure ->
            if (!holder.current(player, generation)) return@whenCompleteSync
            holder.actionPending = false
            if (failure != null || snapshot == null) renderError(player, holder.menuInventory)
            else {
                holder.snapshot = snapshot
                render(player, holder, snapshot)
            }
        }
    }

    private fun toggle(player: Player, holder: PerkMenuHolder, perkId: PerkId) {
        val snapshot = holder.snapshot ?: return
        holder.actionPending = true
        holder.generation++
        val generation = holder.generation
        renderRunning(player, holder.menuInventory)
        val operation = if (perkId in snapshot.activePerks) {
            perks.remove(player.uniqueId, perkId)
        } else {
            perks.select(player.uniqueId, perkId, snapshot.mastery)
        }
        operation.whenCompleteSync(tasks) { result, failure ->
            if (!holder.current(player, generation)) return@whenCompleteSync
            holder.actionPending = false
            val key = when {
                failure != null || result == null -> "commands.perks.storage-unavailable"
                result is PerkSelectionResult.Selected -> "commands.perks.selected"
                result is PerkSelectionResult.AlreadySelected -> "commands.perks.already-selected"
                result is PerkSelectionResult.Removed -> "commands.perks.removed"
                result is PerkSelectionResult.NotSelected -> "commands.perks.not-selected"
                result is PerkSelectionResult.Locked -> "commands.perks.locked"
                result is PerkSelectionResult.Full -> "commands.perks.full"
                else -> "commands.perks.storage-unavailable"
            }
            val definition = when (result) {
                is PerkSelectionResult.Selected -> result.perk
                is PerkSelectionResult.AlreadySelected -> result.perk
                is PerkSelectionResult.Removed -> result.perk
                is PerkSelectionResult.NotSelected -> result.perk
                is PerkSelectionResult.Locked -> result.perk
                is PerkSelectionResult.Full -> result.perk
                else -> null
            }
            player.sendMessage(
                locale().render(
                    key,
                    player,
                    definition?.let { mapOf("perk" to locale().render(it.nameKey, player)) }.orEmpty(),
                ),
            )
            refresh(player, holder)
        }
    }

    private fun render(player: Player, holder: PerkMenuHolder, snapshot: RankPlayerSnapshot) {
        val inventory = holder.menuInventory
        items.fill(inventory)
        holder.perkIds.clear()
        val active = snapshot.activePerks.toList().sortedBy { id -> catalog.perks.indexOf(catalog.require(id)) }
        ACTIVE_SLOTS.forEachIndexed { index, slot ->
            val perk = active.getOrNull(index)?.let(catalog::require)
            if (perk == null) {
                inventory.setItem(slot, items.item(GuiItemSpec("BOOK", 0), locale().render("gui.perks.slot.empty.name", player), locale().renderLines("gui.perks.slot.empty.lore", player)))
            } else {
                val values = perk.values(player)
                inventory.setItem(slot, items.item(settings().gui.perks, locale().render("gui.perks.slot.active.name", player, values), locale().renderLines("gui.perks.slot.active.lore", player, values)))
            }
        }
        catalog.perks.forEachIndexed { index, perk ->
            val state = when {
                perk.id in snapshot.activePerks -> "selected"
                snapshot.mastery.getValue(perk.path).ordinal < perk.requiredMastery.ordinal -> "locked"
                snapshot.activePerks.size >= PerkSelection.MAX_SLOTS -> "full"
                else -> "available"
            }
            val slot = PERK_SLOTS[index]
            holder.perkIds[slot] = perk.id
            inventory.setItem(
                slot,
                items.item(
                    GuiItemSpec(perk.path.material(), 0),
                    locale().render("gui.perks.card.$state.name", player, perk.values(player)),
                    locale().renderLines("gui.perks.card.$state.lore", player, perk.values(player)),
                ),
            )
        }
        renderControls(player, inventory)
    }

    private fun PerkDefinition.values(player: Player) = mapOf(
        "perk" to locale().render(nameKey, player),
        "description" to locale().render(descriptionKey, player),
        "path" to locale().render("paths.${path.name.lowercase()}.name", player),
        "mastery" to locale().render("mastery.${requiredMastery.name.lowercase()}", player),
        "percent" to locale().text(basisPoints / 100.0),
    )

    private fun renderLoading(player: Player, inventory: Inventory) {
        items.fill(inventory)
        inventory.setItem(STATUS_SLOT, items.item(settings().gui.perks, locale().render("gui.perks.loading.name", player), locale().renderLines("gui.perks.loading.lore", player)))
        renderControls(player, inventory)
    }

    private fun renderRunning(player: Player, inventory: Inventory) {
        inventory.setItem(STATUS_SLOT, items.item(GuiItemSpec("CLOCK", 0), locale().render("gui.perks.running.name", player), locale().renderLines("gui.perks.running.lore", player)))
    }

    private fun renderError(player: Player, inventory: Inventory) {
        items.fill(inventory)
        inventory.setItem(STATUS_SLOT, items.item(GuiItemSpec("BARRIER", 0), locale().render("gui.perks.error.name", player), locale().renderLines("gui.perks.error.lore", player)))
        renderControls(player, inventory)
    }

    private fun renderControls(player: Player, inventory: Inventory) {
        inventory.setItem(BACK_SLOT, items.item(GuiItemSpec("ARROW", 0), locale().render("gui.common.back.name", player), locale().renderLines("gui.common.back.lore", player)))
        inventory.setItem(REFRESH_SLOT, items.item(GuiItemSpec("CLOCK", 0), locale().render("gui.common.refresh.name", player), locale().renderLines("gui.common.refresh.lore", player)))
    }

    companion object {
        const val INVENTORY_SIZE = 54
        const val STATUS_SLOT = 4
        val ACTIVE_SLOTS = listOf(10, 16)
        val PERK_SLOTS = listOf(19, 21, 23, 25, 28, 30, 32, 34, 37, 39, 41, 43)
        const val BACK_SLOT = 45
        const val REFRESH_SLOT = 53
    }
}

private class PerkMenuHolder(val playerId: UUID) : InventoryHolder {
    lateinit var menuInventory: Inventory
    var generation = 0L
    var actionPending = false
    var snapshot: RankPlayerSnapshot? = null
    val perkIds = mutableMapOf<Int, PerkId>()

    fun current(player: Player, expectedGeneration: Long): Boolean =
        player.isOnline && generation == expectedGeneration && player.openInventory.topInventory === menuInventory

    override fun getInventory(): Inventory = menuInventory
}

private fun SpecializationPath.material(): String = when (this) {
    SpecializationPath.FARMING -> "WHEAT"
    SpecializationPath.INDUSTRY -> "FURNACE"
    SpecializationPath.TRADE -> "EMERALD"
    SpecializationPath.EXPLORATION -> "COMPASS"
    SpecializationPath.BUILDING -> "BRICKS"
    SpecializationPath.COMMUNITY -> "CAMPFIRE"
}
