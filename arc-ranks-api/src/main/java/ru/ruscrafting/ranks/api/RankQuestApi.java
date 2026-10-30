package ru.ruscrafting.ranks.api;

import ru.ruscrafting.ranks.storage.ExternalProgressResult;
import java.util.List;
import java.util.Objects;
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

    /**
     * Reads configured daily item-reward candidates without assigning a board or consuming randomness.
     * A candidate is not a promise that any particular player receives that assignment: rank,
     * availability, history and the ordinary daily selection rules still apply.
     * Titles are plain text in the provider's configured default locale.
     */
    default ItemRewardSourceSnapshot itemRewardSources() {
        return new ItemRewardSourceSnapshot(false, List.of());
    }

    /** False means this provider does not expose a current loaded source catalog. */
    record ItemRewardSourceSnapshot(boolean available, List<ItemRewardSource> sources) {
        public ItemRewardSourceSnapshot {
            sources = List.copyOf(sources);
            if (!available && !sources.isEmpty()) {
                throw new IllegalArgumentException("Unavailable item reward sources must be empty");
            }
        }
    }

    record ItemRewardSource(String questId, String title, String presetId, int amount) {
        public ItemRewardSource {
            Objects.requireNonNull(questId, "questId");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(presetId, "presetId");
            if (questId.isBlank() || title.isBlank() || presetId.isBlank() || amount < 1 || amount > 64) {
                throw new IllegalArgumentException("Invalid item reward source");
            }
        }
    }
}
