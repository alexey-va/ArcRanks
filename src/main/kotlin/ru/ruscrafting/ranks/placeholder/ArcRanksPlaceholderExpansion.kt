package ru.ruscrafting.ranks.placeholder

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.ruscrafting.ranks.domain.MasteryEvaluator
import ru.ruscrafting.ranks.domain.MasteryThresholds
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.domain.RankCatalog
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.rankstate.RankState
import ru.ruscrafting.ranks.service.RankSnapshotCache
import ru.ruscrafting.ranks.text.RankLocale

class ArcRanksPlaceholderExpansion(
    private val plugin: Plugin,
    private val catalog: () -> RankCatalog,
    private val locale: () -> RankLocale,
    private val mastery: () -> Map<SpecializationPath, MasteryThresholds>,
    private val cache: RankSnapshotCache,
    private val currentRank: (java.util.UUID) -> RankState? = { null },
    private val questPlaceholder: (java.util.UUID, String) -> String? = { _, key -> ru.ruscrafting.ranks.quest.QuestHudSnapshot.emptyPlaceholder(key) },
) : PlaceholderExpansion() {
    override fun getIdentifier(): String = "arcranks"

    override fun getAuthor(): String = "RusCrafting"

    override fun getVersion(): String = plugin.pluginMeta.version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        if (params.startsWith("quest_")) return player?.uniqueId?.let { questPlaceholder(it, params) }
            ?: ru.ruscrafting.ranks.quest.QuestHudSnapshot.emptyPlaceholder(params)
        val playerId = player?.uniqueId ?: return "…"
        val snapshot = cache.get(playerId)
        val rankState = snapshot?.rankState ?: currentRank(playerId)
        val exact = rankState as? RankState.Exact
        val audience = player as? Player
        return when {
            params == "rank_id" -> if (rankState == null) "…" else exact?.rankId?.value ?: "unknown"
            params == "rank_name" -> if (rankState == null) "…" else exact?.let {
                plain(locale().render(catalog().require(it.rankId).displayNameKey, audience))
            } ?: "?"
            params == "next_rank" -> if (snapshot == null) "…" else snapshot.evaluation?.nextRank?.let {
                plain(locale().render(it.displayNameKey, audience))
            } ?: "—"
            params == "active_minutes" -> snapshot?.profile?.progress?.value(ProgressMetric.ACTIVE_MINUTES)?.toString() ?: "…"
            params == "focus" -> snapshot?.profile?.selectedFocus?.name?.lowercase() ?: "…"
            params.startsWith("progress_") -> snapshot?.let { loaded ->
                path(params.removePrefix("progress_"))?.let {
                    it.progressValue(loaded.profile.progress).toString()
                }
            } ?: "…"
            params.startsWith("mastery_") -> snapshot?.let { loaded ->
                path(params.removePrefix("mastery_"))?.let {
                    MasteryEvaluator.level(loaded.profile, it, mastery().getValue(it)).name.lowercase()
                }
            } ?: "…"
            else -> null
        }
    }

    private fun path(value: String): SpecializationPath? = SpecializationPath.entries.firstOrNull {
        it.name.equals(value, ignoreCase = true)
    }

    private fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)
}
