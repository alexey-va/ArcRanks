package ru.ruscrafting.ranks.progress

import org.bukkit.plugin.Plugin
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Read-only bridge to the optional ARC contract catalog.
 *
 * ARC is deliberately kept out of ArcRanks' compile-time contract surface:
 * this adapter only observes its public API and fails closed when that API is
 * absent or changes.
 */
class ContractQuestAvailability(private val plugin: Plugin) {
    fun available(): Set<String> {
        if (!plugin.isEnabled) return emptySet()

        val arc = runCatching {
            plugin.server.pluginManager.getPlugin(ARC_PLUGIN_NAME)
        }.getOrNull()?.takeIf { it.isEnabled } ?: return emptySet()

        return runCatching {
            val manager = findManagerClass(arc) ?: return@runCatching emptySet()
            val submissionsEnabled = manager.getMethod(SUBMISSIONS_ENABLED).invoke(null) as? Boolean
                ?: return@runCatching emptySet()
            if (!submissionsEnabled) return@runCatching emptySet()

            val now = System.currentTimeMillis()
            val views = manager.getMethod(CURRENT_VIEWS, Long::class.javaPrimitiveType).invoke(null, now)
                as? Iterable<*> ?: return@runCatching emptySet()
            val utcDayEnd = LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC)
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()

            val result = linkedSetOf<String>()
            views.forEach { view ->
                requireNotNull(view) { "ARC returned a null contract view" }
                val id = view.string("getId")
                require(CONTRACT_ID.matches(id)) { "ARC returned an invalid contract id" }
                if (view.string("getStatus") != OPEN_STATUS) return@forEach
                if (view.long("getWindowEndsAt") < utcDayEnd) return@forEach
                if (view.long("getRemainingQuantity") <= 0L) return@forEach
                val budget = view.long("getBudgetMinor")
                val spent = view.long("getSpentMinor")
                val reserved = view.long("getReservedMinor")
                if (budget <= 0L || spent < 0L || reserved < 0L) return@forEach
                if (budget <= spent || budget - spent <= reserved) return@forEach
                if (view.long("getPayoutMinorPerUnit") <= 0L) return@forEach
                result += "$KEY_PREFIX$id"
            }
            result
        }.getOrElse { emptySet() }
    }

    private fun findManagerClass(arc: Plugin): Class<*>? {
        val loaders = sequenceOf(arc.javaClass.classLoader, plugin.javaClass.classLoader)
            .filterNotNull()
            .distinct()
        return loaders.mapNotNull { loader ->
            runCatching {
                Class.forName(CONTRACTS_MANAGER_CLASS, true, loader)
            }.getOrNull()
        }.firstOrNull { manager ->
            manager.getMethod(SUBMISSIONS_ENABLED).isStatic() &&
                manager.getMethod(CURRENT_VIEWS, Long::class.javaPrimitiveType).isStatic()
        }
    }

    private fun Any.string(method: String): String =
        javaClass.getMethod(method).invoke(this) as? String
            ?: error("ARC contract view method $method returned an invalid value")

    private fun Any.long(method: String): Long =
        (javaClass.getMethod(method).invoke(this) as? Number)?.toLong()
            ?: error("ARC contract view method $method returned an invalid value")

    private fun Method.isStatic(): Boolean = Modifier.isStatic(modifiers)

    private companion object {
        const val ARC_PLUGIN_NAME = "ARC"
        const val CONTRACTS_MANAGER_CLASS = "ru.arc.contracts.ContractsManager"
        const val CURRENT_VIEWS = "currentViews"
        const val SUBMISSIONS_ENABLED = "submissionsEnabled"
        const val OPEN_STATUS = "open"
        const val KEY_PREFIX = "contract.open:"
        val CONTRACT_ID = Regex("[a-z0-9][a-z0-9_-]{2,47}")
    }
}
