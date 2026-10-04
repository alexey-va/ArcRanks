package ru.ruscrafting.ranks.perk

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import com.sk89q.worldguard.protection.flags.Flags as WorldGuardFlags
import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.flags.type.Flags as LandsFlags
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.logging.Level

/** Queries the installed protection owners without manufacturing rewarded placement events. */
class PerkPlantingAccess(private val plugin: Plugin) {
    private var failed = false
    private val checks: List<(Player, Block, Material) -> Boolean> = buildList {
        for (name in listOf("Lands", "WorldGuard")) {
            if (plugin.server.pluginManager.getPlugin(name) == null) continue
            try {
                check(plugin.server.pluginManager.isPluginEnabled(name)) { "$name is installed but disabled" }
                add(when (name) {
                    "Lands" -> LandsPlantingAccess(plugin)::allows
                    else -> WorldGuardPlantingAccess()::allows
                })
            } catch (failure: Exception) {
                unavailable(name, failure)
            } catch (failure: LinkageError) {
                unavailable(name, failure)
            }
        }
    }

    fun allows(player: Player, block: Block, crop: Material): Boolean {
        if (failed) return false
        return try {
            checks.all { it(player, block, crop) }
        } catch (failure: Exception) {
            unavailable("permission query", failure)
            false
        } catch (failure: LinkageError) {
            unavailable("permission query", failure)
            false
        }
    }

    private fun unavailable(provider: String, failure: Throwable) {
        failed = true
        plugin.logger.log(Level.WARNING, "ArcRanks crop replant unavailable: protection provider=$provider; repair the provider and restart ArcRanks", failure)
    }
}

private class LandsPlantingAccess(plugin: Plugin) {
    private val lands = LandsIntegration.of(plugin)

    fun allows(player: Player, block: Block, crop: Material): Boolean {
        val world = lands.getWorld(block.world) ?: return true
        if (world.getArea(block.location) == null) return true
        val owner = lands.getLandPlayer(player.uniqueId) ?: return false
        return world.hasRoleFlag(owner, block.location, LandsFlags.BLOCK_PLACE, crop, false)
    }
}

private class WorldGuardPlantingAccess {
    private val guard = WorldGuard.getInstance()
    private val query = guard.platform.regionContainer.createQuery()

    fun allows(player: Player, block: Block, @Suppress("UNUSED_PARAMETER") crop: Material): Boolean {
        val local = WorldGuardPlugin.inst().wrapPlayer(player)
        return guard.platform.sessionManager.hasBypass(local, BukkitAdapter.adapt(block.world)) ||
            query.testBuild(BukkitAdapter.adapt(block.location), local, WorldGuardFlags.BLOCK_PLACE)
    }
}
