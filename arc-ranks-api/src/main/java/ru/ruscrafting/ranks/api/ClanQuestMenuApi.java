package ru.ruscrafting.ranks.api;

import java.util.UUID;

/** Opens the existing native quest flow on the gameplay thread, retaining shared menu history. */
@FunctionalInterface
public interface ClanQuestMenuApi {
    boolean open(UUID playerId);
}
