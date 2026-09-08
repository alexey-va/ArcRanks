package ru.ruscrafting.ranks.placeholder

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.ranks.config.ArcRanksSettings
import ru.ruscrafting.ranks.config.RankCatalogLoader
import ru.ruscrafting.ranks.domain.MasteryLevel
import ru.ruscrafting.ranks.domain.PathAvailability
import ru.ruscrafting.ranks.domain.PlayerProgressProfile
import ru.ruscrafting.ranks.domain.ProgressSnapshot
import ru.ruscrafting.ranks.domain.RankEvaluator
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.rankstate.RankState
import ru.ruscrafting.ranks.service.RankPlayerSnapshot
import ru.ruscrafting.ranks.service.RankSnapshotCache
import ru.ruscrafting.ranks.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.ranks.text.RankLocale
import java.nio.file.Files
import java.util.Locale

class ArcRanksPlaceholderExpansionMockBukkitTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "online rank placeholders honor each player's client locale" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("ArcRanksPlaceholderTest")
                val root = Files.createTempDirectory("arcranks-placeholder-platform")
                Config(root, "config.yml").apply {
                    setBoolean("locale.use-client-locale", true)
                    saveStrict()
                }
                val settings = ArcRanksSettings.loadFresh(root) { "secret" }
                val loaded = RankCatalogLoader(Config(root, "ranks.yml")).loadWithMastery()
                val locale = RankLocale.fresh(root, { settings.defaultLocale }, { settings.useClientLocale })
                val cache = RankSnapshotCache()
                val russian = paper.addPlayer("RussianPlaceholder")
                val english = paper.addPlayer("EnglishPlaceholder")
                russian.setLocale(Locale.forLanguageTag("ru-RU"))
                english.setLocale(Locale.forLanguageTag("en-US"))
                cache.put(russian.uniqueId, placeholderSnapshot(loaded, SpecializationPath.FARMING))
                cache.put(english.uniqueId, placeholderSnapshot(loaded, SpecializationPath.FARMING))
                val expansion = ArcRanksPlaceholderExpansion(
                    plugin,
                    { loaded.catalog },
                    { locale },
                    { loaded.mastery },
                    cache,
                )

                expansion.onRequest(russian, "rank_name") shouldBe "Поселенец"
                expansion.onRequest(english, "rank_name") shouldBe "Settler"
                expansion.onRequest(russian, "next_rank") shouldBe "Крестьянин"
                expansion.onRequest(english, "next_rank") shouldBe "Peasant"
                cache.clear()
                expansion.onRequest(russian, "quest_active") shouldBe "false"
                expansion.onRequest(russian, "quest_line_1") shouldBe ""
                expansion.onRequest(null, "quest_line_3") shouldBe ""
                val questExpansion = ArcRanksPlaceholderExpansion(plugin, { loaded.catalog }, { locale }, { loaded.mastery }, cache,
                    questPlaceholder = { _, key -> if (key == "quest_active") "true" else "12/100" })
                questExpansion.onRequest(russian, "quest_active") shouldBe "true"
                questExpansion.onRequest(russian, "quest_line_2") shouldBe "12/100"
            }
        }
    }
})

private fun placeholderSnapshot(
    loaded: ru.ruscrafting.ranks.config.LoadedRankCatalog,
    focus: SpecializationPath,
): RankPlayerSnapshot {
    val profile = PlayerProgressProfile(ProgressSnapshot.EMPTY, focus)
    val rank = loaded.catalog.ranks.first().id
    val availability = PathAvailability.allAvailable()
    return RankPlayerSnapshot(
        rankState = RankState.Exact(rank),
        profile = profile,
        evaluation = RankEvaluator(loaded.catalog).evaluate(rank, profile.progress, availability),
        mastery = SpecializationPath.entries.associateWith { MasteryLevel.NONE },
        availability = availability,
        activePerks = emptySet(),
    )
}
