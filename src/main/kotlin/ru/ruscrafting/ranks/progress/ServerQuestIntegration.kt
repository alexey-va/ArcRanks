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
import java.security.MessageDigest
import java.util.logging.Level

/** Optional public success events; producers retain ownership of their gameplay transactions. */
class ServerQuestIntegration(
    private val plugin: Plugin,
    private val quests: RankQuestApi,
    private val tasks: LifecycleTaskScope,
) {
    fun install() {
        listen("ArcFarms", "ru.ruscrafting.farms.api.WorkShiftCompletedEvent") { event ->
            val kind = event.field("getKind") as String
            val objective = when (kind) { "farm" -> "farm.job"; "lumber" -> "lumber.job"; "mine" -> "mine.job"; else -> return@listen }
            val id = event.field("getEventId") as String
            val contributors = event.field("getContributors") as Set<*>
            require(contributors.size <= 1000)
            contributors.filterIsInstance<UUID>().forEach { player ->
                record("arcfarms_$kind", digest("$id:$player"), player, if (contributors.size >= 2) "$objective:team" else objective, 1)
            }
        }
        listen("ARC", "ru.arc.contracts.api.ResourceContractCommittedEvent") { event ->
            val id = event.field("getSubmissionId") as String
            val player = event.field("getPlayerId") as UUID
            val quantity = (event.field("getQuantity") as Number).toLong()
            val contractId = (event.field("getContractId") as String).lowercase(java.util.Locale.ROOT)
            require(contractId.matches(Regex("[a-z0-9_.-]{1,64}")))
            record("arc_contract_items", id, player, "contract.items:$contractId", quantity)
            record("arc_contract_delivery", id, player, "contract.delivery:$contractId", 1)
        }
    }

    private fun record(source: String, eventId: String, player: UUID, objective: String, amount: Long, attempt: Int = 0) {
        quests.record(source, eventId, player, objective, amount).whenCompleteSync(tasks) { _, failure ->
            if (failure != null) {
                if (attempt < 3) tasks.runLater(100L * (attempt + 1)) { record(source, eventId, player, objective, amount, attempt + 1) }
                else plugin.logger.log(Level.WARNING, "Could not retain quest event source=$source objective=$objective", failure)
            }
        }
    }

    private fun listen(providerName: String, eventName: String, receive: (Event) -> Unit) {
        val provider = plugin.server.pluginManager.getPlugin(providerName)?.takeIf(Plugin::isEnabled) ?: return
        val type = runCatching { Class.forName(eventName, false, provider.javaClass.classLoader).asSubclass(Event::class.java) }
            .getOrElse { plugin.logger.warning("$providerName quest events unavailable; update this provider before enabling its daily goals"); return }
        plugin.server.pluginManager.registerEvent(type, object : Listener {}, EventPriority.MONITOR,
            EventExecutor { _, event ->
                runCatching { receive(event) }.onFailure { plugin.logger.log(Level.WARNING, "Invalid $providerName quest completion event", it) }
            }, plugin, true)
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun Event.field(method: String): Any = javaClass.getMethod(method).invoke(this)
}
