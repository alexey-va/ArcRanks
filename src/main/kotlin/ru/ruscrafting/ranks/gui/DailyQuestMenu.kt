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
import ru.ruscrafting.ranks.quest.QuestMode
import ru.ruscrafting.ranks.quest.QuestPeriod
import ru.ruscrafting.ranks.text.RankLocale
import java.util.UUID
import java.time.LocalDate
import ru.ruscrafting.ranks.quest.QuestReplaceResult
import ru.ruscrafting.ranks.clan.ClanQuestBoard
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
    private val selected: (UUID, DailyQuestBoard) -> String? = { _, _ -> null },
    private val track: (Player, DailyQuestBoard, String) -> CompletableFuture<Unit> = { _, _, _ -> CompletableFuture.completedFuture(Unit) },
    private val displayMode: (UUID) -> ru.ruscrafting.ranks.quest.QuestDisplayMode = { ru.ruscrafting.ranks.quest.QuestDisplayMode.SCOREBOARD },
    private val cycleDisplay: (Player) -> CompletableFuture<Unit> = { CompletableFuture.completedFuture(Unit) },
    private val diagnose: (UUID, ru.ruscrafting.ranks.quest.DailyQuestProgress) -> String? = { _, _ -> null },
    private val trackingEnabled: () -> Boolean = { true },
    private val openClanQuests: (Player) -> Unit = {},
    private val loadClanQuests: (UUID) -> CompletableFuture<ClanQuestBoard?> = { CompletableFuture.completedFuture(null) },
) : Listener {
    private val items = RankMenuItemFactory { settings().gui.background }

    fun open(player: Player) {
        val holder = Holder(player.uniqueId, generation())
        holder.menu = layouts.create(holder, MENU, locale().render("daily.title", player))
        items.fill(holder.menu)
        set(player, holder, "back", "ARROW", "daily.back")
        set(player, holder, "refresh", "CLOCK", "daily.refresh")
        set(player, holder, "status", "CLOCK", "daily.loading")
        setClanEntry(player, holder, null, false)
        player.openInventory(holder.menu)
        refresh(player, holder)
    }

    private fun refresh(player: Player, holder: Holder) {
        if (holder.pending) return
        holder.pending = true
        val clan = runCatching { loadClanQuests(player.uniqueId) }
            .getOrElse { CompletableFuture.failedFuture(it) }
            .handle { board, failure -> board to (failure != null) }
        load(player.uniqueId).thenCombine(clan) { board, summary -> board to summary }.whenCompleteSync(tasks) { result, failure ->
            holder.pending = false
            if (!player.isOnline || player.openInventory.topInventory.holder !== holder ||
                holder.configGeneration != generation()) return@whenCompleteSync
            if (failure != null || result == null) {
                set(player, holder, "status", "BARRIER", "daily.error")
                return@whenCompleteSync
            }
            val (board, clanSummary) = result
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
            setClanEntry(player, holder, clanSummary.first, clanSummary.second)
            val text = locale()
            val summary = mapOf(
                "completed" to text.text(board.quests.count { it.completed }),
                "total" to text.text(board.quests.size),
                "date" to text.text(board.day),
                "replacements" to text.text(board.replacementsLeft),
                "mode" to text.render("daily.display.${displayMode(player.uniqueId).name.lowercase()}", player),
            )
            layouts.set(holder.menu, MENU, "status", items.item(
                GuiItemSpec(if (!trackingEnabled()) "CLOCK" else when (displayMode(player.uniqueId)) {
                    ru.ruscrafting.ranks.quest.QuestDisplayMode.SCOREBOARD -> "MAP"
                    ru.ruscrafting.ranks.quest.QuestDisplayMode.ACTIONBAR -> "BELL"
                    ru.ruscrafting.ranks.quest.QuestDisplayMode.OFF -> "GRAY_DYE"
                }, 0), text.render("daily.summary.name", player),
                text.renderLines("daily.summary.lore", player, summary) +
                    (if (trackingEnabled()) text.renderLines("daily.display.hint", player, summary) else emptyList()),
            ))
            val trackedId = selected(player.uniqueId, board)
            val suggestedId = if (trackedId == null) ru.ruscrafting.ranks.quest.QuestGuidance.suggestion(board) else null
            board.quests.forEachIndexed { index, state ->
                val values = mapOf(
                    "value" to text.text(state.value), "target" to text.text(state.quest.target),
                    "bonus" to text.text(state.quest.bonus),
                    "quest-name" to text.render("daily.${state.quest.textId}.name", player),
                    "money" to text.text(state.quest.money), "tokens" to text.text(state.quest.tokens),
                    "item-amount" to text.text(state.quest.itemAmount),
                    "item" to (state.quest.itemPreset?.let { text.render("gui.contracts.rewards.items.$it", player) } ?: text.text("")),
                    "key-amount" to text.text(state.quest.crateKeyAmount),
                    "key-name" to (state.quest.crateKey?.let { text.render("daily.crate-keys.$it", player) } ?: text.text("")),
                    "extra-money" to text.text((state.quest.money * state.quest.challengePercent + 99) / 100),
                    "challenge-value" to text.text(state.challengeValue),
                    "replacements" to text.text(board.replacementsLeft),
                )
                val expanded = holder.detailsId == state.quest.id
                val steps = state.quest.plan?.let { plan ->
                    val optionalComplete = state.completed && plan.mode in setOf(QuestMode.ANY, QuestMode.DISTINCT)
                    listOf(text.render(if (plan.mode == QuestMode.DISTINCT) "daily-dialog.distinct" else "daily.mode.${plan.mode.name.lowercase()}", player,
                        mapOf("required" to text.text(plan.requiredDistinct), "total" to text.text(plan.steps.size)))) +
                        (if (optionalComplete) listOf(text.render("daily-dialog.choice-completed", player)) else emptyList()) + plan.steps.mapIndexedNotNull { step, definition ->
                        if (!expanded && step != ru.ruscrafting.ranks.quest.QuestTrackingView.of(state).stepIndex) return@mapIndexedNotNull null
                        val key = when {
                            state.stepValues[step] >= definition.target -> "daily.step-done"
                            optionalComplete -> "daily-dialog.step-optional"
                            else -> "daily.step"
                        }
                        text.render(key, player,
                            mapOf("step-name" to text.render("daily.${definition.textId}.name", player),
                                "step-value" to text.text(state.stepValues[step]), "step-target" to text.text(definition.target)))
                    }
                }.orEmpty()
                val challenge = if (state.quest.challengePercent > 0) text.renderLines("daily.challenge", player, values) else emptyList()
                val hints = if (expanded) ru.ruscrafting.ranks.quest.DailyQuestHints.categories(state).filterNot { it in setOf("chain", "all", "any", "distinct") }.flatMap {
                    text.renderLines("daily.hints.$it", player, values)
                } else emptyList()
                val unavailable = if (!state.completed && state.quest.id in board.unavailableQuestIds)
                    text.renderLines("daily.hints.unavailable", player, values) else emptyList()
                val isTracked = trackedId == state.quest.id
                val view = ru.ruscrafting.ranks.quest.QuestTrackingView.of(state)
                val category = ru.ruscrafting.ranks.quest.DailyQuestHints.category(view.objective)
                val next = if (!state.completed && !expanded && category != null) listOf(text.render("daily.next.$category", player)) else emptyList()
                val reason = if (expanded && !state.completed) listOf(text.render(
                    diagnose(player.uniqueId, state)?.let { "daily.reason.$it" } ?: "daily.reason.check", player)) else emptyList()
                val pathHint = board.selectedFocus?.takeIf { it.owns(state.quest.metric) }?.let { path ->
                    listOf(text.render("daily.guidance.path", player, mapOf("path" to text.render("paths.${path.name.lowercase()}.name", player))))
                }.orEmpty()
                val suggestion = if (state.quest.id == suggestedId) listOf(text.render("daily.guidance.near", player,
                    mapOf("remaining" to text.text(view.target - view.value)))) else emptyList()
                val trackingHint = if (!state.completed && trackingEnabled())
                    text.renderLines("daily.tracking.${if (isTracked) "selected-hint" else "hint"}", player, values) else emptyList()
                val lore = listOf(
                    text.text(""),
                    text.render("daily.period.${state.quest.period.name.lowercase()}.label", player),
                    text.render("daily.period.deadline", player, mapOf("deadline" to text.renderQuestDeadline(board.expiresAt(state.quest), player))),
                    text.renderQuestPeriodHint(board, state, player),
                ) + text.renderLines("daily.${state.quest.textId}.lore", player, values) +
                    listOf(ContractProgressBar.render(view.value, view.target)) + pathHint + suggestion + next + hints + reason + unavailable + steps + challenge +
                    (if (state.quest.money > 0 || state.quest.tokens > 0) text.renderLines(if (state.quest.tokens > 0) "daily.rare-reward" else "daily.money-reward", player, values) else emptyList()) +
                    (if (state.quest.itemPreset != null) text.renderLines("daily.item-reward", player, values) else emptyList()) +
                    (if (state.quest.crateKey != null) listOf(text.render("daily.key-reward", player, values), text.render("daily.key-delivery", player)) else emptyList()) +
                    (if (!state.completed) emptyList() else text.renderLines(when {
                        state.rewardState == DailyRewardState.GRANTED -> "daily.completed"
                        state.rewardState == DailyRewardState.RECOVERY -> "daily.recovery"
                        else -> "daily.pending"
                    }, player, values)) +
                    (if (!state.completed && state.quest.period == QuestPeriod.DAILY) text.renderLines("daily.replace-hint", player, values) else emptyList()) + trackingHint + text.renderLines("daily.guidance.details-hint", player)
                val periodName = text.render("daily.period.${state.quest.period.name.lowercase()}.name", player, values)
                val name = if (state.quest.tokens > 0) text.render("daily.rare-name", player, mapOf("quest-name" to periodName)) else periodName
                holder.menu.setItem(geometry.slots[index], items.item(
                    GuiItemSpec(if (state.completed) "LIME_DYE" else ru.ruscrafting.ranks.quest.questIcon(state.quest.objective, state.quest.material), 0),
                    if (isTracked) text.render("daily.tracking.selected-name", player, mapOf("quest-name" to name)) else name, lore,
                ))
            }
        }
    }

    private fun set(player: Player, holder: Holder, element: String, material: String, key: String) {
        holder.menu.setItem(controlSlot(holder, element), items.item(
            if (element == "back") settings().gui.back else GuiItemSpec(material, 0), locale().render("$key.name", player), locale().renderLines("$key.lore", player),
        ))
    }

    private fun setClanEntry(player: Player, holder: Holder, board: ClanQuestBoard?, unavailable: Boolean) {
        val text = locale()
        val values = if (board == null) emptyMap() else mapOf(
            "clan" to text.text(board.clanName), "completed" to text.text(board.completedCount),
            "total" to text.text(board.quests.size),
        )
        val state = when { unavailable -> "unavailable"; board == null -> "no-clan"; else -> "summary" }
        holder.menu.setItem(clanSlot(holder), items.item(
            GuiItemSpec("WHITE_BANNER", 0), text.render("clan-personal.open", player),
            listOf(text.render("clan-personal.chest-$state", player, values)) + text.renderLines("clan-personal.lore", player),
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
            clanSlot(holder) -> { player.closeInventory(); openClanQuests(player) }
            controlSlot(holder, "status") -> if (trackingEnabled() && !holder.pending) {
                holder.pending = true
                cycleDisplay(player).whenCompleteSync(tasks) { _, failure ->
                    holder.pending = false
                    if (!player.isOnline || player.openInventory.topInventory.holder !== holder || holder.configGeneration != generation()) return@whenCompleteSync
                    if (failure != null) player.sendMessage(locale().chat("daily.tracking.unavailable", player))
                    refresh(player, holder)
                }
            }
            else -> if (event.isShiftClick && event.isLeftClick && !holder.pending) {
                val board = holder.board ?: return
                val goal = board.quests.getOrNull(holder.geometry.slots.indexOf(event.rawSlot)) ?: return
                holder.detailsId = if (holder.detailsId == goal.quest.id) null else goal.quest.id
                refresh(player, holder)
            } else if (event.isLeftClick && !holder.pending && trackingEnabled()) {
                val board = holder.board ?: return
                val goal = board.quests.getOrNull(holder.geometry.slots.indexOf(event.rawSlot)) ?: return
                if (goal.completed) return
                holder.pending = true
                track(player, board, goal.quest.id).whenCompleteSync(tasks) { _, failure ->
                    holder.pending = false
                    if (!player.isOnline || player.openInventory.topInventory.holder !== holder || holder.configGeneration != generation()) return@whenCompleteSync
                    if (failure != null) player.sendMessage(locale().chat("daily.tracking.unavailable", player))
                    refresh(player, holder)
                }
            } else if (event.isRightClick && !holder.pending) {
                val board = holder.board ?: return
                val index = holder.geometry.slots.indexOf(event.rawSlot)
                val goal = board.quests.getOrNull(index) ?: return
                if (goal.quest.period != QuestPeriod.DAILY) return
                holder.pending = true
                replace(player.uniqueId, board.day, goal.quest.id).whenCompleteSync(tasks) { result, failure ->
                    holder.pending = false
                    if (!player.isOnline || player.openInventory.topInventory.holder !== holder || holder.configGeneration != generation()) return@whenCompleteSync
                    player.sendMessage(locale().chat("daily.replace-result.${if (failure == null && result != null) result.name.lowercase() else "error"}", player))
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

    private fun clanSlot(holder: Holder): Int = (holder.menu.size - 5 until holder.menu.size - 1)
        .first { it != controlSlot(holder, "back") && it != controlSlot(holder, "refresh") && it != controlSlot(holder, "status") }

    private class Holder(override val playerId: UUID, override val configGeneration: Long) : ArcRanksInventoryHolder {
        lateinit var menu: Inventory
        var geometry = DailyQuestLayout(3)
        var pending = false
        var detailsId: String? = null
        var board: DailyQuestBoard? = null
        override fun getInventory() = menu
    }

    private companion object { val MENU = ArcRanksMenuLayouts.DAILY_QUESTS }
}
