package ru.ruscrafting.ranks.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Native match producers freeze start/finish membership before persisting a played result. */
@FunctionalInterface
public interface ClanArenaQuestApi {
    CompletableFuture<Integer> record(String matchId, List<Map<UUID, ClanMembership>> participantGroups, Instant completedAt);
}
