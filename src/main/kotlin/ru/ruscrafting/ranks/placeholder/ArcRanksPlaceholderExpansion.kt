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
    private val questPlaceholder: (java.util.UUID, String) -> String? = { _, key -> ru.ruscrafting.ranks.quest.QuestHudSnapshot.emptyPlaceholder(key) },
) : PlaceholderExpansion() {
    override fun getIdentifier(): String = "arcranks"

    override fun getAuthor(): String = "RusCrafting"

    override fun getVersion(): String = plugin.pluginMeta.version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        if (params.startsWith("quest_")) return player?.uniqueId?.let { questPlaceholder(it, params) }
            ?: ru.ruscrafting.ranks.quest.QuestHudSnapshot.emptyPlaceholder(params)
        val snapshot = player?.uniqueId?.let(cache::get) ?: return "…"
        val exact = snapshot.rankState as? RankState.Exact
        val audience = player as? Player
        return when {
            params == "rank_id" -> exact?.rankId?.value ?: "unknown"
            params == "rank_name" -> exact?.let {
                plain(locale().render(catalog().require(it.rankId).displayNameKey, audience))
            } ?: "?"
            params == "next_rank" -> snapshot.evaluation?.nextRank?.let {
                plain(locale().render(it.displayNameKey, audience))
            } ?: "—"
            params == "active_minutes" -> snapshot.profile.progress.value(ProgressMetric.ACTIVE_MINUTES).toString()
            params == "focus" -> snapshot.profile.selectedFocus.name.lowercase()
            params.startsWith("progress_") -> path(params.removePrefix("progress_"))?.let {
                it.progressValue(snapshot.profile.progress).toString()
            }
            params.startsWith("mastery_") -> path(params.removePrefix("mastery_"))?.let {
                MasteryEvaluator.level(snapshot.profile, it, mastery().getValue(it)).name.lowercase()
            }
            else -> null
        }
    }

    private fun path(value: String): SpecializationPath? = SpecializationPath.entries.firstOrNull {
        it.name.equals(value, ignoreCase = true)
    }

    private fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)
}
