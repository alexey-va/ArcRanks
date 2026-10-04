package ru.ruscrafting.ranks.perk

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.entity.Tameable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemDamageEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import ru.arc.core.LifecycleTaskScope
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.text.RankLocale
import java.util.UUID

/** Small conveniences on ordinary player actions; never creates blocks or items. */
class UtilityPerkListener(
    private val settings: () -> ArcRanksSettings,
    private val catalog: () -> PerkCatalog,
    private val selected: (UUID) -> Collection<PerkId>,
    private val tasks: LifecycleTaskScope,
    private val generation: () -> Long,
    private val locale: () -> RankLocale,
) : Listener {
    private fun eligible(player: Player): Boolean = settings().let {
        it.features.perks && it.collection.allows(player.gameMode.name, player.world.name)
    }

    private fun has(player: Player, effect: PerkEffectKind): Boolean = eligible(player) &&
        catalog().gameplayEffect(selected(player.uniqueId), effect) > 0

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTrample(event: PlayerInteractEvent) {
        if (event.action == Action.PHYSICAL && event.clickedBlock?.type == Material.FARMLAND &&
            has(event.player, PerkEffectKind.FARMLAND_CARE)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPetDamage(event: EntityDamageByEntityEvent) {
        if (event.isCancelled || event.damage <= 0 ||
            event.cause !in setOf(DamageCause.ENTITY_ATTACK, DamageCause.ENTITY_SWEEP_ATTACK)) return
        val player = event.damager as? Player ?: return
        val pet = event.entity as? Tameable ?: return
        if (!pet.isTamed || pet.ownerUniqueId != player.uniqueId || player.isSneaking ||
            !has(player, PerkEffectKind.TAME_PET_CARE)) return
        event.isCancelled = true
        player.sendActionBar(locale().render("perk-feedback.pet-protected", player))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (event.isCancelled || !event.canBuild() || !has(event.player, PerkEffectKind.BLOCK_STACK_REFILL)) return
        val placed = event.itemInHand.clone()
        if (placed.amount != 1 || !placed.type.isBlock || !placed.isSimilar(ItemStack(placed.type))) return
        val player = event.player
        val slot = when (event.hand) {
            EquipmentSlot.HAND -> player.inventory.heldItemSlot
            EquipmentSlot.OFF_HAND -> 40
            else -> return
        }
        val world = player.world.uid
        val epoch = generation()
        // Paper consumes the placed item after the event returns. Revalidate final cancellation and hand contents.
        tasks.runLater(1) {
            if (event.isCancelled || !event.canBuild() || epoch != generation() || !player.isOnline || player.isDead ||
                player.world.uid != world || !has(player, PerkEffectKind.BLOCK_STACK_REFILL) ||
                (slot != 40 && player.inventory.heldItemSlot != slot)) return@runLater
            val inventory = player.inventory
            val current = inventory.getItem(slot)
            if (current != null && !current.type.isAir && current.amount > 0) return@runLater
            val donor = (0 until 36).firstOrNull {
                it != slot && inventory.getItem(it)?.let { stack -> stack.amount > 0 && stack.isSimilar(placed) } == true
            } ?: return@runLater
            val stack = inventory.getItem(donor)?.clone() ?: return@runLater
            inventory.setItem(donor, null)
            inventory.setItem(slot, stack)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onToolWear(event: PlayerItemDamageEvent) {
        if (event.isCancelled || event.damage <= 0 || !eligible(event.player)) return
        val item = event.item
        if (!isOrdinaryTool(item)) return
        val meta = item.itemMeta as? Damageable ?: return
        val maximum = if (meta.hasMaxDamage()) meta.maxDamage else item.type.maxDurability.toInt()
        if (maximum <= 0) return
        val threshold = maximum - maxOf(1, maximum / 20)
        val afterDamage = meta.damage.toLong() + event.damage
        if (meta.damage < threshold && afterDamage >= threshold && afterDamage < maximum) {
            event.player.sendActionBar(locale().render("perk-feedback.tool-worn", event.player))
        }
    }

    private fun isOrdinaryTool(item: ItemStack): Boolean {
        val type = item.type
        if (!(type.name.endsWith("_PICKAXE") || type.name.endsWith("_AXE") || type.name.endsWith("_SHOVEL") ||
                type.name.endsWith("_HOE") || type == Material.FISHING_ROD || type == Material.SHEARS)) return false
        return item.itemMeta?.let { it.persistentDataContainer.isEmpty && !it.hasCustomModelData() && !it.hasItemModel() } ?: true
    }
}
