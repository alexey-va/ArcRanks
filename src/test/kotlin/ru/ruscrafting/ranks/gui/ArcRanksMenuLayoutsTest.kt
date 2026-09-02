package ru.ruscrafting.ranks.gui

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files

class ArcRanksMenuLayoutsTest : FunSpec({
    test("fixed actions and dynamic regions follow one validated config generation") {
        val root = Files.createTempDirectory("arcranks-menu-layout")
        val source = requireNotNull(ArcRanksMenuLayoutsTest::class.java.classLoader.getResourceAsStream("config.yml"))
        val data = source.use { Yaml().load<MutableMap<String, Any?>>(it) }
        @Suppress("UNCHECKED_CAST")
        val layouts = ((data.getValue("gui") as MutableMap<String, Any?>).getValue("layouts") as MutableMap<String, Any?>)
        @Suppress("UNCHECKED_CAST")
        val analytics = layouts.getValue("analytics") as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        ((analytics.getValue("elements") as MutableMap<String, Any?>).getValue("refresh") as MutableMap<String, Any?>)["slot"] = 52
        @Suppress("UNCHECKED_CAST")
        ((analytics.getValue("regions") as MutableMap<String, Any?>).getValue("windows") as MutableMap<String, Any?>)["slots"] = listOf(11, 13, 15)
        Files.writeString(root.resolve("config.yml"), Yaml().dump(data))

        val catalog = ArcRanksMenuLayouts.loadConfiguration(root)
        catalog.require(ArcRanksMenuLayouts.ANALYTICS).slot("refresh").index shouldBe 52
        catalog.require(ArcRanksMenuLayouts.ANALYTICS).region("windows").map { it.index } shouldBe listOf(11, 13, 15)
    }

    test("overlap rejects the complete candidate") {
        val root = Files.createTempDirectory("arcranks-menu-overlap")
        val source = requireNotNull(ArcRanksMenuLayoutsTest::class.java.classLoader.getResourceAsStream("config.yml"))
        Files.writeString(root.resolve("config.yml"), source.bufferedReader().use { it.readText() }
            .replace("refresh: {slot: 53}", "refresh: {slot: 45}"))

        runCatching { ArcRanksMenuLayouts.loadConfiguration(root) }.isFailure shouldBe true
    }

    test("undersized dynamic region rejects the complete candidate") {
        val root = Files.createTempDirectory("arcranks-menu-capacity")
        val source = requireNotNull(ArcRanksMenuLayoutsTest::class.java.classLoader.getResourceAsStream("config.yml"))
        val yaml = source.bufferedReader().use { it.readText() }.replace(
            "offers: {slots: [10, 11, 12, 15, 16, 17, 19, 20, 21, 24, 25, 26, 28, 29, 30, 33, 34, 35]}",
            "offers: {slots: [10, 11, 12]}",
        )
        Files.writeString(root.resolve("config.yml"), yaml)

        val failure = runCatching { ArcRanksMenuLayouts.loadConfiguration(root) }.exceptionOrNull()
        requireNotNull(failure).message shouldContain "needs at least 18 slots, got 3"
    }
})
