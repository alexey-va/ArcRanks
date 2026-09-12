package ru.ruscrafting.ranks.quest

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.ruscrafting.ranks.text.RankLocale
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** One daily HUD per online player; scoreboard mode chooses an unfinished quest when no pin exists.
 * SQL owns the selection, lifecycle-scoped main-thread sessions own throttling and stale callbacks.
 */
class QuestTracker(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val catalog: () -> DailyQuestCatalog,
    private val locale: () -> RankLocale,
    private val repository: QuestTrackingRepository,
    private val loadBoard: (UUID) -> CompletableFuture<DailyQuestBoard>,
    private val clock: Clock = Clock.systemUTC(),
) : Listener, AutoCloseable {
    private val sessions = mutableMapOf<UUID, Session>()
    private val hud = java.util.concurrent.ConcurrentHashMap<UUID, QuestHudSnapshot>()
    private val pendingWrites = java.util.concurrent.ConcurrentHashMap.newKeySet<CompletableFuture<Unit>>()

    fun install() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.onlinePlayers.forEach(::join)
        tasks.runTimer(100, 100) {
            sessions.values.toList().forEach { session ->
                if (!session.loaded) loadSelection(session) else refresh(session.player.uniqueId)
            }
        }
    }

    @EventHandler fun onJoin(event: PlayerJoinEvent) = join(event.player)
    @EventHandler fun onQuit(event: PlayerQuitEvent) { sessions.remove(event.player.uniqueId); hud.remove(event.player.uniqueId) }
    override fun close() {
        sessions.clear()
        hud.clear()
        pendingWrites.forEach { it.cancel(false) }
        pendingWrites.clear()
    }

    private fun join(player: Player) {
        val session = Session(player)
        sessions[player.uniqueId] = session
        loadSelection(session)
    }

    private fun loadSelection(session: Session) {
        if (session.refreshing || !current(session)) return
        session.refreshing = true
        repository.load(session.player.uniqueId).thenCombine(repository.loadMode(session.player.uniqueId)) { selection, mode -> selection to mode }
            .whenCompleteSync(tasks) { loaded, failure ->
            if (!current(session)) return@whenCompleteSync
            session.refreshing = false
            session.loaded = failure == null
            session.selection = loaded?.first
            session.mode = loaded?.second ?: QuestDisplayMode.SCOREBOARD
            session.autoSelect = session.selection != null || session.mode == QuestDisplayMode.SCOREBOARD
            session.prewarm = failure == null
            if (failure == null) refresh(session.player.uniqueId)
        }
    }

    fun selected(playerId: UUID, board: DailyQuestBoard): String? =
        sessions[playerId]?.selection?.takeIf { selection -> catalog().trackingEnabled && selection.day == board.day &&
            board.quests.any { it.quest.id == selection.questId && !it.completed } }?.questId

    fun displayMode(playerId: UUID): QuestDisplayMode = sessions[playerId]?.mode ?: QuestDisplayMode.SCOREBOARD

    fun placeholder(playerId: UUID, key: String): String? =
        hud[playerId]?.takeIf { catalog().trackingEnabled && it.day == DailyQuest.day(clock.instant()) }?.placeholder(key) ?: QuestHudSnapshot.emptyPlaceholder(key)

    fun cycleDisplay(player: Player): CompletableFuture<Unit> {
        val session = sessions[player.uniqueId] ?: return unavailable(player)
        if (!current(session) || !session.loaded || session.busy) return unavailable(player)
        val next = session.mode.next()
        session.busy = true
        val result = CompletableFuture<Unit>()
        pendingWrites.add(result)
        result.whenComplete { _, _ -> pendingWrites.remove(result) }
        repository.saveMode(player.uniqueId, next).whenComplete { _, error ->
            val scheduled = tasks.runSync {
                if (current(session)) {
                    session.busy = false
                    if (error == null) {
                        session.mode = next
                        session.lastView = null
                        session.lastSentAt = null
                        hud.remove(player.uniqueId)
                        if (next == QuestDisplayMode.SCOREBOARD && session.selection == null) {
                            session.autoSelect = true
                            session.prewarm = true
                        }
                        refresh(player.uniqueId)
                    }
                }
                if (error == null) result.complete(Unit) else result.completeExceptionally(error)
            }
            if (scheduled == null) result.cancel(false)
        }
        return result
    }

    fun toggle(player: Player, board: DailyQuestBoard, questId: String): CompletableFuture<Unit> {
        val session = sessions[player.uniqueId] ?: return unavailable(player)
        val state = board.quests.firstOrNull { it.quest.id == questId }
        if (!current(session) || session.player !== player || !catalog().trackingEnabled || !session.loaded || session.busy || state == null || state.completed ||
            board.day != DailyQuest.day(clock.instant())) return unavailable(player)
        session.busy = true
        val next = TrackedQuest(board.day, questId)
        val stopping = session.selection == next
        val result = CompletableFuture<Unit>()
        pendingWrites.add(result)
        result.whenComplete { _, _ -> pendingWrites.remove(result) }
        val write = if (stopping) repository.clear(player.uniqueId, next) else repository.save(player.uniqueId, next)
        write.whenComplete { _, failure ->
            val scheduled = tasks.runSync {
                if (current(session)) {
                    session.busy = false
                    if (failure == null) {
                        session.selection = if (stopping) null else next
                        session.autoSelect = !stopping
                        hud.remove(player.uniqueId)
                        session.lastView = null
                        session.lastSentAt = null
                        player.sendMessage(locale().render("daily.tracking.${if (stopping) "stopped" else "started"}", player,
                            mapOf("quest-name" to locale().render("daily.${state.quest.textId}.name", player))))
                        if (!stopping) refresh(player.uniqueId)
                    }
                }
                if (failure == null) result.complete(Unit) else result.completeExceptionally(failure)
            }
            if (scheduled == null) result.cancel(false)
        }
        return result
    }

    /** Called after a successful local flush/external event; periodic refresh also catches rollover. */
    fun refresh(playerId: UUID) {
        val session = sessions[playerId] ?: return
        if (!catalog().trackingEnabled) { hud.remove(playerId); return }
        if (session.busy || session.refreshing || !current(session)) return
        val selection = session.selection
        if (selection == null && !session.prewarm) { hud.remove(playerId); return }
        session.refreshing = true
        session.prewarm = false
        loadBoard(playerId).whenCompleteSync(tasks) { board, failure ->
            if (!current(session) || session.busy || session.selection != selection) {
                session.refreshing = false
                return@whenCompleteSync
            }
            if (failure != null || board == null) {
                session.refreshing = false
                if (selection == null) session.prewarm = true
                return@whenCompleteSync
            }
            val state = selection?.let { current -> board.quests.firstOrNull { it.quest.id == current.questId } }
            if (selection == null || board.day != selection.day || state == null || state.completed) {
                session.selection = null
                hud.remove(playerId)
                if (selection != null && session.mode != QuestDisplayMode.OFF && board.day == selection.day && state?.completed == true) {
                    session.player.sendActionBar(locale().render("daily.tracking.completed", session.player,
                        mapOf("quest-name" to locale().render("daily.${state.quest.textId}.name", session.player))))
                }
                val fallback = if (session.autoSelect && session.mode == QuestDisplayMode.SCOREBOARD) {
                    board.quests.firstOrNull { !it.completed }
                } else null
                if (fallback != null) {
                    saveDefault(session, board, fallback)
                } else {
                    session.refreshing = false
                    if (selection != null) repository.clear(playerId, selection).exceptionally {
                        plugin.logger.warning("Could not clear expired quest tracking: ${it.javaClass.simpleName}"); null
                    }
                }
                return@whenCompleteSync
            }
            session.refreshing = false
            publish(session, board, state)
        }
    }

    private fun saveDefault(session: Session, board: DailyQuestBoard, state: DailyQuestProgress) {
        val selection = TrackedQuest(board.day, state.quest.id)
        session.busy = true
        repository.save(session.player.uniqueId, selection).whenCompleteSync(tasks) { _, failure ->
            session.busy = false
            session.refreshing = false
            if (!current(session) || session.selection != null) return@whenCompleteSync
            if (failure != null) {
                session.prewarm = true
                plugin.logger.warning("Could not save default quest tracking: ${failure.javaClass.simpleName}")
                return@whenCompleteSync
            }
            session.selection = selection
            publish(session, board, state)
        }
    }

    private fun publish(session: Session, board: DailyQuestBoard, state: DailyQuestProgress) {
        val playerId = session.player.uniqueId
        val view = QuestTrackingView.of(state)
        if (session.mode == QuestDisplayMode.SCOREBOARD) hud[playerId] = QuestHudSnapshot.render(state, session.player, locale(), board.day)
        else hud.remove(playerId)
        val changedStep = session.lastView?.let { it.stepIndex != view.stepIndex } == true
        if (session.mode == QuestDisplayMode.OFF || (session.mode == QuestDisplayMode.SCOREBOARD && !changedStep)) {
            session.lastView = view
            return
        }
        val now = clock.millis()
        if (view == session.lastView || session.lastSentAt?.let { now - it < catalog().trackingIntervalSeconds * 1000L } == true) return
        val text = locale()
        val values = mutableMapOf("quest-name" to text.render("daily.${view.textId}.name", session.player),
            "value" to text.text(view.value), "target" to text.text(view.target))
        view.stepTextId?.let { values["step-name"] = text.render("daily.$it.name", session.player) }
        session.player.sendActionBar(text.render("daily.tracking.${if (view.stepTextId != null) "step" else "counter"}", session.player, values))
        session.lastView = view
        session.lastSentAt = now
    }

    private fun unavailable(player: Player): CompletableFuture<Unit> {
        player.sendMessage(locale().render("daily.tracking.unavailable", player))
        return CompletableFuture.completedFuture(Unit)
    }

    private fun current(session: Session): Boolean = session.player.isOnline && sessions[session.player.uniqueId] === session

    private class Session(val player: Player) {
        var mode = QuestDisplayMode.SCOREBOARD
        var loaded = false
        var busy = false
        var refreshing = false
        var selection: TrackedQuest? = null
        var autoSelect = false
        var prewarm = false
        var lastView: QuestTrackingView? = null
        var lastSentAt: Long? = null
    }
}
