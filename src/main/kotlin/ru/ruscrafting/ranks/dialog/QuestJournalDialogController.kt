package ru.ruscrafting.ranks.dialog

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.paper.menu.DialogDecoration
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogNavigation
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.ruscrafting.onboarding.api.StoryQuestService
import ru.ruscrafting.ranks.text.RankLocale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import java.util.logging.Logger

/** One journal entry point; the existing owners retain authored chains and recurring goals. */
class QuestJournalDialogController(
    private val runtime: PaperDialogRuntime,
    private val stories: () -> StoryQuestService?,
    private val openDaily: (Player) -> Unit,
    private val openClan: (Player) -> Unit,
    private val dailySummary: (Player) -> Pair<Component, Component>?,
    private val refreshTracking: (UUID) -> Unit,
    private val tasks: LifecycleTaskScope,
    private val locale: () -> RankLocale,
    private val logger: Logger,
    private val closeOnEscape: (Player) -> Boolean = { false },
    private val generation: () -> Long = { 0L },
) : Listener {
    private val visits = mutableMapOf<UUID, Visit>()
    private var serial = 0L

    fun beginFlowAndOpen(player: Player) {
        runtime.beginFlow(player)
        open(player)
    }

    fun open(player: Player) { show(player) }

    @EventHandler fun onQuit(event: PlayerQuitEvent) { visits.remove(event.player.uniqueId) }

    private fun show(player: Player, requestedPage: Int = 0, notice: String? = null, saving: Boolean = false): Visit {
        val visit = Visit(++serial, generation())
        visits[player.uniqueId] = visit
        val provider = stories()
        val snapshot = runCatching {
            if (provider?.ready(player.uniqueId) == true) provider.quests(player.uniqueId).toList() else null
        }.onFailure { logger.log(Level.WARNING, "Could not read story quest journal for ${player.uniqueId}", it) }.getOrNull()
        val ordered = snapshot.orEmpty().sortedWith(compareByDescending<StoryQuestService.Quest> { it.tracked() }
            .thenBy { when (it.status()) {
                StoryQuestService.Status.ACTIVE -> 0
                StoryQuestService.Status.AVAILABLE -> 1
                StoryQuestService.Status.COMPLETED -> 2
            } })
        val pages = maxOf(1, (ordered.size + PAGE_SIZE - 1) / PAGE_SIZE)
        val page = requestedPage.coerceIn(0, pages - 1)
        val tracked = ordered.firstOrNull { it.tracked() }
        val daily = if (tracked == null) dailySummary(player) else null
        val screen = PaperDialogScreen(
            id = "ranks.journal",
            title = tr("quest-journal.title", player),
            body = buildList {
                add(DialogDecoration.library().headingBody(tr("quest-journal.current", player), WIDTH,
                    DialogDecoration.HeadingStyle.DIAMOND))
                val current = when {
                    tracked != null -> listOf(
                        tr("quest-journal.quest", player) to Component.text(tracked.title()),
                        tr("quest-journal.goal", player) to Component.text(tracked.currentGoal()),
                        tr("quest-journal.progress", player) to progress(player, tracked),
                    )
                    daily != null -> listOf(tr("quest-journal.quest", player) to daily.first,
                        tr("quest-journal.goal", player) to daily.second)
                    else -> listOf(tr("quest-journal.quest", player) to tr("quest-journal.none", player))
                }
                add(DialogTables.factsBody(current, DialogTables.FactsLayout.ROWS,
                    DialogTables.PanelSpec(DialogTables.PanelSkin.NAVY, WIDTH, 1, false)))
                add(DialogDecoration.library().headingBody(tr("quest-journal.stories", player), WIDTH,
                    DialogDecoration.HeadingStyle.DIAMOND))
                val state = when {
                    saving -> "quest-journal.saving"
                    notice != null -> notice
                    provider == null -> "quest-journal.unavailable"
                    snapshot == null -> "quest-journal.loading"
                    snapshot.isEmpty() -> "quest-journal.empty"
                    else -> null
                }
                if (state != null) add(DialogTextLayout.modelBody(tr(state, player), width = WIDTH))
                if (pages > 1) add(DialogTextLayout.modelBody(locale().render("daily-dialog.page", player,
                    mapOf("page" to locale().text(page + 1), "pages" to locale().text(pages))), width = WIDTH))
            },
            buttons = buildList {
                if (!saving) {
                    ordered.drop(page * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, quest ->
                        add(button("story_$index", locale().render("quest-journal.${when {
                            quest.tracked() -> "tracked-entry"
                            quest.status() == StoryQuestService.Status.COMPLETED -> "completed-entry"
                            else -> "entry"
                        }}", player, mapOf("quest" to Component.text(quest.title()))),
                            Component.text(quest.summary()).append(Component.newline()).append(
                                tr("quest-journal.status-${quest.status().name.lowercase()}", player)
                            ).append(Component.newline()).append(progress(player, quest))) {
                            if (current(player, visit)) openStory(player, quest.id(), page)
                        })
                    }
                    if (tracked != null) add(button("stop_tracking", tr("quest-journal.stop", player)) {
                        if (current(player, visit)) clearTracking(player, page)
                    })
                    if (page > 0) add(PaperDialogNavigation.previous(button("previous", tr("daily-dialog.previous", player)) {
                        if (current(player, visit)) show(player, page - 1)
                    }, tr("daily-dialog.previous", player)))
                    if (page < pages - 1) add(PaperDialogNavigation.next(button("next", tr("daily-dialog.next", player)) {
                        if (current(player, visit)) show(player, page + 1)
                    }, tr("daily-dialog.next", player)))
                }
                add(button("daily", tr("quest-journal.daily", player), tr("quest-journal.daily-tip", player)) {
                    if (current(player, visit)) openDaily(player)
                })
                add(button("clan", tr("quest-journal.clan", player), tr("quest-journal.clan-tip", player)) {
                    if (current(player, visit)) openClan(player)
                })
                if (!saving) add(button("refresh", tr("quest-journal.refresh", player)) {
                    if (current(player, visit)) show(player, page)
                })
            },
            columns = 2,
        )
        runtime.open(player, screen, { show(player, page) }, { visits.remove(player.uniqueId, visit) }, closeOnEscape(player))
        return visit
    }

    private fun openStory(player: Player, id: String, page: Int) {
        val provider = stories()
        if (provider == null || !provider.ready(player.uniqueId) || !provider.open(player.uniqueId, id)) {
            show(player, page, "quest-journal.unavailable")
        }
    }

    private fun clearTracking(player: Player, page: Int) {
        val provider = stories() ?: run { show(player, page, "quest-journal.unavailable"); return }
        val visit = show(player, page, saving = true)
        runCatching { provider.track(player.uniqueId, null).toCompletableFuture() }
            .getOrElse { CompletableFuture.failedFuture(it) }.whenCompleteSync(tasks) { _, failure ->
                if (failure != null) logger.log(Level.WARNING, "Could not clear story quest tracking for ${player.uniqueId}", failure)
                else refreshTracking(player.uniqueId)
                if (current(player, visit)) show(player, page, if (failure == null) null else "quest-journal.failed")
            }
    }

    private fun progress(player: Player, quest: StoryQuestService.Quest) = locale().render("quest-journal.steps", player,
        mapOf("done" to locale().text(quest.completedSteps()), "total" to locale().text(quest.totalSteps())))

    private fun button(id: String, label: Component, tooltip: Component = Component.empty(), action: () -> Unit) = PaperDialogButton(
        id = PaperDialogActionId.of(id), label = label, tooltip = tooltip, width = BUTTON_WIDTH, onClick = { action() },
    )

    private fun tr(key: String, player: Player) = locale().render(key, player)
    private fun current(player: Player, visit: Visit) = player.isOnline && generation() == visit.generation && visits[player.uniqueId] == visit
    private data class Visit(val token: Long, val generation: Long)

    private companion object { const val WIDTH = 420; const val BUTTON_WIDTH = 210; const val PAGE_SIZE = 8 }
}
