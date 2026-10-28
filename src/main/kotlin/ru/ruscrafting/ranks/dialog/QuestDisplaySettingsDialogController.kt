package ru.ruscrafting.ranks.dialog

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
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
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.ruscrafting.ranks.quest.QuestDisplayMode
import ru.ruscrafting.ranks.text.RankLocale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Explicit display preferences, entered from the shared settings flow. */
class QuestDisplaySettingsDialogController(
    private val runtime: PaperDialogRuntime,
    private val mode: (UUID) -> QuestDisplayMode,
    private val available: (UUID) -> Boolean,
    private val select: (Player, QuestDisplayMode) -> CompletableFuture<Unit>,
    private val tasks: LifecycleTaskScope,
    private val locale: () -> RankLocale,
    private val closeOnEscape: (Player) -> Boolean = { false },
    private val generation: () -> Long = { 0L },
) : Listener {
    private val serial = AtomicLong()
    private val visits = ConcurrentHashMap<UUID, Visit>()

    fun beginFlowAndOpen(player: Player) {
        runtime.beginFlow(player)
        open(player)
    }

    fun open(player: Player) {
        show(player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        visits.remove(event.player.uniqueId)
    }

    private fun show(player: Player, notice: String? = null, saving: Boolean = false): Visit {
        val visit = Visit(serial.incrementAndGet(), generation())
        visits[player.uniqueId] = visit
        val ready = available(player.uniqueId)
        val selected = mode(player.uniqueId)
        val screen = PaperDialogScreen(
            id = "ranks.quest-display",
            title = tr("quest-display.title", player),
            body = buildList {
                add(DialogDecoration.library().headingBody(
                    tr("quest-display.heading", player), WIDTH, DialogDecoration.HeadingStyle.DIAMOND,
                ))
                add(DialogTables.factsBody(QuestDisplayMode.entries.map { choice ->
                    tr("daily.display.${choice.name.lowercase()}", player) to tr("quest-display.${choice.name.lowercase()}", player)
                }, DialogTables.FactsLayout.ROWS, DialogTables.PanelSpec(DialogTables.PanelSkin.NAVY, WIDTH, 1, false)))
                add(DialogTextLayout.modelBody(tr("quest-display.hint", player), width = WIDTH))
                val state = when {
                    saving -> "quest-display.saving"
                    notice != null -> notice
                    !ready -> "quest-display.unavailable"
                    else -> null
                }
                state?.let { add(DialogTextLayout.modelBody(tr(it, player), width = WIDTH)) }
            },
            buttons = when {
                saving -> emptyList()
                !ready -> listOf(PaperDialogButton(
                    id = PaperDialogActionId.of("retry"), label = tr("daily-dialog.retry", player), width = BUTTON_WIDTH,
                    onClick = { if (current(player, visit)) open(player) },
                ))
                else -> QuestDisplayMode.entries.map { choice ->
                    PaperDialogButton(
                        id = PaperDialogActionId.of("mode_${choice.name.lowercase()}"),
                        label = locale().render(if (choice == selected) "quest-display.selected" else "quest-display.choice", player,
                            mapOf("mode" to Component.text(PlainTextComponentSerializer.plainText().serialize(
                                tr("daily.display.${choice.name.lowercase()}", player),
                            )))),
                        tooltip = tr("quest-display.${choice.name.lowercase()}", player),
                        width = BUTTON_WIDTH,
                        onClick = { if (current(player, visit)) choose(player, choice) },
                    )
                }
            },
            columns = 1,
        )
        runtime.open(player, screen, { open(player) }, { visits.remove(player.uniqueId, visit) }, closeOnEscape(player))
        return visit
    }

    private fun choose(player: Player, choice: QuestDisplayMode) {
        if (!available(player.uniqueId)) {
            show(player, "quest-display.unavailable")
            return
        }
        if (mode(player.uniqueId) == choice) {
            open(player)
            return
        }
        val visit = show(player, saving = true)
        runCatching { select(player, choice) }.getOrElse { CompletableFuture.failedFuture(it) }
            .whenCompleteSync(tasks) { _, failure ->
                if (!current(player, visit)) return@whenCompleteSync
                show(player, if (failure != null) "quest-display.failed" else null)
            }
    }

    private fun current(player: Player, visit: Visit): Boolean =
        player.isOnline && generation() == visit.generation && visits[player.uniqueId] == visit

    private fun tr(key: String, player: Player): Component = locale().render(key, player)

    private data class Visit(val token: Long, val generation: Long)

    private companion object {
        const val WIDTH = 380
        const val BUTTON_WIDTH = 320
    }
}
