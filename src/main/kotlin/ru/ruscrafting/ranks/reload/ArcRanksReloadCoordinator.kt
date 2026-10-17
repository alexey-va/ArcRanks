package ru.ruscrafting.ranks.reload

import ru.ruscrafting.ranks.config.ArcRanksConfigDiff
import ru.ruscrafting.ranks.config.ArcRanksConfigDiffer
import ru.ruscrafting.ranks.config.ArcRanksConfigLoader
import ru.ruscrafting.ranks.config.ArcRanksConfigSnapshot
import ru.ruscrafting.ranks.config.ArcRanksConfigStore
import ru.ruscrafting.ranks.config.ArcRanksLiveArea
import java.util.concurrent.atomic.AtomicBoolean

sealed interface ArcRanksReloadResult {
    data class Applied(
        val generation: Long,
        val liveAreas: Set<ArcRanksLiveArea>,
        val warnings: List<String> = emptyList(),
    ) : ArcRanksReloadResult

    data class NoChanges(val generation: Long) : ArcRanksReloadResult

    data class RestartRequired(val paths: Set<String>) : ArcRanksReloadResult

    data class Invalid(val reason: String) : ArcRanksReloadResult

    data class RolledBack(val reason: String, val rollbackFailure: String? = null) : ArcRanksReloadResult

    data object Busy : ArcRanksReloadResult
}

/**
 * Parses first, restarts only recurring work, and publishes the candidate last.
 * The active generation therefore survives validation and scheduling failures intact.
 */
class ArcRanksReloadCoordinator(
    private val loader: ArcRanksConfigLoader,
    private val store: ArcRanksConfigStore,
    private val restartRecurringTasks: () -> Unit,
    private val installRecurringTasks: (ArcRanksConfigSnapshot) -> Unit,
    private val afterCommit: (ArcRanksConfigSnapshot, ArcRanksConfigDiff) -> List<String> = { _, _ -> emptyList() },
) {
    private val inProgress = AtomicBoolean(false)

    class Prepared internal constructor(
        val current: ru.ruscrafting.ranks.config.ArcRanksConfigSnapshot?,
        val candidate: ru.ruscrafting.ranks.config.ArcRanksConfigSnapshot?,
        val diff: ArcRanksConfigDiff?,
        val result: ArcRanksReloadResult?,
    )

    /** Worker-only preparation: parsing and the complete content diff may be large. */
    fun prepare(): Prepared {
        if (!inProgress.compareAndSet(false, true)) return Prepared(null, null, null, ArcRanksReloadResult.Busy)
        val current = store.current()
        val candidate = try { loader.load(nextGeneration(current.generation)) } catch (failure: Throwable) {
            inProgress.set(false)
            return Prepared(null, null, null, ArcRanksReloadResult.Invalid(failure.safeMessage()))
        }
        val diff = ArcRanksConfigDiffer.diff(current, candidate)
        return Prepared(current, candidate, diff, null)
    }

    /** Owning-thread publication: restart tasks and atomically publish an already prepared snapshot. */
    fun apply(prepared: Prepared): ArcRanksReloadResult {
        prepared.result?.let { return it }
        return try { applyCandidate(prepared) } finally { inProgress.set(false) }
    }

    fun abandon(prepared: Prepared) {
        if (prepared.result == null) inProgress.set(false)
    }

    /** Synchronous headless/test entry point; production explicitly separates the two stages. */
    fun reload(): ArcRanksReloadResult = apply(prepare())

    private fun applyCandidate(prepared: Prepared): ArcRanksReloadResult {
        val current = requireNotNull(prepared.current)
        val candidate = requireNotNull(prepared.candidate)
        val diff = requireNotNull(prepared.diff)
        if (store.current() !== current) return ArcRanksReloadResult.RolledBack("active configuration changed during preparation")
        if (diff.noChanges) return ArcRanksReloadResult.NoChanges(current.generation)
        if (diff.restartRequired.isNotEmpty()) return ArcRanksReloadResult.RestartRequired(diff.restartRequired)

        try {
            restartRecurringTasks()
            installRecurringTasks(candidate)
        } catch (candidateFailure: Throwable) {
            val rollbackFailure = runCatching {
                restartRecurringTasks()
                installRecurringTasks(current)
            }.exceptionOrNull()
            return ArcRanksReloadResult.RolledBack(candidateFailure.safeMessage(), rollbackFailure?.safeMessage())
        }

        if (!store.compareAndSet(current, candidate)) {
            val rollbackFailure = runCatching {
                restartRecurringTasks()
                installRecurringTasks(current)
            }.exceptionOrNull()
            return ArcRanksReloadResult.RolledBack("active configuration changed during reload", rollbackFailure?.safeMessage())
        }
        val warnings = runCatching { afterCommit(candidate, diff) }
            .getOrElse { listOf(it.safeMessage()) }
        return ArcRanksReloadResult.Applied(candidate.generation, diff.liveAreas, warnings)
    }

    private fun nextGeneration(current: Long): Long = if (current == Long.MAX_VALUE) 1L else current + 1L
}

private fun Throwable.safeMessage(): String =
    (message ?: javaClass.simpleName).replace('\n', ' ').replace('\r', ' ').take(MAX_FAILURE_LENGTH)

private const val MAX_FAILURE_LENGTH = 240
