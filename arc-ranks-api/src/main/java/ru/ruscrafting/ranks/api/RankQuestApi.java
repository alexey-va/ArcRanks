package ru.ruscrafting.ranks.api;

import ru.ruscrafting.ranks.storage.ExternalProgressResult;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Records confirmed gameplay success asynchronously in ArcRanks' existing durable ledger.
 * Source and event ID identify one observation across players, objectives, days and retries.
 * Implementations must not perform blocking persistence on the calling gameplay thread.
 * Resolve through Bukkit ServicesManager; ArcRanks owns these API classes at runtime.
 */
@FunctionalInterface
public interface RankQuestApi {
    CompletableFuture<ExternalProgressResult> record(
            String source, String eventId, UUID playerId, String objective, long amount);
}
