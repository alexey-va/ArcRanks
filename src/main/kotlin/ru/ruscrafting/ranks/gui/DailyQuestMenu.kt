package ru.ruscrafting.ranks.gui

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.ruscrafting.ranks.quest.DailyRewardState
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
import java.time.LocalDate
import ru.ruscrafting.ranks.quest.QuestReplaceResult
import java.util.concurrent.CompletableFuture

/** Compact board: progress and automatic bonuses belong to the SQL transaction, not clicks. */
class DailyQuestMenu(
    private val settings: () -> ArcRanksSettings,
    private val locale: () -> RankLocale,
    private val load: (UUID) -> CompletableFuture<DailyQuestBoard>,
    private val tasks: LifecycleTaskScope,
    private val layouts: ArcRanksMenuLayouts,
    private val generation: () -> Long,
    private val back: (Player) -> Unit,
    private val replace: (UUID, LocalDate, String) -> CompletableFuture<QuestReplaceResult> = { _, _, _ -> CompletableFuture.completedFuture(QuestReplaceResult.UNAVAILABLE) },
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
            val geometry = DailyQuestLayout(maxOf(1, board.quests.size))
            if (holder.menu.size != geometry.size) {
                holder.menu = Bukkit.createInventory(holder, geometry.size, locale().render("daily.title", player))
                player.openInventory(holder.menu)
            }
            holder.geometry = geometry
            holder.board = board
            items.fill(holder.menu)
            set(player, holder, "back", "ARROW", "daily.back")
            set(player, holder, "refresh", "CLOCK", "daily.refresh")
            val text = locale()
            val summary = mapOf(
                "completed" to text.text(board.quests.count { it.completed }),
                "total" to text.text(board.quests.size),
                "date" to text.text(board.day),
                "replacements" to text.text(board.replacementsLeft),
            )
            layouts.set(holder.menu, MENU, "status", items.item(
                GuiItemSpec("CLOCK", 0), text.render("daily.summary.name", player),
                text.renderLines("daily.summary.lore", player, summary),
            ))
            board.quests.forEachIndexed { index, state ->
                val values = mapOf(
                    "value" to text.text(state.value), "target" to text.text(state.quest.target),
                    "bonus" to text.text(state.quest.bonus),
                    "quest-name" to text.render("daily.${state.quest.textId}.name", player),
                    "money" to text.text(state.quest.money), "tokens" to text.text(state.quest.tokens),
                    "extra-money" to text.text((state.quest.money * state.quest.challengePercent + 99) / 100),
                    "challenge-value" to text.text(state.challengeValue),
                    "replacements" to text.text(board.replacementsLeft),
                )
                val steps = state.quest.plan?.let { plan ->
                    listOf(text.render("daily.mode.${plan.mode.name.lowercase()}", player)) + plan.steps.mapIndexed { step, definition ->
                        text.render(if (state.stepValues[step] >= definition.target) "daily.step-done" else "daily.step", player,
                            mapOf("step-name" to text.render("daily.${definition.textId}.name", player),
                                "step-value" to text.text(state.stepValues[step]), "step-target" to text.text(definition.target)))
                    }
                }.orEmpty()
                val challenge = if (state.quest.challengePercent > 0) text.renderLines("daily.challenge", player, values) else emptyList()
                val lore = text.renderLines("daily.${state.quest.textId}.lore", player, values) + steps + challenge +
                    text.renderLines(if (state.quest.tokens > 0) "daily.rare-reward" else "daily.money-reward", player, values) +
                    text.renderLines(when {
                        !state.completed -> "daily.active"
                        state.rewardState == DailyRewardState.GRANTED -> "daily.completed"
                        state.rewardState == DailyRewardState.RECOVERY -> "daily.recovery"
                        else -> "daily.pending"
                    }, player, values) +
                    if (!state.completed) text.renderLines("daily.replace-hint", player, values) else emptyList()
                holder.menu.setItem(geometry.slots[index], items.item(
                    GuiItemSpec(if (state.completed) "LIME_DYE" else state.quest.material, 0),
                    text.render(if (state.quest.tokens > 0) "daily.rare-name" else "daily.${state.quest.textId}.name", player, values), lore,
                ))
            }
        }
    }

    private fun set(player: Player, holder: Holder, element: String, material: String, key: String) {
        holder.menu.setItem(controlSlot(holder, element), items.item(
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
            controlSlot(holder, "back") -> back(player)
            controlSlot(holder, "refresh") -> refresh(player, holder)
            else -> if (event.isRightClick && !holder.pending) {
                val board = holder.board ?: return
                val index = holder.geometry.slots.indexOf(event.rawSlot)
                val goal = board.quests.getOrNull(index) ?: return
                holder.pending = true
                replace(player.uniqueId, board.day, goal.quest.id).whenCompleteSync(tasks) { result, failure ->
                    holder.pending = false
                    if (!player.isOnline || player.openInventory.topInventory.holder !== holder || holder.configGeneration != generation()) return@whenCompleteSync
                    player.sendMessage(locale().render("daily.replace-result.${if (failure == null && result != null) result.name.lowercase() else "error"}", player))
                    refresh(player, holder)
                }
            }
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Holder && event.rawSlots.any { it < event.view.topInventory.size }) {
            event.isCancelled = true
        }
    }

    private fun controlSlot(holder: Holder, element: String): Int = layouts.slot(MENU, element) +
        if (element in setOf("back", "refresh")) holder.geometry.footerOffset else 0

    private class Holder(override val playerId: UUID, override val configGeneration: Long) : ArcRanksInventoryHolder {
        lateinit var menu: Inventory
        var geometry = DailyQuestLayout(3)
        var pending = false
        var board: DailyQuestBoard? = null
        override fun getInventory() = menu
    }

    private companion object { val MENU = ArcRanksMenuLayouts.DAILY_QUESTS }
}
