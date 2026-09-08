package ru.ruscrafting.ranks.dialog

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.ruscrafting.ranks.quest.DailyQuestBoard
import ru.ruscrafting.ranks.quest.DailyQuestProgress
import ru.ruscrafting.ranks.quest.DailyQuestHints
import ru.ruscrafting.ranks.quest.DailyRewardState
import ru.ruscrafting.ranks.quest.QuestDisplayMode
import ru.ruscrafting.ranks.quest.QuestReplaceResult
import ru.ruscrafting.ranks.quest.QuestTrackingView
import ru.ruscrafting.ranks.text.RankLocale
import ru.ruscrafting.ranks.domain.SpecializationPath
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Native dialogue view of the daily board; all progress mutations remain in the quest owners. */
class DailyQuestDialogController(
    private val runtime: PaperDialogRuntime,
    private val load: (UUID) -> CompletableFuture<DailyQuestBoard>,
    private val selected: (UUID, DailyQuestBoard) -> String? = { _, _ -> null },
    private val track: (Player, DailyQuestBoard, String) -> CompletableFuture<Unit> = { _, _, _ -> CompletableFuture.completedFuture(Unit) },
    private val displayMode: (UUID) -> QuestDisplayMode = { QuestDisplayMode.SCOREBOARD },
    private val cycleDisplay: (Player) -> CompletableFuture<Unit> = { CompletableFuture.completedFuture(Unit) },
    private val diagnose: (UUID, DailyQuestProgress) -> String? = { _, _ -> null },
    private val trackingEnabled: () -> Boolean = { true },
    private val replace: (UUID, LocalDate, String) -> CompletableFuture<QuestReplaceResult> =
        { _, _, _ -> CompletableFuture.completedFuture(QuestReplaceResult.UNAVAILABLE) },
    private val generation: () -> Long = { 0L },
    private val tasks: LifecycleTaskScope,
    private val locale: () -> RankLocale,
    private val openChest: (Player) -> Unit = {},
    private val closeOnEscape: (Player) -> Boolean = { false },
) : Listener {
    private val serial = AtomicLong()
    private val navigation = ConcurrentHashMap<UUID, Navigation>()
    private val directEntries = ConcurrentHashMap.newKeySet<UUID>()

    /** Entry from another dialog keeps the existing PaperDialogHistory flow. */
    fun open(player: Player) {
        directEntries.remove(player.uniqueId)
        loadBoard(player, page = 0, targetId = CATALOG_ID)
    }

    /** Command/hotkey entry starts a new native-dialog flow. */
    fun beginFlowAndOpen(player: Player) {
        runtime.beginFlow(player)
        directEntries += player.uniqueId
        loadBoard(player, page = 0, targetId = CATALOG_ID)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        navigation.remove(event.player.uniqueId)
        directEntries.remove(event.player.uniqueId)
    }

    private fun loadBoard(player: Player, page: Int, targetId: String, detailId: String? = null, notice: Component? = null) {
        val expectedGeneration = generation()
        val token = showLoading(player, targetId, expectedGeneration)
        load(player.uniqueId).whenCompleteSync(tasks) { board, failure ->
            if (!current(player, token, expectedGeneration)) return@whenCompleteSync
            if (failure != null || board == null) {
                showError(player, page, targetId)
                return@whenCompleteSync
            }
            if (detailId != null) {
                val state = board.quests.firstOrNull { it.quest.id == detailId }
                if (state != null) showDetail(player, board, page, state, notice)
                else showCatalog(player, board, page, notice)
            } else {
                showCatalog(player, board, page, notice)
            }
        }
    }

    private fun showCatalog(player: Player, board: DailyQuestBoard, requestedPage: Int, notice: Component? = null) {
        val pages = pageCount(board)
        val page = requestedPage.coerceIn(0, pages - 1)
        val start = page * PAGE_SIZE
        val visible = board.quests.drop(start).take(PAGE_SIZE)
        val trackedId = selected(player.uniqueId, board)
        val selectedPath = board.selectedFocus?.let { locale().render("paths.${it.name.lowercase()}.name", player) }
            ?: tr("dialogs.common.none", player)
        val values = mapOf(
            "date" to locale().text(board.day),
            "completed" to locale().text(board.quests.count { it.completed }),
            "total" to locale().text(board.quests.size),
            "replacements" to locale().text(board.replacementsLeft),
            "page" to locale().text(page + 1),
            "pages" to locale().text(pages),
            "mode" to locale().render("daily.display.${displayMode(player.uniqueId).name.lowercase()}", player),
            "path" to selectedPath,
        )
        val sections = buildList {
            notice?.let { add(PaperDialogBody(it, BODY_WIDTH)) }
            add(body(if (visible.isEmpty()) "daily-dialog.empty" else "daily-dialog.intro", player))
            add(body("daily-dialog.summary", player, values))
            add(body("daily-dialog.auto-rewards", player))
            add(body("daily-dialog.selected-path", player, values))
        }
        val buttons = buildList {
            visible.forEach { state ->
                val questName = questName(player, state)
                val labelKey = when {
                    state.completed -> "daily-dialog.goal-completed"
                    state.quest.id in board.unavailableQuestIds -> "daily-dialog.goal-unavailable"
                    trackedId == state.quest.id -> "daily-dialog.goal-selected"
                    else -> "daily-dialog.goal"
                }
                add(button(
                    "goal_${state.quest.id}",
                    tr(labelKey, player, mapOf(
                        "quest-name" to questName,
                        "value" to locale().text(QuestTrackingView.of(state).value),
                        "target" to locale().text(QuestTrackingView.of(state).target),
                    )),
                    tr("daily-dialog.goal-tooltip", player),
                ) { showDetail(player, board, page, state) })
            }
            if (visible.size % 2 == 1) add(unavailableButton("padding", Component.empty(), Component.empty()))
            if (trackingEnabled()) {
                add(button("tracker", tr("daily-dialog.tracker", player, mapOf(
                    "mode" to values.getValue("mode"),
                )), tr("daily-dialog.tracker-tooltip", player)) { cycleTracker(player, page) })
            } else {
                add(unavailableButton("tracker", tr("daily-dialog.tracker-disabled", player), tr("daily-dialog.tracker-disabled-tooltip", player)))
            }
            add(button("chest", "daily-dialog.chest", player, "daily-dialog.chest-tooltip") { leaveForChest(player) })
            if (page > 0) add(button("previous", "daily-dialog.previous", player) {
                loadBoard(player, page - 1, CATALOG_ID)
            })
            if (page < pages - 1) add(button("next", "daily-dialog.next", player) {
                loadBoard(player, page + 1, CATALOG_ID)
            })
        }
        present(
            player,
            PaperDialogScreen(
                id = CATALOG_ID,
                title = tr("daily-dialog.title", player),
                body = sections,
                buttons = buttons,
                exitButton = footer(player),
                columns = 2,
            ),
            reopen = { loadBoard(player, page, CATALOG_ID) },
        )
    }

    private fun showDetail(player: Player, board: DailyQuestBoard, page: Int, state: DailyQuestProgress, notice: Component? = null) {
        val quest = state.quest
        val view = QuestTrackingView.of(state)
        val questName = questName(player, state)
        val values = mapOf(
            "quest-name" to questName,
            "value" to locale().text(view.value),
            "target" to locale().text(view.target),
            "bar" to progressBar(view.value, view.target),
            "step-name" to (view.stepTextId?.let { locale().render("daily.${it}.name", player) } ?: questName),
            "step-value" to locale().text(view.value),
            "step-target" to locale().text(view.target),
            "bonus" to locale().text(quest.bonus),
            "path" to (SpecializationPath.entries.firstOrNull { it.owns(quest.metric) }
                ?.let { locale().render("paths.${it.name.lowercase()}.name", player) }
                ?: tr("dialogs.common.none", player)),
            "money" to locale().text(quest.money),
            "tokens" to locale().text(quest.tokens),
            "challenge-value" to locale().text(state.challengeValue),
            "extra-money" to locale().text((quest.money * quest.challengePercent + 99) / 100),
            "replacements" to locale().text(board.replacementsLeft),
        )
        val detail = buildList {
            notice?.let { add(PaperDialogBody(it, BODY_WIDTH)) }
            add(body("daily-dialog.detail", player, values))
            add(body(if (quest.tokens > 0) "daily-dialog.rewards-rare" else "daily-dialog.rewards", player, values))
            if (view.stepTextId != null) add(body("daily-dialog.current-step", player, values))
            if (quest.challengePercent > 0) addAll(locale().renderLines(
                "daily.challenge", player, values + mapOf("target" to locale().text(quest.target)),
            ).map { PaperDialogBody(it, BODY_WIDTH) })
            addAll(conditionLines(player, state, values))
            if (state.completed) {
                addAll(locale().renderLines(
                    when (state.rewardState) {
                        DailyRewardState.GRANTED -> "daily.completed"
                        DailyRewardState.RECOVERY -> "daily.recovery"
                        else -> "daily.pending"
                    }, player, values,
                ).map { PaperDialogBody(it, BODY_WIDTH) })
            }
        }
        val buttons = buildList {
            if (!state.completed && trackingEnabled()) {
                val tracked = selected(player.uniqueId, board) == quest.id
                add(button(
                    "track",
                    tr(if (tracked) "daily-dialog.untrack" else "daily-dialog.track", player, mapOf("quest-name" to questName)),
                    tr("daily-dialog.track-tooltip", player),
                ) { toggleTracking(player, board, page, quest.id) })
            }
            if (!state.completed) {
                if (board.replacementsLeft > 0) {
                    add(button("replace", tr("daily-dialog.replace", player, mapOf("replacements" to locale().text(board.replacementsLeft))), tr("daily-dialog.replace-tooltip", player)) {
                        replaceQuest(player, board, page, quest.id)
                    })
                } else {
                    add(unavailableButton("replace", tr("daily-dialog.replace-disabled", player, values), tr("daily-dialog.replace-disabled-tooltip", player)))
                }
            }
            add(button("chest", "daily-dialog.chest", player, "daily-dialog.chest-tooltip") { leaveForChest(player) })
        }
        present(
            player,
            PaperDialogScreen(
                id = DETAIL_ID,
                title = tr("daily-dialog.detail-title", player, mapOf("quest-name" to questName)),
                body = detail,
                buttons = buttons,
                exitButton = footer(player),
                columns = 2,
            ),
            reopen = { loadBoard(player, page, DETAIL_ID, quest.id) },
        )
    }

    private fun conditionLines(player: Player, state: DailyQuestProgress, values: Map<String, Component>): List<PaperDialogBody> {
        val text = locale()
        val lines = mutableListOf<PaperDialogBody>()
        lines += body("daily-dialog.conditions-title", player)
        state.quest.plan?.let { plan ->
            plan.steps.forEachIndexed { index, step ->
                lines += body("daily-dialog.step", player, values + mapOf(
                    "number" to text.text(index + 1),
                    "step-name" to text.render("daily.${step.textId}.name", player),
                    "step-value" to text.text(state.stepValues[index]),
                    "step-target" to text.text(step.target),
                ))
            }
        }
        DailyQuestHints.categories(state).forEach { category ->
            lines += PaperDialogBody(text.renderLines("daily.hints.$category", player, values).joinLines(), BODY_WIDTH)
        }
        diagnose(player.uniqueId, state)?.let { reason ->
            lines += body("daily-dialog.diagnostic", player, mapOf(
                "reason" to text.render("daily.reason.$reason", player),
            ))
        }
        if (!state.completed) {
            val view = QuestTrackingView.of(state)
            DailyQuestHints.category(view.objective)?.let { category ->
                if (category !in DailyQuestHints.categories(state)) {
                    lines += body("daily-dialog.next-step", player, mapOf(
                        "next" to text.render("daily.next.$category", player),
                    ))
                }
            }
        }
        return lines
    }

    private fun cycleTracker(player: Player, page: Int) {
        val expectedGeneration = generation()
        val token = showLoading(player, CATALOG_ID, expectedGeneration)
        cycleDisplay(player).thenCompose { load(player.uniqueId) }.whenCompleteSync(tasks) { board, failure ->
            if (!current(player, token, expectedGeneration)) return@whenCompleteSync
            if (failure != null || board == null) {
                showError(player, page, CATALOG_ID)
            } else showCatalog(player, board, page)
        }
    }

    private fun toggleTracking(player: Player, board: DailyQuestBoard, page: Int, questId: String) {
        val expectedGeneration = generation()
        val token = showLoading(player, DETAIL_ID, expectedGeneration)
        track(player, board, questId).thenCompose { load(player.uniqueId) }.whenCompleteSync(tasks) { fresh, failure ->
            if (!current(player, token, expectedGeneration)) return@whenCompleteSync
            val state = fresh?.quests?.firstOrNull { it.quest.id == questId }
            if (failure != null || fresh == null || state == null) {
                showCatalog(player, fresh ?: board, page, tr("daily-dialog.action-failed", player))
            } else showDetail(player, fresh, page, state)
        }
    }

    private fun replaceQuest(player: Player, board: DailyQuestBoard, page: Int, questId: String) {
        val expectedGeneration = generation()
        val token = showLoading(player, DETAIL_ID, expectedGeneration)
        replace(player.uniqueId, board.day, questId).whenCompleteSync(tasks) { result, replaceFailure ->
            if (!current(player, token, expectedGeneration)) return@whenCompleteSync
            load(player.uniqueId).whenCompleteSync(tasks) { fresh, loadFailure ->
                if (!current(player, token, expectedGeneration)) return@whenCompleteSync
                val resultKey = if (replaceFailure == null && result != null) "daily-dialog.replace-result.${result.name.lowercase()}" else "daily-dialog.replace-result.error"
                val notice = tr(resultKey, player)
                if (loadFailure != null || fresh == null) showError(player, page, CATALOG_ID)
                else {
                    val state = fresh.quests.firstOrNull { it.quest.id == questId }
                    if (state != null) showDetail(player, fresh, page, state, notice)
                else {
                    val previousIds = board.quests.mapTo(hashSetOf()) { it.quest.id }
                    val replacement = fresh.quests.firstOrNull { it.quest.id !in previousIds }
                    if (replacement != null) showDetail(player, fresh, page, replacement, notice)
                    else showCatalog(player, fresh, page, notice)
                }
                }
            }
        }
    }

    private fun showLoading(player: Player, targetId: String, expectedGeneration: Long): Long {
        val token = markNavigation(player, expectedGeneration)
        present(
            player,
            PaperDialogScreen(
                id = targetId,
                title = tr("daily-dialog.title", player),
                body = listOf(body("daily-dialog.loading", player)),
                buttons = emptyList(),
                exitButton = footer(player),
            ),
            reopen = null,
        )
        return token
    }

    private fun showError(player: Player, page: Int, targetId: String) {
        present(
            player,
            PaperDialogScreen(
                id = targetId,
                title = tr("daily-dialog.error-title", player),
                body = listOf(body("daily-dialog.error", player)),
                buttons = listOf(button("retry", "daily-dialog.retry", player) { loadBoard(player, page, targetId) }),
                exitButton = footer(player),
            ),
            reopen = { loadBoard(player, page, targetId) },
        )
    }

    private fun present(player: Player, screen: PaperDialogScreen, reopen: (() -> Unit)? = null) {
        val directCatalog = screen.id == CATALOG_ID && player.uniqueId in directEntries
        val footer = (screen.exitButton ?: footer(player)).copy(
            label = tr(if (closeOnEscape(player) || directCatalog) "dialogs.common.close" else "dialogs.common.back", player),
            width = 200,
        )
        runtime.open(player, screen.copy(exitButton = footer), reopen, { markNavigation(player, generation()) }, closeOnEscape(player))
    }

    private fun footer(player: Player): PaperDialogButton = button(
        "daily_footer",
        if (closeOnEscape(player) || player.uniqueId in directEntries) "dialogs.common.close" else "dialogs.common.back",
        player,
    ) {}.copy(width = 200)

    private fun leaveForChest(player: Player) {
        runtime.close(player)
        openChest(player)
    }

    private fun body(key: String, player: Player, values: Map<String, Component> = emptyMap()): PaperDialogBody =
        PaperDialogBody(tr(key, player, values), BODY_WIDTH)

    private fun button(id: String, key: String, player: Player, tooltipKey: String? = null, onClick: () -> Unit): PaperDialogButton =
        button(id, tr(key, player), tooltipKey?.let { tr(it, player) } ?: Component.empty(), onClick)

    private fun button(id: String, label: Component, tooltip: Component, onClick: () -> Unit): PaperDialogButton {
        val renderedGeneration = generation()
        return PaperDialogButton(
            id = PaperDialogActionId.of(id), label = label, tooltip = tooltip, width = BUTTON_WIDTH,
            onClick = onClick@{
                if (generation() != renderedGeneration) return@onClick
                markNavigation(it.player, generation())
                onClick()
            },
        )
    }

    private fun unavailableButton(id: String, label: Component, tooltip: Component): PaperDialogButton = PaperDialogButton(
        id = PaperDialogActionId.of(id), label = label, tooltip = tooltip, width = BUTTON_WIDTH, onClick = { },
    )

    private fun questName(player: Player, state: DailyQuestProgress): Component = Component.text(
        PlainTextComponentSerializer.plainText().serialize(tr("daily.${state.quest.textId}.name", player)),
    )

    private fun tr(key: String, player: Player, values: Map<String, Component> = emptyMap()): Component =
        locale().render(key, player, values)

    private fun pageCount(board: DailyQuestBoard): Int = maxOf(1, (board.quests.size + PAGE_SIZE - 1) / PAGE_SIZE)

    private fun markNavigation(player: Player, expectedGeneration: Long): Long = serial.incrementAndGet().also {
        navigation[player.uniqueId] = Navigation(it, expectedGeneration)
    }

    private fun current(player: Player, token: Long, expectedGeneration: Long): Boolean =
        player.isOnline && generation() == expectedGeneration && navigation[player.uniqueId] == Navigation(token, expectedGeneration)

    private data class Navigation(val token: Long, val generation: Long)

    private fun List<Component>.joinLines(): Component = Component.join(JoinConfiguration.separator(Component.newline()), this)

    private companion object {
        const val PAGE_SIZE = 6
        const val BODY_WIDTH = 468
        const val BUTTON_WIDTH = 230
        const val CATALOG_ID = "ranks.daily"
        const val DETAIL_ID = "ranks.daily.detail"
    }
}
