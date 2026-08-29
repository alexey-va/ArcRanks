package ru.ruscrafting.ranks.api

import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.storage.ExternalProgressEvent
import ru.ruscrafting.ranks.storage.ExternalProgressResult
import ru.ruscrafting.ranks.storage.ProgressRepository
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Stable integration seam for Jobs, quests, events, and future seasonal systems. */
interface RankProgressApi {
    fun record(
        source: String,
        eventId: String,
        playerId: UUID,
        metric: ProgressMetric,
        delta: Long,
    ): CompletableFuture<ExternalProgressResult>
}

class RepositoryRankProgressApi(private val repository: ProgressRepository) : RankProgressApi {
    override fun record(
        source: String,
        eventId: String,
        playerId: UUID,
        metric: ProgressMetric,
        delta: Long,
    ): CompletableFuture<ExternalProgressResult> = repository.recordExternalEvent(
        ExternalProgressEvent(
            playerId = playerId,
            source = source,
            eventId = eventId,
            metric = metric,
            delta = delta,
        ),
    )
}
