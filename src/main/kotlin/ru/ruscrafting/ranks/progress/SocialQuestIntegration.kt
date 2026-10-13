package ru.ruscrafting.ranks.progress

import com.google.gson.Gson
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.redis.PaperRedisClient
import ru.arc.redis.network.RedisRequestReplyChannel
import ru.arc.redis.network.RedisRequestResult
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore

/**
 * Paper-side read-only social status client. ARC remains the owner of the
 * existing Redis connection; a primary-client lease borrows it without a
 * second config, credential, or reflective access to ARC internals.
 */
class SocialQuestIntegration(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
) : AutoCloseable {
    private val concurrency = Semaphore(MAX_CONCURRENT)
    private val lifecycleToken = tasks.token()
    private var client: RedisRequestReplyChannel<SocialIdentityStatusWire>? = null
    private var redisClient: PaperRedisClient? = null
    @Volatile private var closed = false

    /** Returns linked=true/unlinked=false; an absent key means authoritative unknown. */
    fun status(playerId: UUID): CompletableFuture<Map<String, Boolean>> {
        if (closed || !tasks.isCurrent(lifecycleToken) || !Bukkit.isPrimaryThread()) return unknown()
        val player = Bukkit.getPlayer(playerId) ?: return unknown()
        if (!player.isOnline || !concurrency.tryAcquire()) return unknown()
        val activeClient = client ?: openClient()?.also { client = it } ?: run {
            concurrency.release()
            return unknown()
        }
        val request = SocialIdentityStatusWire.request("arcranks:${UUID.randomUUID()}", playerId)
        return activeClient.request(request).handle { result, failure ->
            try {
                if (failure != null || result !is RedisRequestResult.Reply) return@handle emptyMap()
                val reply = result.message
                if (!reply.isReply() || reply.playerId != playerId.toString()) return@handle emptyMap()
                buildMap {
                    reply.discord?.toBooleanOrNull()?.let { put("discord", it) }
                    reply.telegram?.toBooleanOrNull()?.let { put("telegram", it) }
                }
            } finally {
                concurrency.release()
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        client?.close()
        client = null
        redisClient?.close()
        redisClient = null
    }

    private fun openClient(): RedisRequestReplyChannel<SocialIdentityStatusWire>? {
        val redis = runCatching { PaperRedisClient.openPrimary(plugin) }.getOrElse { failure ->
            plugin.logger.fine("ARC social status Redis service is unavailable: ${failure.javaClass.simpleName}")
            return null
        }
        return try {
            RedisRequestReplyChannel(
                redis = redis,
                channel = SocialIdentityStatusWire.CHANNEL,
                codec = SocialIdentityStatusWire.codec(Gson()),
                originAllowed = { it.equals(PROXY_ORIGIN, ignoreCase = true) },
                requestId = SocialIdentityStatusWire::requestId,
                replyTo = SocialIdentityStatusWire::replyTo,
                replyAllowed = { request, reply, origin ->
                    origin.equals(PROXY_ORIGIN, ignoreCase = true) &&
                        reply.isReply() &&
                        reply.replyTo == request.requestId &&
                        reply.requestId == request.requestId &&
                        reply.playerId == request.playerId
                },
                timeoutMillis = REQUEST_TIMEOUT_MS,
                maxPending = MAX_CONCURRENT,
                onMessage = { _, _ -> },
            ).also {
                redis.init()
                redisClient = redis
            }
        } catch (failure: Throwable) {
            runCatching { redis.close() }
            plugin.logger.fine("ARC social status Redis client is unavailable: ${failure.javaClass.simpleName}")
            null
        }
    }

    private fun unknown(): CompletableFuture<Map<String, Boolean>> =
        CompletableFuture.completedFuture(emptyMap())

    private fun String.toBooleanOrNull(): Boolean? = when (this) {
        SocialIdentityStatusWire.LINKED -> true
        SocialIdentityStatusWire.UNLINKED -> false
        SocialIdentityStatusWire.UNAVAILABLE -> null
        else -> null
    }

    private companion object {
        const val PROXY_ORIGIN = "proxy"
        const val REQUEST_TIMEOUT_MS = 2_000L
        const val MAX_CONCURRENT = 4
    }
}
