package ru.ruscrafting.ranks.api;

import java.util.UUID;

/** Resolve through Bukkit ServicesManager on the gameplay thread; null means no known clan. */
@FunctionalInterface
public interface ClanMembershipProvider {
    ClanMembership find(UUID playerId);
}
