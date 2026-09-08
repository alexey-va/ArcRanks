package ru.ruscrafting.ranks.progress

import com.google.gson.Gson
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import java.util.UUID

/** Wire-compatible copy of ProxyARC's closed social status request/reply DTO. */
internal data class SocialIdentityStatusWire(
    val kind: String,
    val requestId: String,
    val replyTo: String? = null,
    val playerId: String,
    val discord: String? = null,
    val telegram: String? = null,
) {
    fun isReply(): Boolean = replyTo != null && discord in STATES && telegram in STATES

    fun validate() {
        require(kind == KIND)
        require(REQUEST_ID.matches(requestId))
        require(runCatching { UUID.fromString(playerId) }.isSuccess)
        replyTo?.let { require(REQUEST_ID.matches(it)) }
        require(replyTo != null && isReply() || replyTo == null && discord == null && telegram == null)
    }

    companion object {
        const val CHANNEL = "arc.social_identity_status"
        const val KIND = "status"
        const val LINKED = "linked"
        const val UNLINKED = "unlinked"
        const val UNAVAILABLE = "unavailable"
        private val STATES = setOf(LINKED, UNLINKED, UNAVAILABLE)
        private val REQUEST_ID = Regex("[A-Za-z0-9:._-]{1,160}")
        private val CONTRACT = JsonObjectContract(
            allowedFields = setOf("kind", "requestId", "replyTo", "playerId", "discord", "telegram"),
            requiredFields = setOf("kind", "requestId", "playerId"),
        )
        private val BOUNDS = JsonResourceBounds(512, 4, 8, 16, 160)

        fun codec(gson: Gson = Gson()): BoundedJsonCodec<SocialIdentityStatusWire> =
            BoundedJsonCodec(gson, SocialIdentityStatusWire::class.java, CONTRACT, BOUNDS) { it.validate() }

        fun request(requestId: String, playerId: UUID): SocialIdentityStatusWire =
            SocialIdentityStatusWire(KIND, requestId, playerId = playerId.toString())
    }
}
