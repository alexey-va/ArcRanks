package ru.ruscrafting.ranks.config

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Files

class ArcRanksConfigurationTest : StringSpec({
    afterTest { ConfigManager.clear() }

    "fresh byte-identical generations are a real no-op" {
        val root = Files.createTempDirectory("arcranks-config-noop")
        val loader = ArcRanksConfigLoader(root) { "test-secret" }

        val first = loader.load(1)
        val second = loader.load(2)

        (first !== second) shouldBe true
        (first.ranks.catalog !== second.ranks.catalog) shouldBe true
        (first.perks !== second.perks) shouldBe true
        ArcRanksConfigDiffer.diff(first, second).noChanges shouldBe true
        first.fingerprints.files.values.joinToString() shouldNotContain "test-secret"
    }

    "invalid isolated locale candidate leaves active generation and ConfigManager untouched" {
        val root = Files.createTempDirectory("arcranks-config-isolation")
        val loader = ArcRanksConfigLoader(root) { "test-secret" }
        val active = loader.load(1)
        val store = ArcRanksConfigStore(active)
        val plain = PlainTextComponentSerializer.plainText()
        val oldTitle = plain.serialize(active.locale.render("gui.title"))
        val version = ConfigManager.getVersion()
        Config(root, "lang/ru.yml").apply {
            setString("gui.title", "<italic:false>Кандидат")
            removeKey("commands.help")
            saveStrict()
        }

        shouldThrowAny { loader.load(2) }

        (store.current() === active) shouldBe true
        plain.serialize(active.locale.render("gui.title")) shouldBe oldTitle
        ConfigManager.getVersion() shouldBe version
    }

    "live gameplay, presentation, schedule, and catalog rules are classified together" {
        val root = Files.createTempDirectory("arcranks-config-live-diff")
        val loader = ArcRanksConfigLoader(root) { "test-secret" }
        val current = loader.load(1)
        Config(root, "config.yml").apply {
            setString("promotion-mode", "ACTIVE")
            setBoolean("features.perks", false)
            setLong("progress.sample-ticks", 2_400)
            setInt("analytics.maximum-metric-keys", 640)
            setInt("gui.back.custom-model-data", 42)
            setLong("celebration.title.stay-ms", 2_500)
            saveStrict()
        }
        Config(root, "ranks.yml").apply {
            setLong("ranks.peasant.active-minutes", 121)
            saveStrict()
        }
        Config(root, "perks.yml").apply {
            setInt("perks.farming_momentum.basis-points", 600)
            saveStrict()
        }
        Config(root, "contracts.yml").apply {
            setLong("targets.farming", 125)
            saveStrict()
        }
        Config(root, "weekly-kits.yml").apply {
            setInt("kits.settler.minimum-free-slots", 2)
            saveStrict()
        }

        val diff = ArcRanksConfigDiffer.diff(current, loader.load(2))

        diff.restartRequired shouldBe emptySet()
        diff.liveAreas.shouldContainAll(
            ArcRanksLiveArea.PROMOTION,
            ArcRanksLiveArea.FEATURES,
            ArcRanksLiveArea.PROGRESS,
            ArcRanksLiveArea.ANALYTICS,
            ArcRanksLiveArea.GUI,
            ArcRanksLiveArea.CELEBRATION,
            ArcRanksLiveArea.RANK_RULES,
            ArcRanksLiveArea.PERK_RULES,
            ArcRanksLiveArea.CONTRACTS,
            ArcRanksLiveArea.WEEKLY_KITS,
        )
    }

    "restart-only changes are aggregated and never expose the database secret" {
        val root = Files.createTempDirectory("arcranks-config-restart")
        var secret = "old-secret"
        val loader = ArcRanksConfigLoader(root) { secret }
        val current = loader.load(1)
        Config(root, "config.yml").apply {
            setString("server-id", "survival-two")
            setInt("mysql.port", 3_307)
            setLong("runtime.startup-timeout-seconds", 45)
            saveStrict()
        }
        Config(root, "ranks.yml").apply {
            setString("ranks.peasant.group", "rank_peasant_two")
            saveStrict()
        }
        secret = "rotated-secret"

        val diff = ArcRanksConfigDiffer.diff(current, loader.load(2))

        diff.restartRequired.shouldContainAll(
            "server-id",
            "mysql.port",
            "mysql.password",
            "runtime.startup-timeout-seconds",
            "ranks.peasant.group",
        )
        diff.toString() shouldNotContain "rotated-secret"
    }

    "misspelled or missing gui item keys reject the isolated candidate" {
        val root = Files.createTempDirectory("arcranks-config-gui-keys")
        val loader = ArcRanksConfigLoader(root) { "test-secret" }
        loader.load(1)
        Config(root, "config.yml").apply {
            removeKey("gui.items.perk-available")
            setString("gui.items.perk-availabe.material", "BOOK")
            setInt("gui.items.perk-availabe.custom-model-data", 0)
            saveStrict()
        }

        val failure = shouldThrowAny { loader.load(2) }

        failure.message.orEmpty() shouldNotContain "test-secret"
        failure.message.orEmpty().contains("perk-available") shouldBe true
        failure.message.orEmpty().contains("perk-availabe") shouldBe true
    }

    "invalid live weekly kit icon rejects the isolated candidate" {
        val root = Files.createTempDirectory("arcranks-config-kit-icon")
        val loader = ArcRanksConfigLoader(root) { "test-secret" }
        loader.load(1)
        Config(root, "weekly-kits.yml").apply {
            setString("kits.settler.icon.material", "NOT_A_REAL_MATERIAL")
            saveStrict()
        }

        val failure = shouldThrowAny { loader.load(2) }

        failure.message.orEmpty() shouldNotContain "test-secret"
        failure.message.orEmpty().contains("weekly-kits.settler.icon") shouldBe true
    }
})
