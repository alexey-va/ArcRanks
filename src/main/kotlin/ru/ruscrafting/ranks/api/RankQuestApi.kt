package ru.ruscrafting.ranks.api

import ru.ruscrafting.ranks.storage.ExternalProgressResult
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Successful gameplay operations only. Source/event identity must survive retries across backends. */
fun interface RankQuestApi {
    fun record(source: String, eventId: String, playerId: UUID, objective: String, amount: Long): CompletableFuture<ExternalProgressResult>
}
