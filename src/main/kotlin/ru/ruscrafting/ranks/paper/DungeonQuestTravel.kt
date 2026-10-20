package ru.ruscrafting.ranks.paper

import com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance
import org.bukkit.entity.Player
import ru.arc.dungeons.api.ArcDungeonNavigation
import ru.ruscrafting.ranks.config.DungeonQuestDestination
import ru.ruscrafting.ranks.config.QuestTravelLocation
import ru.ruscrafting.ranks.config.locationsFor

/** Called on Paper's owning thread, using only the current in-memory configuration generation. */
internal fun travelToDungeonEntrance(
    player: Player,
    objective: String,
    destinations: Map<String, DungeonQuestDestination>,
    insideInstance: (Player) -> Boolean = ::insideDungeonInstance,
): Boolean {
    if (objective == "dungeon.complete") return player.performCommand("dungeon tp")
    val id = objective.takeIf { it.startsWith("dungeon.complete:") }
        ?.removePrefix("dungeon.complete:") ?: return false
    val destination = destinations[id] ?: return false
    return travelToDestination(player, destination, insideInstance)
}

internal fun travelToQuestLocation(
    player: Player,
    objective: String,
    locationId: String,
    locations: Map<String, QuestTravelLocation>,
    insideInstance: (Player) -> Boolean = ::insideDungeonInstance,
): Boolean {
    if (locationId !in locations.locationsFor(objective)) return false
    return travelToDestination(player, locations.getValue(locationId).destination, insideInstance)
}

private fun travelToDestination(
    player: Player,
    destination: DungeonQuestDestination,
    insideInstance: (Player) -> Boolean,
): Boolean {
    if (insideInstance(player)) return player.performCommand("dungeon tp")
    val navigation = player.server.servicesManager.load(ArcDungeonNavigation::class.java) ?: return false
    return navigation.teleport(
        player,
        destination.server,
        destination.world,
        destination.x,
        destination.y,
        destination.z,
        destination.yaw,
        destination.pitch,
    )
}

/** Match ArcDungeons' world boundary, including spectators and players outside its roster. */
private fun insideDungeonInstance(player: Player): Boolean =
    player.server.pluginManager.isPluginEnabled("EliteMobs") &&
        DungeonInstance.getDungeonInstances().any { it.world == player.world }
