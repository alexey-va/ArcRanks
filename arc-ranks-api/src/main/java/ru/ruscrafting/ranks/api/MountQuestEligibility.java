package ru.ruscrafting.ranks.api;

import java.util.Optional;
import java.util.UUID;

/** Cache-only ownership read on Paper's owning thread, registered by ArcMounts. */
@FunctionalInterface
public interface MountQuestEligibility {
    /** Empty means unavailable; only a confirmed true admits a new riding quest. */
    Optional<Boolean> hasOwnedMount(UUID playerId);
}
