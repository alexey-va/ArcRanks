package ru.ruscrafting.ranks.kit

import com.Zrips.CMI.CMI
import org.bukkit.Bukkit
import ru.arc.core.LifecycleTaskScope
import java.util.concurrent.CompletableFuture

fun interface CmiKitDelivery {
    fun deliver(kitId: String, playerName: String): Boolean
}

class CmiWeeklyKitProvider(
    private val tasks: LifecycleTaskScope,
    private val delivery: CmiKitDelivery = CmiKitDelivery(::deliverNative),
) : WeeklyKitProvider {
    override fun deliver(definition: WeeklyKitDefinition, playerName: String): CompletableFuture<Boolean> {
        val completion = CompletableFuture<Boolean>()
        val scheduled = tasks.runSync {
            runCatching { delivery.deliver(definition.kitId, playerName) }
                .fold(completion::complete, completion::completeExceptionally)
        }
        if (scheduled == null) completion.completeExceptionally(IllegalStateException("Plugin lifecycle is inactive"))
        return completion
    }

    companion object {
        private fun deliverNative(kitId: String, playerName: String): Boolean {
            val cmiPlugin = Bukkit.getPluginManager().getPlugin("CMI")
            if (cmiPlugin?.isEnabled != true) return false
            val player = Bukkit.getPlayerExact(playerName)?.takeIf { it.isOnline } ?: return false
            val manager = CMI.getInstance().kitsManager
            val kit = manager.getKit(kitId) ?: return false
            if (!kit.isEnabled || !kit.enoughFreeSpace(player)) return false
            manager.giveKit(player, kit, true)
            return true
        }
    }
}
