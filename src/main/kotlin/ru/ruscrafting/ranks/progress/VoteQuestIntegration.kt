package ru.ruscrafting.ranks.progress

import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.ruscrafting.ranks.api.RankQuestApi
import java.util.UUID
import java.util.logging.Level

/** Optional ArcVotes bridge for durable, identity-validated vote confirmations. */
class VoteQuestIntegration(
    private val plugin: Plugin,
    private val quests: RankQuestApi,
    private val tasks: LifecycleTaskScope,
) {
    fun install(): Boolean {
        val provider = plugin.server.pluginManager.getPlugin("ArcVotes")?.takeIf(Plugin::isEnabled) ?: return false
        val eventClass = runCatching {
            Class.forName(
                "ru.ruscrafting.votes.api.VoteConfirmedEvent",
                false,
                provider.javaClass.classLoader,
            ).asSubclass(Event::class.java)
        }.getOrElse {
            plugin.logger.warning("ArcVotes confirmed-vote API is unavailable; vote daily goals are disabled")
            return false
        }
        plugin.server.pluginManager.registerEvent(
            eventClass,
            object : Listener {},
            EventPriority.MONITOR,
            EventExecutor { _, event ->
                runCatching { record(event) }
                    .onFailure { plugin.logger.log(Level.WARNING, "Could not read ArcVotes confirmed-vote event", it) }
            },
            plugin,
            true,
        )
        return true
    }

    private fun record(event: Event) {
        val eventId = event.value("getEventId") as? String ?: return
        val playerId = event.value("getPlayerId") as? UUID ?: return
        record(eventId, playerId)
    }

    private fun record(eventId: String, playerId: UUID, attempt: Int = 0) {
        quests.record("arcvotes", eventId, playerId, "vote.confirmed", 1)
            .whenCompleteSync(tasks) { _, failure ->
                if (failure != null) {
                    if (attempt < 3) {
                        tasks.runLater(100L * (attempt + 1)) { record(eventId, playerId, attempt + 1) }
                    } else {
                        plugin.logger.log(Level.WARNING, "Could not record ArcVotes confirmed-vote quest progress", failure)
                    }
                }
            }
    }

    private fun Any.value(method: String): Any? = javaClass.getMethod(method).invoke(this)
}
