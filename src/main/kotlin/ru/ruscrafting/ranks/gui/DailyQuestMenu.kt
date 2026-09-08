package ru.ruscrafting.ranks.gui

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.config.GuiItemSpec
import ru.ruscrafting.ranks.quest.DailyQuestBoard
import ru.ruscrafting.ranks.text.RankLocale
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Read-only board: progress and automatic bonuses belong to the SQL transaction, not clicks. */
class DailyQuestMenu(
    private val settings: () -> ArcRanksSettings,
    private val locale: () -> RankLocale,
    private val load: (UUID) -> CompletableFuture<DailyQuestBoard>,
    private val tasks: LifecycleTaskScope,
    private val layouts: ArcRanksMenuLayouts,
    private val generation: () -> Long,
    private val back: (Player) -> Unit,
) : Listener {
    private val items = RankMenuItemFactory { settings().gui.background }

    fun open(player: Player) {
        val holder = Holder(player.uniqueId, generation())
        holder.menu = layouts.create(holder, MENU, locale().render("daily.title", player))
        items.fill(holder.menu)
        set(player, holder, "back", "ARROW", "daily.back")
        set(player, holder, "refresh", "CLOCK", "daily.refresh")
        set(player, holder, "status", "CLOCK", "daily.loading")
        player.openInventory(holder.menu)
        refresh(player, holder)
    }

    private fun refresh(player: Player, holder: Holder) {
        if (holder.pending) return
        holder.pending = true
        load(player.uniqueId).whenCompleteSync(tasks) { board, failure ->
            holder.pending = false
            if (!player.isOnline || player.openInventory.topInventory.holder !== holder ||
                holder.configGeneration != generation()) return@whenCompleteSync
            if (failure != null || board == null) {
                set(player, holder, "status", "BARRIER", "daily.error")
                return@whenCompleteSync
            }
            val text = locale()
            val summary = mapOf(
                "completed" to text.text(board.quests.count { it.completed }),
                "date" to text.text(board.day),
            )
            layouts.set(holder.menu, MENU, "status", items.item(
                GuiItemSpec("CLOCK", 0), text.render("daily.summary.name", player),
                text.renderLines("daily.summary.lore", player, summary),
            ))
            board.quests.forEachIndexed { index, state ->
                val values = mapOf(
                    "value" to text.text(state.value), "target" to text.text(state.quest.target),
                    "bonus" to text.text(state.quest.bonus),
                )
                val lore = text.renderLines("daily.${state.quest.id}.lore", player, values) +
                    text.renderLines(if (state.completed) "daily.completed" else "daily.active", player, values)
                holder.menu.setItem(layouts.region(MENU, "quests")[index], items.item(
                    GuiItemSpec(if (state.completed) "LIME_DYE" else state.quest.material, 0),
                    text.render("daily.${state.quest.id}.name", player), lore,
                ))
            }
        }
    }

    private fun set(player: Player, holder: Holder, element: String, material: String, key: String) {
        layouts.set(holder.menu, MENU, element, items.item(
            GuiItemSpec(material, 0), locale().render("$key.name", player), locale().renderLines("$key.lore", player),
        ))
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? Holder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.clickedInventory !== holder.menu || player.uniqueId != holder.playerId) return
        if (holder.configGeneration != generation()) { player.closeInventory(); return }
        when (event.rawSlot) {
            layouts.slot(MENU, "back") -> back(player)
            layouts.slot(MENU, "refresh") -> refresh(player, holder)
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Holder && event.rawSlots.any { it < event.view.topInventory.size }) {
            event.isCancelled = true
        }
    }

    private class Holder(override val playerId: UUID, override val configGeneration: Long) : ArcRanksInventoryHolder {
        lateinit var menu: Inventory
        var pending = false
        override fun getInventory() = menu
    }

    private companion object { val MENU = ArcRanksMenuLayouts.DAILY_QUESTS }
}
