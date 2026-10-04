package ru.ruscrafting.ranks.perk

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.block.data.Ageable
import org.bukkit.entity.Enemy
import org.bukkit.entity.ExperienceOrb
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityAirChangeEvent
import org.bukkit.event.entity.EntityExhaustionEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.player.PlayerExpChangeEvent
import org.bukkit.event.player.PlayerItemDamageEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.view.AnvilView
import ru.ruscrafting.ranks.config.ArcRanksSettings
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/** Native event adjustments only: never repeat a quest, job payout, or item-spawn event. */
class GameplayPerkListener(
    private val settings: () -> ArcRanksSettings,
    private val catalog: () -> PerkCatalog,
    private val selected: (UUID) -> Collection<PerkId>,
    private val roll: () -> Int = { ThreadLocalRandom.current().nextInt(10_000) },
) : Listener {
    private data class AnvilInputs(
        val first: ItemStack?,
        val second: ItemStack?,
        val renameText: String?,
    ) {
        fun sameAs(other: AnvilInputs): Boolean =
            sameStack(first, other.first) && sameStack(second, other.second) && renameText == other.renameText

        private fun sameStack(left: ItemStack?, right: ItemStack?): Boolean = when {
            left == null -> right == null
            right == null -> false
            else -> left.amount == right.amount && left.isSimilar(right)
        }
    }

    private data class AnvilCost(
        val view: AnvilView,
        val inputs: AnvilInputs,
        val original: Int,
        val reduced: Int,
    )

    private val anvilCosts = mutableMapOf<UUID, AnvilCost>()

    private fun ItemStack.hasPerkCustomData(): Boolean = itemMeta?.let {
        !it.persistentDataContainer.isEmpty || it.hasCustomModelData() || it.hasItemModel()
    } ?: false

    private fun Material.isPerkArmor(): Boolean = name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") ||
        name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")

    private fun ItemStack?.anvilSnapshot(): ItemStack? = this
        ?.takeIf { !it.type.isAir && it.amount > 0 }
        ?.clone()

    private fun eligible(player: Player): Boolean = settings().let {
        it.features.perks && it.collection.allows(player.gameMode.name, player.world.name)
    }

    private fun effect(player: Player, kind: PerkEffectKind): Int =
        if (eligible(player)) catalog().gameplayEffect(selected(player.uniqueId), kind) else 0

    private fun companionNearby(player: Player): Boolean = player.world.getNearbyPlayers(player.location, 16.0)
        .any { it.uniqueId != player.uniqueId && it.isOnline && !it.isDead && eligible(it) && player.canSee(it) }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCropDrop(event: BlockDropItemEvent) {
        if (event.isCancelled) return
        val collection = settings().collection
        val state = event.blockState
        if (!collection.matureCrop.enabled || state.type.name !in collection.matureCropMaterials) return
        val crop = when (state.type) {
            Material.WHEAT -> Material.WHEAT
            Material.CARROTS -> Material.CARROT
            Material.POTATOES -> Material.POTATO
            Material.BEETROOTS -> Material.BEETROOT
            else -> return
        }
        val age = state.blockData as? Ageable ?: return
        if (age.age != age.maximumAge) return
        val chance = effect(event.player, PerkEffectKind.HARVEST_BONUS)
        if (chance == 0) return
        // Edit one existing vanilla drop. Later cancellation still suppresses the entire yield.
        val vanillaCrop = ItemStack(crop)
        val drop = event.items.firstOrNull {
            val stack = it.itemStack
            stack.isSimilar(vanillaCrop) && stack.amount in 1 until stack.maxStackSize
        } ?: return
        if (roll() < chance) drop.itemStack = drop.itemStack.clone().also { it.amount += 1 }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onWear(event: PlayerItemDamageEvent) {
        if (event.isCancelled || event.damage <= 0) return
        val item = event.item
        if (item.hasPerkCustomData()) return
        val basisPoints = when {
            item.type == Material.FISHING_ROD -> effect(event.player, PerkEffectKind.FISHING_ROD_PRESERVATION)
            item.type.isPerkArmor() -> {
                val armorBonus = effect(event.player, PerkEffectKind.NEARBY_ARMOR_PRESERVATION)
                if (armorBonus > 0 && companionNearby(event.player)) armorBonus else 0
            }
            item.type.name.endsWith("_HOE") -> effect(event.player, PerkEffectKind.HOE_PRESERVATION)
            item.type.name.endsWith("_PICKAXE") -> effect(event.player, PerkEffectKind.PICKAXE_PRESERVATION)
            item.type.name.endsWith("_AXE") || item.type.name.endsWith("_SHOVEL") ->
                effect(event.player, PerkEffectKind.BUILDING_TOOL_PRESERVATION)
            else -> 0
        }
        if (basisPoints > 0) event.damage -= scaledUnits(event.damage, basisPoints, roll())
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onAirChange(event: EntityAirChangeEvent) {
        if (event.isCancelled || event.amount < 0) return
        val player = event.entity as? Player ?: return
        if (!player.isUnderWater) return
        val airLoss = player.remainingAir - event.amount
        if (airLoss <= 0) return
        val basisPoints = effect(player, PerkEffectKind.AIR_PRESERVATION)
        if (basisPoints <= 0) return
        event.amount += scaledUnits(airLoss, basisPoints, roll())
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onAnvilPrepare(event: PrepareAnvilEvent) {
        val view = event.view
        val player = view.player as? Player ?: return
        val inventory = event.inventory
        val first = inventory.getItem(0).anvilSnapshot()
        val second = inventory.getItem(1).anvilSnapshot()
        val inputs = AnvilInputs(first, second, view.renameText)
        val displayedCost = view.repairCost
        val previous = anvilCosts[player.uniqueId]
        val repeated = previous != null && previous.view === view && previous.inputs.sameAs(inputs) &&
            displayedCost == previous.reduced
        val originalCost = if (repeated) requireNotNull(previous).original else displayedCost
        val result = event.result
        val isNativeCombination = first != null && second != null && result != null &&
            !result.type.isAir && result.type == first.type && !first.hasPerkCustomData() &&
            !second.hasPerkCustomData() && !result.hasPerkCustomData()
        val basisPoints = if (isNativeCombination) effect(player, PerkEffectKind.ANVIL_COST_REDUCTION) else 0

        if (!isNativeCombination || basisPoints <= 0 || originalCost <= 0 || originalCost >= view.maximumRepairCost) {
            if (repeated) view.repairCost = originalCost
            anvilCosts.remove(player.uniqueId)
            return
        }

        val discount = (originalCost.toLong() * basisPoints / 10_000).toInt()
        val reducedCost = (originalCost - discount).coerceAtLeast(1)
        view.repairCost = reducedCost
        anvilCosts[player.uniqueId] = AnvilCost(view, inputs, originalCost, reducedCost)
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        anvilCosts.remove(event.player.uniqueId)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        anvilCosts.remove(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onExhaustion(event: EntityExhaustionEvent) {
        if (event.isCancelled || event.exhaustion <= 0f) return
        val player = event.entity as? Player ?: return
        val foodReduction = effect(player, PerkEffectKind.FOOD_EXHAUSTION_REDUCTION)
        val movementReduction = if (event.exhaustionReason in MOVEMENT_REASONS) {
            effect(player, PerkEffectKind.MOVEMENT_EXHAUSTION_REDUCTION)
        } else {
            0
        }
        val basisPoints = maxOf(foodReduction, movementReduction)
        if (basisPoints > 0) event.exhaustion *= 1f - basisPoints / 10_000f
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        if (event.isCancelled || event.damage <= 0) return
        val player = event.entity as? Player ?: return
        val basisPoints = if (event.cause == EntityDamageEvent.DamageCause.FALL) {
            effect(player, PerkEffectKind.FALL_REDUCTION)
        } else {
            val attacker = (event as? EntityDamageByEntityEvent)?.damager ?: return
            val source = if (attacker is Projectile) attacker.shooter else attacker
            if (source !is Enemy) return
            val bonus = effect(player, PerkEffectKind.NEARBY_DEFENCE)
            if (bonus > 0 && companionNearby(player)) bonus else 0
        }
        if (basisPoints > 0) event.damage *= 1.0 - basisPoints / 10_000.0
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onExperience(event: PlayerExpChangeEvent) {
        if (event.amount <= 0 || !eligible(event.player)) return
        val orb = event.source as? ExperienceOrb ?: return
        if (orb.spawnReason !in NATURAL_XP_REASONS) return
        val player = event.player
        val sourceBonus = when (orb.spawnReason) {
            ExperienceOrb.SpawnReason.FURNACE -> effect(player, PerkEffectKind.SMELTING_XP)
            ExperienceOrb.SpawnReason.VILLAGER_TRADE -> effect(player, PerkEffectKind.TRADE_XP)
            else -> 0
        }
        val nearby = effect(player, PerkEffectKind.NEARBY_XP)
        val basisPoints = maxOf(sourceBonus, if (nearby > 0 && companionNearby(player)) nearby else 0)
        if (basisPoints > 0) event.amount = (event.amount.toLong() + scaledUnits(event.amount, basisPoints, roll()))
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    companion object {
        /** Stochastic rounding keeps one-point actions useful without a reloadable accumulation bank. */
        internal fun scaledUnits(amount: Int, basisPoints: Int, roll: Int): Int {
            require(amount >= 0 && basisPoints in 0..5_000 && roll in 0 until 10_000)
            val scaled = amount.toLong() * basisPoints
            return (scaled / 10_000 + if (roll < scaled % 10_000) 1 else 0).toInt()
        }

        private val MOVEMENT_REASONS = setOf(
            EntityExhaustionEvent.ExhaustionReason.SPRINT,
            EntityExhaustionEvent.ExhaustionReason.SWIM,
            EntityExhaustionEvent.ExhaustionReason.JUMP,
            EntityExhaustionEvent.ExhaustionReason.JUMP_SPRINT,
        )
        private val NATURAL_XP_REASONS = setOf(
            ExperienceOrb.SpawnReason.ENTITY_DEATH, ExperienceOrb.SpawnReason.FURNACE,
            ExperienceOrb.SpawnReason.BREED, ExperienceOrb.SpawnReason.VILLAGER_TRADE,
            ExperienceOrb.SpawnReason.FISHING, ExperienceOrb.SpawnReason.BLOCK_BREAK,
        )
    }
}
