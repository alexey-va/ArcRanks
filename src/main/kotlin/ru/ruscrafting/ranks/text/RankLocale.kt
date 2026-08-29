package ru.ruscrafting.ranks.text

import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.text.ConfigLocaleCatalog
import ru.arc.text.LocaleRequirements
import ru.arc.text.LocalizedMiniMessage
import ru.ruscrafting.ranks.domain.RankCatalog
import ru.ruscrafting.ranks.domain.SpecializationPath
import java.nio.file.Path

class RankLocale(
    dataRoot: Path,
    private val defaultLocale: () -> String,
    private val useClientLocale: () -> Boolean,
) {
    private val renderer = LocalizedMiniMessage(
        catalogs = mapOf(
            "ru" to ConfigLocaleCatalog(ConfigManager.of(dataRoot, "lang/ru.yml")),
            "en" to ConfigLocaleCatalog(ConfigManager.of(dataRoot, "lang/en.yml")),
        ),
        defaultLocale = defaultLocale,
    )

    fun render(
        path: String,
        audience: CommandSender? = null,
        values: Map<String, Component> = emptyMap(),
    ): Component = renderer.render(path, localeTag(audience), values)

    fun renderLines(
        path: String,
        audience: CommandSender? = null,
        values: Map<String, Component> = emptyMap(),
    ): List<Component> = renderer.renderLines(path, localeTag(audience), values)

    fun text(value: Any?): Component = renderer.literal(value)

    fun validate(catalog: RankCatalog) {
        val rankPaths = catalog.ranks.flatMap { rank -> listOf(rank.displayNameKey) + rank.benefitKeys }
        val pathPaths = SpecializationPath.entries.flatMap { path ->
            val key = path.name.lowercase()
            listOf("paths.$key.name", "paths.$key.summary")
        }
        renderer.validate(
            LocaleRequirements(
                scalarPaths = SCALAR_PATHS + rankPaths + pathPaths,
                listPaths = LIST_PATHS,
            ),
        )
    }

    private fun localeTag(audience: CommandSender?): String =
        if (useClientLocale() && audience is Player) audience.locale().toLanguageTag() else defaultLocale()

    private companion object {
        val SCALAR_PATHS = setOf(
            "prefix",
            "commands.help",
            "commands.player-only",
            "commands.no-permission",
            "commands.loading",
            "commands.storage-unavailable",
            "commands.shadow-mode",
            "commands.rank-state.missing",
            "commands.rank-state.conflict",
            "commands.rank-state.unknown",
            "commands.why.ready",
            "commands.why.core",
            "commands.why.paths",
            "commands.why.unavailable",
            "commands.why.top",
            "commands.benefits.header",
            "commands.benefits.entry",
            "commands.focus.selected",
            "commands.focus.unknown",
            "commands.promotion.success",
            "commands.promotion.busy",
            "commands.promotion.retryable",
            "commands.promotion.not-eligible",
            "commands.reload.success",
            "commands.reload.failure",
            "commands.admin.inspect",
            "commands.admin.grant-success",
            "commands.admin.grant-duplicate",
            "commands.admin.grant-invalid",
            "commands.admin.simulate",
            "mastery.none",
            "mastery.i",
            "mastery.ii",
            "mastery.iii",
            "gui.title",
            "gui.common.close.name",
            "gui.common.refresh.name",
            "gui.profile.name",
            "gui.rank.completed.name",
            "gui.rank.current.name",
            "gui.rank.next.name",
            "gui.rank.locked.name",
            "gui.path.available.name",
            "gui.path.complete.name",
            "gui.path.unavailable.name",
            "gui.path.selected.name",
            "gui.recommendation.name",
            "gui.recommendation.active",
            "gui.recommendation.path",
            "gui.recommendation.ready",
            "gui.recommendation.top",
            "gui.benefits.name",
            "gui.promotion.ready.name",
            "gui.promotion.blocked.name",
            "gui.promotion.running.name",
            "gui.promotion.top.name",
            "gui.state.loading.name",
            "gui.state.error.name",
            "celebration.title",
            "celebration.subtitle",
        )
        val LIST_PATHS = setOf(
            "gui.common.close.lore",
            "gui.common.refresh.lore",
            "gui.profile.lore",
            "gui.rank.completed.lore",
            "gui.rank.current.lore",
            "gui.rank.next.lore",
            "gui.rank.locked.lore",
            "gui.path.available.lore",
            "gui.path.complete.lore",
            "gui.path.unavailable.lore",
            "gui.path.selected.lore",
            "gui.recommendation.lore",
            "gui.benefits.lore",
            "gui.promotion.ready.lore",
            "gui.promotion.blocked.lore",
            "gui.promotion.running.lore",
            "gui.promotion.top.lore",
            "gui.state.loading.lore",
            "gui.state.error.lore",
        )
    }
}
