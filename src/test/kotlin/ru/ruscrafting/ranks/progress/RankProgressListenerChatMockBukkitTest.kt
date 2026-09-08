package ru.ruscrafting.ranks.progress

import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.StringSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.chat.SignedMessage
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.block.data.Ageable
import org.bukkit.event.block.BlockBreakEvent
import ru.arc.config.Config
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.perk.FractionalProgressBonus
import ru.ruscrafting.ranks.perk.PerkCatalogLoader
import ru.ruscrafting.ranks.perk.PerkProgressModifier
import ru.ruscrafting.ranks.quest.QuestProgressDiagnostics
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

    "immature crop records a reason, while mature crop keeps the configured amount" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcRanksCropDiagnosticsTest")
            val player = paper.addPlayer("CropPlayer")
            val root = Files.createTempDirectory("arcranks-crop-settings")
            val settings = ArcRanksSettings.load(root) { "unused-test-password" }
            val writes = mutableListOf<Long>()
            val buffer = ru.ruscrafting.ranks.progress.ProgressBuffer(64) { _, mutations ->
                writes += mutations.single().let { mutation ->
                    (mutation as ru.ruscrafting.ranks.progress.ProgressMutation.Add).delta
                }
                java.util.concurrent.CompletableFuture.completedFuture(Unit)
            }
            val catalog = PerkCatalogLoader(Config(root, "perks.yml")).load()
            val modifier = PerkProgressModifier(buffer, catalog, FractionalProgressBonus()) { emptyList() }
            val diagnostics = QuestProgressDiagnostics()
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val listener = RankProgressListener(
                buffer,
                MovementAccumulator { MovementTuning(16.0, true) },
                modifier,
                { settings },
                tasks,
                diagnostics = diagnostics,
            )
            try {
                val block = player.location.block
                block.type = Material.WHEAT
                val ageable = block.blockData as Ageable
                ageable.age = 0
                block.blockData = ageable
                listener.onBlockBreak(BlockBreakEvent(block, player))
                diagnostics.latest(player.uniqueId, "harvest:wheat") shouldBe "not_mature"

                ageable.age = ageable.maximumAge
                block.blockData = ageable
                listener.onBlockBreak(BlockBreakEvent(block, player))
                diagnostics.latest(player.uniqueId, "harvest:wheat") shouldBe null
                buffer.flush(player.uniqueId).join()
                writes shouldBe listOf(settings.collection.matureCrop.amount)
            } finally {
                tasks.close()
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
