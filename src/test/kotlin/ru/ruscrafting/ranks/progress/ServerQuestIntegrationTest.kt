package ru.ruscrafting.ranks.progress

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.api.RankQuestApi
import ru.ruscrafting.ranks.storage.ExternalProgressResult
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ServerQuestIntegrationTest : StringSpec({
    "only an explicitly empty network node is a farm relay" {
        val config = YamlConfiguration()
        isFarmRelay(config) shouldBe false
        config.set("network.enabled", true)
        isFarmRelay(config) shouldBe false
        listOf("farm-zones", "lumber-zones", "mine-zones").forEach { config.createSection(it) }
        isFarmRelay(config) shouldBe true
        config.set("mine-zones.quarry.world", "world")
        isFarmRelay(config) shouldBe false
        config.set("mine-zones.quarry", null)
        config.set("network.enabled", false)
        isFarmRelay(config) shouldBe false
    }

    "shift completion preserves per-player identity and team objectives across retries" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ShiftQuestBridge")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            data class Call(val source: String, val eventId: String, val player: UUID, val objective: String, val amount: Long)
            val calls = mutableListOf<Call>()
            val api = RankQuestApi { source, id, player, objective, amount ->
                calls += Call(source, id, player, objective, amount)
                CompletableFuture.completedFuture(ExternalProgressResult.APPLIED)
            }
            try {
                val integration = ServerQuestIntegration(plugin, api, tasks)
                val first = UUID.randomUUID()
                val second = UUID.randomUUID()
                val team = ShiftQuestFixture("spawn:farm:field:17", "farm", setOf(first, second))
                integration.recordShift(team)
                integration.recordShift(team)
                calls.size shouldBe 4
                calls.take(2) shouldBe calls.drop(2)
                calls.map { it.eventId }.distinct().size shouldBe 2
                calls.all { it.source == "arcfarms_farm" && it.objective == "farm.job:team" && it.amount == 1L } shouldBe true
                integration.recordShift(ShiftQuestFixture("spawn:mine:quarry:18", "mine", setOf(first)))
                calls.last().objective shouldBe "mine.job"
                integration.recordShift(ShiftQuestFixture("spawn:lumber:forest:19", "lumber", setOf(first)))
                calls.last().objective shouldBe "lumber.job"
                val count = calls.size
                integration.recordShift(ShiftQuestFixture("spawn:farm:field:20", "farm", emptySet()))
                calls.size shouldBe count
            } finally {
                tasks.close()
            }
        }
    }
})

/** Public getter shape of the provider event; no provider dependency is bundled into ArcRanks. */
class ShiftQuestFixture(val eventId: String, val kind: String, val contributors: Set<UUID>) : Event() {
    override fun getHandlers(): HandlerList = HandlerList()
}
