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
    private val worker: ru.arc.core.BoundedAsyncExecutor,
    private val registerWorkerCleanup: (AutoCloseable) -> Unit,
) : AutoCloseable {
    private val concurrency = Semaphore(MAX_CONCURRENT)
    private val lifecycleToken = tasks.token()
    @Volatile private var client: RedisRequestReplyChannel<SocialIdentityStatusWire>? = null
    private var opening: CompletableFuture<RedisRequestReplyChannel<SocialIdentityStatusWire>>? = null
    private val resourceLock = Any()
    private var redisClient: PaperRedisClient? = null
    @Volatile private var closed = false

    /** Returns linked=true/unlinked=false; an absent key means authoritative unknown. */
    fun status(playerId: UUID): CompletableFuture<Map<String, Boolean>> {
        if (closed || !tasks.isCurrent(lifecycleToken) || !Bukkit.isPrimaryThread()) return unknown()
        val player = Bukkit.getPlayer(playerId) ?: return unknown()
        if (!player.isOnline || !concurrency.tryAcquire()) return unknown()
        val activeClient = client?.let { CompletableFuture.completedFuture(it) } ?: opening ?: run {
            val service = plugin.server.servicesManager.load(ru.arc.paper.api.ArcRedisService::class.java)
            if (service == null) { concurrency.release(); return unknown() }
            val owner = plugin.name
            worker.submit { openClient(service, owner) }.also { future ->
                opening = future
                future.whenComplete { _, failure ->
                    if (failure != null) tasks.runSync(lifecycleToken) { if (opening === future) opening = null }
                }
            }
        }
        val request = SocialIdentityStatusWire.request("arcranks:${UUID.randomUUID()}", playerId)
        return activeClient.thenCompose { channel ->
            if (closed || !tasks.isCurrent(lifecycleToken)) CompletableFuture.completedFuture(null) else channel.request(request)
        }.handle { result, failure ->
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

    override fun close() {
        synchronized(resourceLock) {
            if (closed) return
            closed = true
            client = null
            redisClient = null
        }
        // The composition root owns channel/lease disposal in its reserved
        // terminal worker slot; full ordinary admission cannot lose cleanup.
    }

    /** Worker-only socket/channel acquisition; native service/owner were captured by status. */
    private fun openClient(service: ru.arc.paper.api.ArcRedisService, owner: String): RedisRequestReplyChannel<SocialIdentityStatusWire> {
        val redis = PaperRedisClient.openPrimary(service, owner)
        try {
            redis.retainDuringShutdown()
            val channel = RedisRequestReplyChannel(
                redis = redis,
                channel = SocialIdentityStatusWire.CHANNEL,
                codec = SocialIdentityStatusWire.codec(Gson()),
                originAllowed = { it.equals(PROXY_ORIGIN, ignoreCase = true) },
                requestId = SocialIdentityStatusWire::requestId,
                replyTo = SocialIdentityStatusWire::replyTo,
                replyAllowed = { request, reply, origin ->
                    origin.equals(PROXY_ORIGIN, ignoreCase = true) && reply.isReply() &&
                        reply.replyTo == request.requestId && reply.requestId == request.requestId && reply.playerId == request.playerId
                },
                timeoutMillis = REQUEST_TIMEOUT_MS,
                maxPending = MAX_CONCURRENT,
                onMessage = { _, _ -> },
            )
            redis.init()
            val accepted = synchronized(resourceLock) {
                if (closed) false else { client = channel; redisClient = redis; true }
            }
            if (!accepted) {
                channel.close()
                throw java.util.concurrent.CancellationException("Social status lifecycle closed")
            }
            registerWorkerCleanup(AutoCloseable { try { channel.close() } finally { redis.close() } })
            return channel
        } catch (failure: Throwable) {
            runCatching { redis.close() }
            throw failure
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
