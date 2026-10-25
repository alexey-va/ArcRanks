package ru.ruscrafting.ranks.dialog

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.ruscrafting.ranks.clan.ClanQuestBoard
import ru.ruscrafting.ranks.clan.ClanQuestProgress
import ru.ruscrafting.ranks.text.RankLocale
import ru.ruscrafting.ranks.text.TooltipLayout
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** One read-only clan board, shared by the personal-quest and team entry points. */
class ClanQuestDialogController(
    private val runtime: PaperDialogRuntime,
    private val load: (UUID) -> CompletableFuture<ClanQuestBoard?>,
    private val tasks: LifecycleTaskScope,
    private val locale: () -> RankLocale,
    private val generation: () -> Long = { 0L },
    private val closeOnEscape: (Player) -> Boolean = { false },
    private val openDungeon: ((Player) -> Unit)? = null,
) : Listener {
    private val serial = AtomicLong()
    private val visits = ConcurrentHashMap<UUID, Long>()

    fun open(player: Player) = loadBoard(player)

    fun beginFlowAndOpen(player: Player) {
        runtime.beginFlow(player)
        open(player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { visits.remove(event.player.uniqueId) }

    private fun loadBoard(player: Player, questId: String? = null) {
        val version = generation()
        val token = nextVisit(player)
        val screenId = if (questId == null) BOARD_ID else DETAIL_ID
        present(player, PaperDialogScreen(
            id = screenId, title = tr("clan-dialog.title", player),
            body = listOf(body("clan-dialog.loading", player)), buttons = emptyList(), exitButton = footer(player),
        ))
        load(player.uniqueId).whenCompleteSync(tasks) { board, failure ->
            if (!player.isOnline || visits[player.uniqueId] != token || generation() != version) return@whenCompleteSync
            when {
                failure != null -> unavailable(player, questId)
                board == null -> noClan(player)
                else -> {
                    val quest = board.quests.firstOrNull { it.quest.id == questId }
                    if (quest != null) detail(player, board, quest) else catalog(player, board)
                }
            }
        }
    }

    private fun catalog(player: Player, board: ClanQuestBoard) {
        val values = boardValues(board)
        present(player, PaperDialogScreen(
            id = BOARD_ID, title = tr("clan-dialog.title", player),
            body = buildList {
                add(DialogTables.body(listOf(
                    tr("clan-dialog.clan-label", player) to locale().text(board.clanName),
                    tr("clan-dialog.projects-label", player) to tr("clan-dialog.count", player, values),
                    tr("clan-dialog.renown-label", player) to locale().text(board.renown),
                ), headers = null, frame = DialogTables.Frame.EPIC, width = BODY_WIDTH, columns = DialogTables.Columns.BALANCED))
                if (!board.contributionAllowed) add(body("clan-dialog.bound-elsewhere", player, values))
                add(body("clan-dialog.cooperation", player))
                add(body("clan-dialog.reset", player, values))
            },
            buttons = board.quests.map { state ->
                button("clan_goal_${state.quest.id}",
                    tr(if (state.completed) "clan-dialog.goal-completed" else "clan-dialog.goal", player, questValues(player, state)),
                    TooltipLayout.dialog(buildList {
                        add(tr("clan-quests.${state.quest.textId}.description", player))
                        add(Component.empty())
                        add(tr("clan-dialog.participants", player, questValues(player, state)))
                        add(tr("clan-dialog.reward", player, questValues(player, state)))
                    }),
                ) { loadBoard(player, state.quest.id) }
            } + button("clan_refresh", tr("clan-dialog.refresh", player)) { loadBoard(player) },
            columns = 1, exitButton = footer(player),
        ), reopen = { loadBoard(player) })
    }

    private fun detail(player: Player, board: ClanQuestBoard, state: ClanQuestProgress) {
        val values = boardValues(board) + questValues(player, state)
        present(player, PaperDialogScreen(
            id = DETAIL_ID, title = tr("clan-dialog.detail-title", player, values),
            body = buildList {
                add(body("clan-dialog.source", player, values))
                add(body("clan-quests.${state.quest.textId}.description", player))
                add(DialogTables.filledBody(
                    rows = state.quest.plan.steps.mapIndexed { index, step -> listOf(
                        tr("daily.${step.textId}.name", player),
                        tr(if (state.values[index] >= step.target) "clan-dialog.step-complete" else "clan-dialog.step-progress", player,
                            mapOf("value" to locale().text(state.values[index]), "target" to locale().text(step.target))),
                        if (state.quest.groupActivity) locale().text(state.myContributions[index]) else
                            tr("clan-dialog.my-step", player, mapOf(
                                "value" to locale().text(state.myContributions[index]),
                                "cap" to locale().text(state.quest.contributionCap(index)),
                            )),
                    ) },
                    headers = listOf(tr("clan-dialog.objective-label", player), tr("clan-dialog.clan-label", player), tr("clan-dialog.you-label", player)),
                    width = BODY_WIDTH, columnWeights = listOf(4, 2, 2),
                ))
                add(body("clan-dialog.participants", player, values))
                add(body(if (state.quest.groupActivity) "clan-dialog.group-rule" else "clan-dialog.cap-rule", player, values))
                if (!board.contributionAllowed && !state.completed) add(body("clan-dialog.bound-elsewhere", player, values))
                add(body(if (state.completed) "clan-dialog.reward-earned" else "clan-dialog.reward", player, values))
                add(body("clan-dialog.renown-explanation", player))
                add(body("clan-dialog.reset", player, values))
            },
            buttons = buildList {
                if (!state.completed && openDungeon != null && state.quest.plan.steps.any {
                    it.objective in setOf("clan.elite_kill", "clan.boss_kill", "clan.dungeon_run")
                }) {
                    add(button("clan_dungeon", tr("clan-dialog.dungeons", player), tr("clan-dialog.dungeons-tooltip", player)) {
                        openDungeon.invoke(player)
                    }.copy(closeDialogBeforeAction = true))
                }
                if (!state.completed && state.quest.plan.steps.any { it.objective == "clan.arena_match" } &&
                    player.server.pluginManager.isPluginEnabled("ArcDuels")) {
                    add(button("clan_arena", tr("clan-dialog.arena", player), tr("clan-dialog.arena-tooltip", player)) {
                        player.performCommand("duel")
                    }.copy(closeDialogBeforeAction = true))
                }
                add(button("clan_refresh", tr("clan-dialog.refresh", player)) { loadBoard(player, state.quest.id) })
            },
            columns = 1, exitButton = footer(player),
        ), reopen = { loadBoard(player, state.quest.id) })
    }

    private fun noClan(player: Player) = present(player, PaperDialogScreen(
        id = BOARD_ID, title = tr("clan-dialog.title", player),
        body = listOf(body("clan-dialog.no-clan", player)),
        buttons = listOf(button("clan_browse", tr("clan-dialog.browse", player)) {
            player.performCommand("clans")
        }.copy(closeDialogBeforeAction = true)),
        exitButton = footer(player), columns = 1,
    ), reopen = { loadBoard(player) })

    private fun unavailable(player: Player, questId: String?) = present(player, PaperDialogScreen(
        id = if (questId == null) BOARD_ID else DETAIL_ID, title = tr("clan-dialog.title", player),
        body = listOf(body("clan-dialog.unavailable", player)),
        buttons = listOf(button("clan_retry", tr("clan-dialog.refresh", player)) { loadBoard(player, questId) }),
        exitButton = footer(player), columns = 1,
    ), reopen = { loadBoard(player, questId) })

    private fun boardValues(board: ClanQuestBoard): Map<String, Component> = mapOf(
        "clan" to locale().text(board.clanName), "completed" to locale().text(board.completedCount),
        "total" to locale().text(board.quests.size), "reset" to locale().text(board.week.plusWeeks(1).format(DATE)),
    )

    private fun questValues(player: Player, state: ClanQuestProgress): Map<String, Component> = mapOf(
        "quest" to tr("clan-quests.${state.quest.textId}.name", player),
        "value" to locale().text(if (state.quest.plan.steps.size == 1) state.values.single() else state.quest.plan.progress(state.values)),
        "target" to locale().text(state.quest.plan.steps.singleOrNull()?.target ?: state.quest.plan.target),
        "contributors" to locale().text(state.contributors), "minimum" to locale().text(state.quest.minContributors),
        "cap" to locale().text(state.quest.capPercent), "renown" to locale().text(state.quest.renown),
    )

    private fun present(player: Player, screen: PaperDialogScreen, reopen: (() -> Unit)? = null) {
        val footer = screen.exitButton.takeUnless { screen.id == BOARD_ID }
        runtime.open(player, screen.copy(exitButton = footer), reopen, { nextVisit(player) },
            closeOnEscape(player) && footer?.closeDialogBeforeAction != false)
    }

    private fun nextVisit(player: Player): Long = serial.incrementAndGet().also { visits[player.uniqueId] = it }
    private fun footer(player: Player) = button("clan_footer", tr("dialogs.common.back", player)) {}.copy(width = 200)
    private fun tr(key: String, player: Player, values: Map<String, Component> = emptyMap()) = locale().render(key, player, values)
    private fun body(key: String, player: Player, values: Map<String, Component> = emptyMap()) = PaperDialogBody(tr(key, player, values), BODY_WIDTH)
    private fun button(id: String, label: Component, tooltip: Component = Component.empty(), action: () -> Unit): PaperDialogButton {
        val version = generation()
        return PaperDialogButton(PaperDialogActionId.of(id), label, tooltip = tooltip, width = BODY_WIDTH,
            onClick = { if (generation() == version) action() })
    }

    private companion object {
        const val BOARD_ID = "ranks.clan"
        const val DETAIL_ID = "ranks.clan.detail"
        const val BODY_WIDTH = 320
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}
