package ru.ruscrafting.ranks.api;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable, event-time clan membership. Clan identity survives renames and owner changes. */
public record ClanMembership(String clanId, String name, Set<UUID> memberIds) {
    public ClanMembership {
        Objects.requireNonNull(clanId);
        Objects.requireNonNull(name);
        memberIds = Set.copyOf(memberIds);
        if (!clanId.matches("[a-z0-9_.:-]{1,64}") || memberIds.isEmpty() || memberIds.size() > 1000) {
            throw new IllegalArgumentException("Invalid clan membership snapshot");
        }
    }
}
