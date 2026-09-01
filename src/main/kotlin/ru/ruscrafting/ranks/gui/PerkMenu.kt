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
import ru.ruscrafting.ranks.perk.PerkSelectionResult
import ru.ruscrafting.ranks.perk.PerkSelectionService
import ru.ruscrafting.ranks.service.RankPlayerService
import ru.ruscrafting.ranks.service.RankPlayerSnapshot
import ru.ruscrafting.ranks.text.RankLocale
import java.util.UUID

class PerkMenu(
    private val settings: () -> ArcRanksSettings,
    private val locale: () -> RankLocale,
    private val catalog: () -> PerkCatalog,
    private val players: RankPlayerService,
    private val perks: PerkSelectionService,
    private val tasks: LifecycleTaskScope,
    private val telemetry: ProductTelemetry?,
    private val back: (Player) -> Unit,
    private val configGeneration: () -> Long = { 0L },
) : Listener {
    private val items = RankMenuItemFactory { settings().gui.background }

    fun open(player: Player) {
        if (!settings().features.perks) {
            player.sendMessage(
                locale().render(
                    "commands.feature-disabled",
                    player,
                    mapOf("feature" to locale().render("features.perks", player)),
                ),
            )
            return
        }
        openView(player, PerkMenuView.SLOTS, targetSlot = null, snapshot = null)
        telemetry?.record(ProductEvent.PERK_BOARD_OPEN, ProductDimension.NONE)
    }

    private fun openView(
        player: Player,
        view: PerkMenuView,
        targetSlot: Int?,
        snapshot: RankPlayerSnapshot?,
    ) {
        val holder = PerkMenuHolder(player.uniqueId, configGeneration(), view, targetSlot)
        val inventory = Bukkit.createInventory(
            holder,
            view.inventorySize(),
            locale().render(view.titleKey(), player, targetSlot?.let { mapOf("slot" to locale().text(it)) }.orEmpty()),
        )
        holder.menuInventory = inventory
        if (snapshot == null) renderLoading(player, holder) else {
            holder.snapshot = snapshot
            render(player, holder, snapshot)
        }
        player.openInventory(inventory)
        if (snapshot == null) refresh(player, holder)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? PerkMenuHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory !== event.view.topInventory || holder.actionPending) return
        val player = event.whoClicked as? Player ?: return
        if (holder.playerId != player.uniqueId) return
        when (event.rawSlot) {
            holder.view.backSlot() -> when (holder.view) {
                PerkMenuView.SLOTS -> back(player)
                PerkMenuView.SELECT -> openView(player, PerkMenuView.SLOTS, targetSlot = null, snapshot = holder.snapshot)
            }
            holder.view.refreshSlot() -> refresh(player, holder)
            else -> when (holder.view) {
                PerkMenuView.SLOTS -> {
                    val slot = SLOT_CARDS.indexOf(event.rawSlot) + 1
                    if (slot in 1..SLOT_CARDS.size) {
                        val snapshot = holder.snapshot ?: return
                        openView(player, PerkMenuView.SELECT, targetSlot = slot, snapshot = snapshot)
                    }
                }
                PerkMenuView.SELECT -> holder.perkIds[event.rawSlot]?.let { choose(player, holder, it) }
            }
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is PerkMenuHolder && event.rawSlots.any { it < event.view.topInventory.size }) {
            event.isCancelled = true
        }
    }

    private fun refresh(player: Player, holder: PerkMenuHolder) {
        holder.actionPending = true
        holder.generation++
        val generation = holder.generation
        renderLoading(player, holder)
        players.load(player.uniqueId).whenCompleteSync(tasks) { snapshot, failure ->
            if (!holder.current(player, generation)) return@whenCompleteSync
            holder.actionPending = false
            if (failure != null || snapshot == null) renderError(player, holder)
            else {
                holder.snapshot = snapshot
                render(player, holder, snapshot)
            }
        }
    }

    private fun choose(player: Player, holder: PerkMenuHolder, perkId: PerkId) {
        val snapshot = holder.snapshot ?: return
        val targetSlot = holder.targetSlot ?: return
        holder.actionPending = true
        holder.generation++
        val generation = holder.generation
        val actionLocale = locale()
        renderRunning(player, holder.menuInventory)
        val operation = if (snapshot.perkSlots[targetSlot] == perkId) {
            perks.remove(player.uniqueId, perkId)
        } else {
            perks.selectIntoSlot(player.uniqueId, targetSlot, perkId, snapshot.mastery)
        }
        operation.whenCompleteSync(tasks) { result, failure ->
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
                actionLocale.render(
                    key,
                    player,
                    definition?.let { mapOf("perk" to actionLocale.render(it.nameKey, player)) }.orEmpty(),
                ),
            )
            if (holder.current(player, generation)) {
                holder.actionPending = false
                if (failure == null && result != null) {
                    openView(player, PerkMenuView.SLOTS, targetSlot = null, snapshot = null)
                } else {
                    refresh(player, holder)
                }
            }
        }
    }

    private fun render(player: Player, holder: PerkMenuHolder, snapshot: RankPlayerSnapshot) {
        items.fill(holder.menuInventory)
        holder.perkIds.clear()
        when (holder.view) {
            PerkMenuView.SLOTS -> renderSlots(player, holder.menuInventory, snapshot)
            PerkMenuView.SELECT -> renderSelection(player, holder, snapshot)
        }
        renderControls(player, holder)
    }

    private fun renderSlots(player: Player, inventory: Inventory, snapshot: RankPlayerSnapshot) {
        val currentCatalog = catalog()
        inventory.setItem(
            STATUS_SLOT,
            items.item(
                settings().gui.perks,
                locale().render("gui.perks.overview.name", player),
                locale().renderLines("gui.perks.overview.lore", player),
            ),
        )
        SLOT_CARDS.forEachIndexed { index, inventorySlot ->
            val selectionSlot = index + 1
            val perk = snapshot.perkSlots[selectionSlot]?.let(currentCatalog::require)
            val values = mapOf("slot" to locale().text(selectionSlot)) + perk?.values(player).orEmpty()
            val state = if (perk == null) "empty" else "active"
            inventory.setItem(
                inventorySlot,
                items.item(
                    if (perk == null) {
                        settings().gui.item("perk-empty-slot", GuiItemSpec("BOOK", 0))
                    } else {
                        settings().gui.perks
                    },
                    locale().render("gui.perks.slot.$state.name", player, values),
                    locale().renderLines("gui.perks.slot.$state.lore", player, values),
                ),
            )
        }
    }

    private fun renderSelection(player: Player, holder: PerkMenuHolder, snapshot: RankPlayerSnapshot) {
        val currentCatalog = catalog()
        val targetSlot = checkNotNull(holder.targetSlot)
        val inventory = holder.menuInventory
        inventory.setItem(
            STATUS_SLOT,
            items.item(
                settings().gui.perks,
                locale().render("gui.perks.selection.name", player, mapOf("slot" to locale().text(targetSlot))),
                locale().renderLines("gui.perks.selection.lore", player, mapOf("slot" to locale().text(targetSlot))),
            ),
        )
        PATH_GROUPS.forEach { (path, group) ->
            inventory.setItem(
                group.header,
                items.item(
                    settings().gui.item("path-${path.name.lowercase()}", GuiItemSpec(path.material(), 0)),
                    locale().render("gui.perks.path.name", player, mapOf("path" to locale().render(path.nameKey(), player))),
                    locale().renderLines(
                        "gui.perks.path.lore",
                        player,
                        mapOf(
                            "description" to locale().render(path.detailsKey(), player),
                            "mastery" to locale().render(snapshot.mastery.getValue(path).localeKey(), player),
                        ),
                    ),
                ),
            )
            currentCatalog.forPath(path).forEachIndexed { index, perk ->
                val state = when {
                    snapshot.perkSlots[targetSlot] == perk.id -> "selected"
                    perk.id in snapshot.activePerks -> "other"
                    snapshot.mastery.getValue(perk.path).ordinal < perk.requiredMastery.ordinal -> "locked"
                    else -> "available"
                }
                val slot = group.perks[index]
                if (state == "selected" || state == "available") holder.perkIds[slot] = perk.id
                inventory.setItem(
                    slot,
                    items.item(
                        perkItem(state),
                        locale().render("gui.perks.card.$state.name", player, perk.values(player)),
                        locale().renderLines("gui.perks.card.$state.lore", player, perk.values(player)),
                    ),
                )
            }
        }
    }

    private fun PerkDefinition.values(player: Player) = mapOf(
        "perk" to locale().render(nameKey, player),
        "description" to locale().render(descriptionKey, player),
        "path" to locale().render(path.nameKey(), player),
        "mastery" to locale().render(requiredMastery.localeKey(), player),
        "percent" to locale().text(basisPoints / 100.0),
    )

    private fun renderLoading(player: Player, holder: PerkMenuHolder) {
        items.fill(holder.menuInventory)
        holder.menuInventory.setItem(
            STATUS_SLOT,
            items.item(
                settings().gui.item("loading", settings().gui.perks),
                locale().render("gui.perks.loading.name", player),
                locale().renderLines("gui.perks.loading.lore", player),
            ),
        )
        renderControls(player, holder)
    }

    private fun renderRunning(player: Player, inventory: Inventory) {
        inventory.setItem(
            STATUS_SLOT,
            items.item(
                settings().gui.item("running", GuiItemSpec("CLOCK", 0)),
                locale().render("gui.perks.running.name", player),
                locale().renderLines("gui.perks.running.lore", player),
            ),
        )
    }

    private fun renderError(player: Player, holder: PerkMenuHolder) {
        items.fill(holder.menuInventory)
        holder.menuInventory.setItem(
            STATUS_SLOT,
            items.item(
                settings().gui.item("error", GuiItemSpec("RED_STAINED_GLASS_PANE", 0)),
                locale().render("gui.perks.error.name", player),
                locale().renderLines("gui.perks.error.lore", player),
            ),
        )
        renderControls(player, holder)
    }

    private fun renderControls(player: Player, holder: PerkMenuHolder) {
        holder.menuInventory.setItem(
            holder.view.backSlot(),
            items.item(settings().gui.back, locale().render("gui.common.back.name", player), locale().renderLines("gui.common.back.lore", player)),
        )
        holder.menuInventory.setItem(
            holder.view.refreshSlot(),
            items.item(
                settings().gui.item("refresh", GuiItemSpec("CLOCK", 0)),
                locale().render("gui.common.refresh.name", player),
                locale().renderLines("gui.common.refresh.lore", player),
            ),
        )
    }

    private fun perkItem(state: String): GuiItemSpec = when (state) {
        "selected" -> settings().gui.item("perk-selected", GuiItemSpec("ENCHANTED_BOOK", 0))
        "other" -> settings().gui.item("perk-other", GuiItemSpec("LIME_DYE", 0))
        "locked" -> settings().gui.item("perk-locked", GuiItemSpec("GRAY_DYE", 0))
        else -> settings().gui.item("perk-available", GuiItemSpec("BOOK", 0))
    }

    companion object {
        const val STATUS_SLOT = 4
        val SLOT_CARDS = listOf(21, 23)
        val PATH_GROUPS = linkedMapOf(
            SpecializationPath.FARMING to PerkPathGroup(9, listOf(10, 11, 12)),
            SpecializationPath.INDUSTRY to PerkPathGroup(14, listOf(15, 16, 17)),
            SpecializationPath.TRADE to PerkPathGroup(18, listOf(19, 20, 21)),
            SpecializationPath.EXPLORATION to PerkPathGroup(23, listOf(24, 25, 26)),
            SpecializationPath.BUILDING to PerkPathGroup(27, listOf(28, 29, 30)),
            SpecializationPath.COMMUNITY to PerkPathGroup(32, listOf(33, 34, 35)),
        )
    }
}

data class PerkPathGroup(val header: Int, val perks: List<Int>)

private enum class PerkMenuView {
    SLOTS,
    SELECT,
}

private class PerkMenuHolder(
    override val playerId: UUID,
    override val configGeneration: Long,
    val view: PerkMenuView,
    val targetSlot: Int?,
) : ArcRanksInventoryHolder {
    lateinit var menuInventory: Inventory
    var generation = 0L
    var actionPending = false
    var snapshot: RankPlayerSnapshot? = null
    val perkIds = mutableMapOf<Int, PerkId>()

    fun current(player: Player, expectedGeneration: Long): Boolean =
        player.isOnline && generation == expectedGeneration && player.openInventory.topInventory === menuInventory

    override fun getInventory(): Inventory = menuInventory
}

private fun PerkMenuView.inventorySize(): Int = if (this == PerkMenuView.SLOTS) 45 else 54

private fun PerkMenuView.titleKey(): String = if (this == PerkMenuView.SLOTS) "gui.perks.title" else "gui.perks.selection.title"

private fun PerkMenuView.backSlot(): Int = if (this == PerkMenuView.SLOTS) 36 else 45

private fun PerkMenuView.refreshSlot(): Int = if (this == PerkMenuView.SLOTS) 44 else 53

private fun SpecializationPath.material(): String = when (this) {
    SpecializationPath.FARMING -> "WHEAT"
    SpecializationPath.INDUSTRY -> "FURNACE"
    SpecializationPath.TRADE -> "EMERALD"
    SpecializationPath.EXPLORATION -> "COMPASS"
    SpecializationPath.BUILDING -> "BRICKS"
    SpecializationPath.COMMUNITY -> "CAMPFIRE"
}

private fun SpecializationPath.nameKey(): String = "paths.${name.lowercase()}.name"

private fun SpecializationPath.detailsKey(): String = "paths.${name.lowercase()}.details"

private fun MasteryLevel.localeKey(): String = "mastery.${name.lowercase()}"
