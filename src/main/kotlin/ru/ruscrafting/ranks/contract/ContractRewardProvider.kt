package ru.ruscrafting.ranks.contract

import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.Server
import org.bukkit.entity.Player
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class ContractRewardApplyResult {
    APPLIED,
    REJECTED,
}

sealed interface ContractRewardComponent {
    val key: String
    val amount: Long

    data class Money(override val amount: Long) : ContractRewardComponent {
        override val key = "money"
    }

    data class Tokens(override val amount: Long, val currency: String) : ContractRewardComponent {
        override val key = "tokens"
    }

    data class Item(override val amount: Long, val preset: String) : ContractRewardComponent {
        override val key = "item"
    }
}

fun ContractBonusReward.components(): List<ContractRewardComponent> = listOf(
    ContractRewardComponent.Money(money),
    ContractRewardComponent.Tokens(tokens, tokenCurrency),
    ContractRewardComponent.Item(itemAmount.toLong(), itemPreset),
)

fun interface ContractRewardProvider {
    fun apply(player: Player, component: ContractRewardComponent): ContractRewardApplyResult
}

/** Applies provider effects on the Paper primary thread. */
class PaperContractRewardProvider private constructor(
    private val server: Server,
    private val vault: Economy,
    private val redisClassLoader: ClassLoader,
    currencies: Map<String, RedisCurrencyHandle>,
) : ContractRewardProvider {
    private val currencies = ConcurrentHashMap(currencies)

    override fun apply(player: Player, component: ContractRewardComponent): ContractRewardApplyResult {
        val accepted = when (component) {
            is ContractRewardComponent.Money -> vault.depositPlayer(player, component.amount.toDouble()).transactionSuccess()
            is ContractRewardComponent.Tokens -> currency(component.currency)
                .deposit(player.uniqueId, player.name, component.amount.toDouble())
                .transactionSuccess()
            is ContractRewardComponent.Item -> server.dispatchCommand(
                server.consoleSender,
                "arc give ${player.name} ${component.preset} ${component.amount}",
            )
        }
        return if (accepted) ContractRewardApplyResult.APPLIED else ContractRewardApplyResult.REJECTED
    }

    private fun currency(id: String): RedisCurrencyHandle = currencies.computeIfAbsent(id) {
        RedisEconomyBridge.open(redisClassLoader, setOf(id)).getValue(id)
    }

    companion object {
        fun create(
            server: Server,
            vault: Economy?,
            redisClassLoader: ClassLoader?,
            rewards: Collection<ContractBonusReward>,
        ): PaperContractRewardProvider {
            val economy = requireNotNull(vault) { "Vault economy service is required for contract rewards" }
            val classLoader = requireNotNull(redisClassLoader) { "RedisEconomy is required for contract token rewards" }
            require(server.pluginManager.isPluginEnabled("ARC")) { "ARC is required for contract item rewards" }
            val currencies = RedisEconomyBridge.open(classLoader, rewards.mapTo(linkedSetOf()) { it.tokenCurrency })
            return PaperContractRewardProvider(server, economy, classLoader, currencies)
        }
    }
}

private data class RedisCurrencyHandle(
    private val currency: Any,
    private val depositMethod: Method,
) {
    fun deposit(playerId: UUID, playerName: String, amount: Double): EconomyResponse = try {
        depositMethod.invoke(currency, playerId, playerName, amount, CONTRACT_REWARD_REASON) as? EconomyResponse
            ?: error("RedisEconomy returned an unsupported deposit result")
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }
}

private object RedisEconomyBridge {
    private const val API_CLASS = "dev.unnm3d.rediseconomy.api.RedisEconomyAPI"
    private const val CURRENCY_CLASS = "dev.unnm3d.rediseconomy.currency.Currency"

    fun open(classLoader: ClassLoader, currencyIds: Set<String>): Map<String, RedisCurrencyHandle> {
        val apiClass = Class.forName(API_CLASS, true, classLoader)
        val currencyClass = Class.forName(CURRENCY_CLASS, true, classLoader)
        val api = requireNotNull(apiClass.getMethod("getAPI").invoke(null)) { "RedisEconomy API is unavailable" }
        val getCurrency = apiClass.getMethod("getCurrencyByName", String::class.java)
        val isEnabled = currencyClass.getMethod("isEnabled")
        val deposit = currencyClass.getMethod(
            "depositPlayer",
            UUID::class.java,
            String::class.java,
            Double::class.javaPrimitiveType,
            String::class.java,
        )
        return currencyIds.associateWith { currencyId ->
            val currency = requireNotNull(getCurrency.invoke(api, currencyId)) {
                "RedisEconomy currency '$currencyId' is unavailable"
            }
            require(isEnabled.invoke(currency) == true) { "RedisEconomy currency '$currencyId' is disabled" }
            RedisCurrencyHandle(currency, deposit)
        }
    }
}

private const val CONTRACT_REWARD_REASON = "ArcRanks personal contract reward"
