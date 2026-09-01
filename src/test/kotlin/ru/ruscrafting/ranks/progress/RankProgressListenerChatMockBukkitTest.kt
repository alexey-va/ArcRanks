package ru.ruscrafting.ranks.progress

import io.kotest.core.spec.style.StringSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.chat.SignedMessage
import net.kyori.adventure.text.Component
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.perk.PerkProgressModifier
import java.nio.file.Files

class RankProgressListenerChatMockBukkitTest : StringSpec({
    "delivered meaningful chat is credited on the server thread" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcRanksChatProgressTest")
            val player = paper.addPlayer("CommunityPlayer")
            val settings = ArcRanksSettings.load(Files.createTempDirectory("arcranks-chat-settings")) { "test-secret" }
            val modifier = mockk<PerkProgressModifier>()
            every { modifier.recordCounter(player.uniqueId, ProgressMetric.COMMUNITY_MINUTES, 1) } returns true
            val listener = RankProgressListener(
                mockk(relaxed = true),
                MovementAccumulator { MovementTuning(16.0, true) },
                modifier,
                { settings },
                LifecycleTaskScope(BukkitTaskScheduler(plugin)),
                CommunityChatProgressGate(),
            )

            listener.onChat(chatEvent(player, "Всем привет!"))
            listener.onChat(chatEvent(player, "ок"))
            paper.performTicks(1)

            verify(exactly = 1) {
                modifier.recordCounter(player.uniqueId, ProgressMetric.COMMUNITY_MINUTES, 1)
            }
        }
    }
})

private fun chatEvent(
    player: org.bukkit.entity.Player,
    text: String,
): AsyncChatEvent {
    val message = Component.text(text)
    return AsyncChatEvent(
        true,
        player,
        mutableSetOf<Audience>(player),
        ChatRenderer.defaultRenderer(),
        message,
        message,
        SignedMessage.system(text, message),
    )
}
