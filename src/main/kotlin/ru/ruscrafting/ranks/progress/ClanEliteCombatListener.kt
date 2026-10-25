package ru.ruscrafting.ranks.progress

import com.magmaguy.elitemobs.api.EliteMobDeathEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import ru.ruscrafting.ranks.config.ArcRanksSettings
import java.util.logging.Level
import java.util.logging.Logger

/** Native encounter damage proves participation; proximity alone never earns a clan kill. */
class ClanEliteCombatListener(
    private val clans: ClanQuestIntegration,
    private val settings: () -> ArcRanksSettings,
    private val logger: Logger,
) : Listener {
    private val eliteType = com.magmaguy.elitemobs.mobconstructor.EliteEntity::class.java
    private val reinforcement = eliteType.getMethod("isReinforcementOrMount")
    private val damagers = eliteType.getMethod("getDamagers")
    private val bossType = Class.forName("com.magmaguy.elitemobs.mobconstructor.custombosses.CustomBossEntity",
        false, eliteType.classLoader).getMethod("getCustomBossesConfigFields")
    private val bossKind = bossType.returnType.getMethod("getBossType")

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: EliteMobDeathEvent) {
        runCatching {
            val elite = event.eliteEntity
            if (reinforcement.invoke(elite) == true) return
            val damage = damagers.invoke(elite) as Map<*, *>
            val entity = event.entity
            val eligible = eliteCombatParticipants(damage, entity, { id -> org.bukkit.Bukkit.getPlayer(id) }) {
                settings().collection.allows(it.gameMode.name, it.world.name)
            }
            if (eligible.size < 2) return
            val boss = if (elite.isCustomBossEntity) {
                bossKind.invoke(bossType.invoke(elite)).toString() in setOf("BOSS", "MINIBOSS", "EVENT")
            } else false
            clans.recordGroup("elitemobs_clan_kill", entity.uniqueId.toString(), eligible,
                if (boss) "clan.boss_kill" else "clan.elite_kill")
        }.onFailure { logger.log(Level.WARNING, "Could not capture EliteMobs clan kill entity=${event.entity.uniqueId}", it) }
    }
}

internal fun eliteCombatParticipants(damage: Map<*, *>, entity: org.bukkit.entity.Entity,
                                    current: (java.util.UUID) -> Player?, allows: (Player) -> Boolean): Set<java.util.UUID> =
    damage.entries.mapNotNull { (raw, value) ->
        val id = (raw as? Player)?.uniqueId ?: return@mapNotNull null
        val player = current(id) ?: return@mapNotNull null
        val amount = (value as? Number)?.toDouble() ?: return@mapNotNull null
        player.takeIf {
            amount.isFinite() && amount > 0 && it.isOnline && it.world == entity.world &&
                it.location.distanceSquared(entity.location) <= 64.0 * 64.0 && allows(it)
        }?.uniqueId
    }.toSet()
