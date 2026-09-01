package ru.ruscrafting.ranks.progress

import ru.ruscrafting.ranks.config.CommunityChatSourceSettings
import java.time.Instant
import java.util.Locale
import java.util.UUID

class CommunityChatProgressGate {
    private data class PlayerState(
        var lastCredit: Instant? = null,
        val creditedMessages: LinkedHashMap<String, Instant> = linkedMapOf(),
    )

    private val lock = Any()
    private val states = mutableMapOf<UUID, PlayerState>()
    private var nextCleanup = Instant.MIN

    fun credit(
        playerId: UUID,
        message: String,
        now: Instant,
        settings: CommunityChatSourceSettings,
    ): Long {
        if (!settings.enabled) return 0
        val normalized = normalize(message)
        if (normalized.count(Char::isLetterOrDigit) < settings.minimumLettersOrDigits) return 0

        return synchronized(lock) {
            if (!now.isBefore(nextCleanup)) {
                val expiredBefore = now.minusSeconds(settings.duplicateWindowSeconds)
                states.entries.removeIf { (_, state) -> state.lastCredit?.isBefore(expiredBefore) != false }
                nextCleanup = now.plusSeconds(settings.duplicateWindowSeconds.coerceAtMost(MAX_CLEANUP_INTERVAL_SECONDS))
            }
            val state = states.getOrPut(playerId, ::PlayerState)
            val duplicateThreshold = now.minusSeconds(settings.duplicateWindowSeconds)
            state.creditedMessages.entries.removeIf { (_, creditedAt) -> creditedAt.isBefore(duplicateThreshold) }
            val lastCredit = state.lastCredit
            if (lastCredit != null && now.isBefore(lastCredit.plusSeconds(settings.cooldownSeconds))) {
                return@synchronized 0
            }
            if (normalized in state.creditedMessages) return@synchronized 0

            if (state.creditedMessages.size >= MAX_RECENT_MESSAGES) {
                state.creditedMessages.entries.iterator().run {
                    if (hasNext()) {
                        next()
                        remove()
                    }
                }
            }
            state.lastCredit = now
            state.creditedMessages[normalized] = now
            settings.amount
        }
    }

    private fun normalize(message: String): String = message
        .trim()
        .lowercase(Locale.ROOT)
        .replace(WHITESPACE, " ")

    private companion object {
        const val MAX_RECENT_MESSAGES = 128
        const val MAX_CLEANUP_INTERVAL_SECONDS = 600L
        val WHITESPACE = Regex("\\s+")
    }
}
