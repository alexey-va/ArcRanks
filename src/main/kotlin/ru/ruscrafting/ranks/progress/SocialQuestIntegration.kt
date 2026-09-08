package ru.ruscrafting.ranks.progress

import com.google.gson.Gson
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisOperations
import ru.arc.redis.network.RedisRequestReplyChannel
import ru.arc.redis.network.RedisRequestResult
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore

private const val ARC_PLUGIN_NAME = "ARC"
private const val ARC_CLASS = "ru.arc.ARC"
private const val CHANNEL_LISTENER_CLASS = "ru.arc.redis.ChannelListener"
private const val REDIS_FIELD = "redisManager"

/**
 * Paper-side read-only social status client. ARC remains the owner of the
 * existing Redis connection; this adapter borrows it through ARC's public
 * [ARC.redisManager] field and never creates another config or credential.
 */
class SocialQuestIntegration(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
) : AutoCloseable {
    private val concurrency = Semaphore(MAX_CONCURRENT)
    private val lifecycleToken = tasks.token()
    private var client: RedisRequestReplyChannel<SocialIdentityStatusWire>? = null
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
    }

    private fun openClient(): RedisRequestReplyChannel<SocialIdentityStatusWire>? {
        val redis = ArcRedisOperationsAdapter.open(plugin) ?: return null
        return runCatching {
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
            )
        }.getOrElse {
            plugin.logger.fine("ARC social status Redis adapter is unavailable: ${it.javaClass.simpleName}")
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

/** Small reflection shell for ARC's already initialized RedisOperations owner. */
private class ArcRedisOperationsAdapter private constructor(
    private val manager: Any,
    private val listenerType: Class<*>,
) : RedisOperations {
    private val listeners = IdentityHashMap<ChannelListener, Any>()

    override fun publish(channel: String, message: String) {
        manager.call("publish", channel, message)
    }

    override fun saveMap(key: String, map: Map<String, String>) {
        manager.call("saveMap", key, map)
    }

    override fun saveMapEntries(key: String, vararg keyValuePairs: String?): CompletableFuture<*> =
        manager.call("saveMapEntries", key, keyValuePairs)
            as CompletableFuture<*>

    override fun loadMap(key: String): CompletableFuture<Map<String, String>> =
        manager.call("loadMap", key) as CompletableFuture<Map<String, String>>

    override fun loadMapEntries(key: String, vararg mapKeys: String): CompletableFuture<List<String?>> =
        manager.call("loadMapEntries", key, mapKeys)
            as CompletableFuture<List<String?>>

    override fun compareAndSetMapEntry(
        key: String,
        mapKey: String,
        expectedValue: String?,
        replacementValue: String?,
    ): CompletableFuture<Boolean> = manager.call(
        "compareAndSetMapEntry",
        key,
        mapKey,
        expectedValue,
        replacementValue,
    ) as CompletableFuture<Boolean>

    override fun registerChannelUnique(channel: String, listener: ChannelListener) {
        synchronized(listeners) {
            lateinit var proxy: Any
            proxy = Proxy.newProxyInstance(
                requireNotNull(listenerType.classLoader),
                arrayOf(listenerType),
                InvocationHandler { _, method, args ->
                    when (method.name) {
                        "consume" -> if (args != null && args.size == 3) {
                            listener.consume(args[0] as String, args[1] as String, args[2] as String)
                        }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> args?.singleOrNull() === proxy
                        "toString" -> "ArcRedisChannelListener(${System.identityHashCode(proxy)})"
                        else -> null
                    }
                },
            )
            listeners[listener] = proxy
            manager.call("registerChannelUnique", channel, proxy)
        }
    }

    override fun unregisterChannel(channel: String, listener: ChannelListener) {
        synchronized(listeners) {
            listeners.remove(listener)?.let { proxy ->
                manager.call("unregisterChannel", channel, proxy)
            }
        }
    }

    override fun init() {
        manager.call("init")
    }

    override fun close() = Unit

    private fun Any.call(name: String, vararg args: Any?): Any? {
        val method = javaClass.methods.firstOrNull { candidate ->
            candidate.name == name && candidate.parameterCount == args.size &&
                candidate.parameterTypes.indices.all { index ->
                    val value = args[index]
                    value == null || candidate.parameterTypes[index].isAssignableFrom(value.javaClass)
                }
        } ?: error("ARC Redis manager method $name(${args.size}) is unavailable")
        return method.invoke(this, *args)
    }

    companion object {
        fun open(plugin: Plugin): RedisOperations? {
            val arc = plugin.server.pluginManager.getPlugin(ARC_PLUGIN_NAME)?.takeIf(Plugin::isEnabled) ?: return null
            return runCatching {
                val loader = arc.javaClass.classLoader
                val arcClass = Class.forName(ARC_CLASS, false, loader)
                val manager = arcClass.getField(REDIS_FIELD).get(null) ?: return@runCatching null
                if (!manager.javaClass.getMethod("isConnected").invoke(manager).let { it as? Boolean == true }) return@runCatching null
                val listenerType = Class.forName(CHANNEL_LISTENER_CLASS, false, loader)
                ArcRedisOperationsAdapter(manager, listenerType)
            }.getOrNull()
        }
    }
}
