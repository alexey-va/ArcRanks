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
import ru.ruscrafting.ranks.perk.PerkCatalog
import ru.ruscrafting.ranks.kit.WeeklyKitCatalog
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    fun renderDurationMinutes(minutes: Long, audience: CommandSender? = null): Component {
        require(minutes >= 0) { "Duration minutes must not be negative" }
        val days = minutes / MINUTES_PER_DAY
        val hours = (minutes % MINUTES_PER_DAY) / MINUTES_PER_HOUR
        val remainingMinutes = minutes % MINUTES_PER_HOUR
        val key = when {
            days > 0 && hours > 0 && remainingMinutes > 0 -> "duration.days-hours-minutes"
            days > 0 && hours > 0 -> "duration.days-hours"
            days > 0 && remainingMinutes > 0 -> "duration.days-minutes"
            days > 0 -> "duration.days"
            hours > 0 && remainingMinutes > 0 -> "duration.hours-minutes"
            hours > 0 -> "duration.hours"
            else -> "duration.minutes"
        }
        return render(
            key,
            audience,
            mapOf(
                "days" to text(days),
                "hours" to text(hours),
                "minutes" to text(remainingMinutes),
            ),
        )
    }

    fun renderWeekPeriod(start: LocalDate, audience: CommandSender? = null): Component {
        val locale = Locale.forLanguageTag(localeTag(audience))
        val pattern = if (locale.language == "ru") "d MMMM" else "MMM d"
        val formatter = DateTimeFormatter.ofPattern(pattern, locale)
        return text("${formatter.format(start)} — ${formatter.format(start.plusDays(6))}")
    }

    fun validate(catalog: RankCatalog, perks: PerkCatalog? = null, weeklyKits: WeeklyKitCatalog? = null) {
        val rankPaths = catalog.ranks.flatMap { rank -> listOf(rank.displayNameKey) + rank.benefitKeys }
        val pathPaths = SpecializationPath.entries.flatMap { path ->
            val key = path.name.lowercase()
            listOf("paths.$key.name", "paths.$key.summary", "paths.$key.details")
        }
        val perkPaths = perks?.perks?.flatMap { listOf(it.nameKey, it.descriptionKey) }.orEmpty()
        val weeklyKitPaths = weeklyKits?.definitions?.flatMap { listOf(it.summaryKey) + it.contentKeys }.orEmpty()
        renderer.validate(
            LocaleRequirements(
                scalarPaths = SCALAR_PATHS + rankPaths + pathPaths + perkPaths + weeklyKitPaths,
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
            "commands.admin.analytics",
            "commands.admin.analytics-invalid",
            "commands.contracts.accepted",
            "commands.contracts.already-active",
            "commands.contracts.offer-unavailable",
            "commands.contracts.cycle-complete",
            "commands.contracts.claimed",
            "commands.contracts.not-ready",
            "commands.contracts.already-claimed",
            "commands.contracts.no-active",
            "commands.contracts.rerolled",
            "commands.contracts.reroll-used",
            "commands.contracts.storage-unavailable",
            "commands.perks.selected",
            "commands.perks.already-selected",
            "commands.perks.removed",
            "commands.perks.not-selected",
            "commands.perks.locked",
            "commands.perks.full",
            "commands.perks.storage-unavailable",
            "commands.weekly-kit.claimed",
            "commands.weekly-kit.already-claimed",
            "commands.weekly-kit.delivery-pending",
            "commands.weekly-kit.inventory-full",
            "commands.weekly-kit.provider-rejected",
            "commands.weekly-kit.storage-unavailable",
            "mastery.none",
            "mastery.i",
            "mastery.ii",
            "mastery.iii",
            "duration.minutes",
            "duration.hours",
            "duration.hours-minutes",
            "duration.days",
            "duration.days-hours",
            "duration.days-minutes",
            "duration.days-hours-minutes",
            "gui.title",
            "gui.paths.title",
            "gui.common.refresh.name",
            "gui.common.back.name",
            "gui.passport.contracts.name",
            "gui.passport.paths.name",
            "gui.passport.perks.name",
            "gui.passport.weekly-kit.name",
            "gui.passport.benefits.name",
            "gui.passport.guide.name",
            "gui.profile.name",
            "gui.rank.completed.name",
            "gui.rank.current.name",
            "gui.rank.next.name",
            "gui.rank.locked.name",
            "gui.rank.benefit-line",
            "gui.path.available.name",
            "gui.path.complete.name",
            "gui.path.unavailable.name",
            "gui.path.selected.name",
            "gui.recommendation.active",
            "gui.recommendation.path",
            "gui.recommendation.ready",
            "gui.recommendation.top",
            "gui.promotion.ready.name",
            "gui.promotion.blocked.name",
            "gui.promotion.running.name",
            "gui.promotion.top.name",
            "gui.promotion.feedback.blocked.name",
            "gui.state.loading.name",
            "gui.state.error.name",
            "gui.state.rank-missing.name",
            "gui.state.rank-conflict.name",
            "gui.state.rank-unknown.name",
            "gui.contracts.title",
            "gui.contracts.status.name",
            "gui.contracts.stamp.name",
            "gui.contracts.offer.name",
            "gui.contracts.active.name",
            "gui.contracts.ready.name",
            "gui.contracts.claim.active.name",
            "gui.contracts.claim.ready.name",
            "gui.contracts.complete.name",
            "gui.contracts.reroll.name",
            "gui.contracts.reroll-used.name",
            "gui.contracts.reroll-active.name",
            "gui.contracts.reroll-complete.name",
            "gui.contracts.loading.name",
            "gui.contracts.running.name",
            "gui.contracts.error.name",
            "gui.perks.title",
            "gui.perks.selection.title",
            "gui.perks.overview.name",
            "gui.perks.selection.name",
            "gui.perks.path.name",
            "gui.perks.slot.empty.name",
            "gui.perks.slot.active.name",
            "gui.perks.card.available.name",
            "gui.perks.card.selected.name",
            "gui.perks.card.other.name",
            "gui.perks.card.locked.name",
            "gui.perks.loading.name",
            "gui.perks.running.name",
            "gui.perks.error.name",
            "gui.analytics.title",
            "gui.analytics.window.available.name",
            "gui.analytics.window.selected.name",
            "gui.analytics.populated.name",
            "gui.analytics.empty.name",
            "gui.analytics.recommendation.active",
            "gui.analytics.recommendation.ready",
            "gui.analytics.recommendation.top",
            "gui.analytics.cards.overview.name",
            "gui.analytics.cards.contracts.name",
            "gui.analytics.cards.perks.name",
            "gui.analytics.cards.promotions.name",
            "gui.analytics.cards.recommendations.name",
            "gui.analytics.cards.health.name",
            "gui.analytics.loading.name",
            "gui.analytics.error.name",
            "gui.weekly-kit.title",
            "gui.weekly-kit.summary.name",
            "gui.weekly-kit.contents.name",
            "gui.weekly-kit.claim.available.name",
            "gui.weekly-kit.claim.delivering.name",
            "gui.weekly-kit.claim.claimed.name",
            "gui.weekly-kit.loading.name",
            "gui.weekly-kit.claiming.name",
            "gui.weekly-kit.error.name",
            "celebration.title",
            "celebration.subtitle",
            "celebration.broadcast",
        )

        const val MINUTES_PER_HOUR = 60L
        const val MINUTES_PER_DAY = 1_440L
        val LIST_PATHS = setOf(
            "gui.common.refresh.lore",
            "gui.common.back.lore",
            "gui.passport.contracts.lore",
            "gui.passport.paths.lore",
            "gui.passport.perks.lore",
            "gui.passport.weekly-kit.lore",
            "gui.passport.benefits.lore",
            "gui.passport.guide.lore",
            "gui.profile.lore",
            "gui.rank.completed.lore",
            "gui.rank.current.lore",
            "gui.rank.next.lore",
            "gui.rank.locked.lore",
            "gui.path.available.lore",
            "gui.path.complete.lore",
            "gui.path.unavailable.lore",
            "gui.path.selected.lore",
            "gui.promotion.ready.lore",
            "gui.promotion.blocked.lore",
            "gui.promotion.running.lore",
            "gui.promotion.top.lore",
            "gui.promotion.feedback.blocked.lore",
            "gui.state.loading.lore",
            "gui.state.error.lore",
            "gui.state.rank-missing.lore",
            "gui.state.rank-conflict.lore",
            "gui.state.rank-unknown.lore",
            "gui.contracts.status.lore",
            "gui.contracts.stamp.lore",
            "gui.contracts.offer.lore",
            "gui.contracts.active.lore",
            "gui.contracts.ready.lore",
            "gui.contracts.claim.active.lore",
            "gui.contracts.claim.ready.lore",
            "gui.contracts.complete.lore",
            "gui.contracts.reroll.lore",
            "gui.contracts.reroll-used.lore",
            "gui.contracts.reroll-active.lore",
            "gui.contracts.reroll-complete.lore",
            "gui.contracts.loading.lore",
            "gui.contracts.running.lore",
            "gui.contracts.error.lore",
            "gui.perks.slot.empty.lore",
            "gui.perks.slot.active.lore",
            "gui.perks.overview.lore",
            "gui.perks.selection.lore",
            "gui.perks.path.lore",
            "gui.perks.card.available.lore",
            "gui.perks.card.selected.lore",
            "gui.perks.card.other.lore",
            "gui.perks.card.locked.lore",
            "gui.perks.loading.lore",
            "gui.perks.running.lore",
            "gui.perks.error.lore",
            "gui.analytics.window.available.lore",
            "gui.analytics.window.selected.lore",
            "gui.analytics.populated.lore",
            "gui.analytics.empty.lore",
            "gui.analytics.cards.overview.lore",
            "gui.analytics.cards.contracts.lore",
            "gui.analytics.cards.perks.lore",
            "gui.analytics.cards.promotions.lore",
            "gui.analytics.cards.recommendations.lore",
            "gui.analytics.cards.health.lore",
            "gui.analytics.loading.lore",
            "gui.analytics.error.lore",
            "gui.weekly-kit.summary.lore",
            "gui.weekly-kit.contents.lore",
            "gui.weekly-kit.claim.available.lore",
            "gui.weekly-kit.claim.delivering.lore",
            "gui.weekly-kit.claim.claimed.lore",
            "gui.weekly-kit.loading.lore",
            "gui.weekly-kit.claiming.lore",
            "gui.weekly-kit.error.lore",
        )
    }
}
