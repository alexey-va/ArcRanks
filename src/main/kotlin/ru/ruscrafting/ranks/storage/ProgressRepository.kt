package ru.ruscrafting.ranks.storage

import ru.ruscrafting.ranks.domain.PlayerProgressProfile
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.progress.ProgressMutation
import java.util.UUID
import java.util.concurrent.CompletableFuture

enum class ExternalProgressResult {
    APPLIED,
    DUPLICATE,
}

data class ExternalProgressEvent(
    val playerId: UUID,
    val source: String,
    val eventId: String,
    val metric: ProgressMetric,
    val delta: Long,
) {
    init {
        require(source.matches(Regex("[a-z0-9_.-]{1,40}"))) { "Unsafe external progress source" }
        require(eventId.matches(Regex("[A-Za-z0-9:_.-]{1,120}"))) { "Unsafe external progress event id" }
        require(delta > 0) { "External progress delta must be positive" }
        require(metric != ProgressMetric.WEALTH_PEAK) { "External progress events support counters, not high-water metrics" }
    }
}

interface ProgressRepository {
    fun initialize(): CompletableFuture<Unit>

    fun load(playerId: UUID): CompletableFuture<PlayerProgressProfile>

    fun applyMutations(playerId: UUID, mutations: List<ProgressMutation>): CompletableFuture<Unit>

    fun selectFocus(playerId: UUID, path: SpecializationPath): CompletableFuture<Unit>

    fun recordExternalEvent(event: ExternalProgressEvent): CompletableFuture<ExternalProgressResult>
}
