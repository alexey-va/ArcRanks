package ru.ruscrafting.ranks

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.yaml.snakeyaml.Yaml

class PluginDescriptorTest : StringSpec({
    "descriptor exposes the ArcRanks player and operator contract" {
        val descriptor = checkNotNull(javaClass.classLoader.getResourceAsStream("plugin.yml")) {
            "plugin.yml must be packaged"
        }.use { input ->
            @Suppress("UNCHECKED_CAST")
            Yaml().load<Map<String, Any?>>(input)
        }

        descriptor["name"] shouldBe "ArcRanks"
        descriptor["version"] shouldBe "0.14.6"
        descriptor["main"] shouldBe "ru.ruscrafting.ranks.paper.ArcRanksPlugin"
        descriptor["api-version"] shouldBe "1.21.11"
        descriptor["depend"] shouldBe listOf("LuckPerms")
        descriptor["softdepend"] shouldBe listOf(
            "Vault", "PlaceholderAPI", "CMI", "EliteMobs", "zAuctionHouse", "zAuctionHouseV3", "RedisEconomy", "ARC", "ArcBuilder", "ArcFarms", "ArcVotes",
        )

        @Suppress("UNCHECKED_CAST")
        val commands = descriptor["commands"] as Map<String, Any?>
        commands.keys shouldContainAll listOf("rank", "rankup")
        @Suppress("UNCHECKED_CAST")
        val rankCommand = commands.getValue("rank") as Map<String, Any?>
        rankCommand["usage"].toString().contains("dialog") shouldBe true

        @Suppress("UNCHECKED_CAST")
        val permissions = descriptor["permissions"] as Map<String, Any?>
        permissions.keys shouldContainAll listOf(
            "arcranks.use",
            "arcranks.rankup",
            "arcranks.admin.inspect",
            "arcranks.admin.grant",
            "arcranks.admin.reload",
            "arcranks.admin.simulate",
            "arcranks.admin.analytics",
            "arcranks.admin.contract",
            "arcranks.admin.kit",
        )
        @Suppress("UNCHECKED_CAST")
        val usePermission = permissions.getValue("arcranks.use") as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val rankUpPermission = permissions.getValue("arcranks.rankup") as Map<String, Any?>
        usePermission["default"] shouldBe false
        rankUpPermission["default"] shouldBe false
    }
})
