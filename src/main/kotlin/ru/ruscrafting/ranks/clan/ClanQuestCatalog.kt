package ru.ruscrafting.ranks.clan

import org.bukkit.Material
import ru.arc.config.Config
import ru.ruscrafting.ranks.quest.QuestMode
import ru.ruscrafting.ranks.quest.QuestPlan
import ru.ruscrafting.ranks.quest.QuestStep
import java.security.MessageDigest
import java.time.LocalDate

data class ClanQuestCatalog(val pool: List<ClanQuestDefinition>, val projectsPerWeek: Int = 3) {
    init {
        require(projectsPerWeek == 3) { "Clan boards contain exactly three weekly projects" }
        require(pool.size in projectsPerWeek..64 && pool.map { it.id }.distinct().size == pool.size) {
            "Clan quest pool must contain 3..64 distinct projects"
        }
    }

    fun select(clanId: String, week: LocalDate): List<ClanQuestDefinition> {
        ClanQuestCycle.validate(week)
        return pool.sortedBy { quest ->
            MessageDigest.getInstance("SHA-256").digest("$clanId|$week|${quest.id}".toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }.take(projectsPerWeek)
    }

    companion object {
        fun load(config: Config): ClanQuestCatalog {
            val minContributors = config.int("rules.minimum-contributors", 2)
            val capPercent = config.int("rules.contribution-cap-percent", 60)
            val renown = config.long("rewards.renown", 1)
            require(config.keys("rewards").all { it == "renown" }) { "Clan rewards may contain only nonredeemable renown" }
            val pool = config.keys("projects").filter { config.boolean("projects.$it.enabled", true) }.map { id ->
                val key = "projects.$id"
                require(config.keys(key).all { it in setOf("enabled", "text", "material", "mode", "steps") }) {
                    "Unsupported clan project property in $id"
                }
                val plan = QuestPlan(
                    QuestMode.valueOf(config.string("$key.mode", "all").uppercase(java.util.Locale.ROOT)),
                    config.keys("$key.steps").sortedBy(String::toInt).map { step ->
                        val prefix = "$key.steps.$step"
                        QuestStep(config.string("$prefix.objective"), config.long("$prefix.target"), config.string("$prefix.text"))
                    },
                )
                val material = config.string("$key.material")
                require(plan.steps.all { it.objective in ClanQuestDefinition.GROUP_OBJECTIVES }) {
                    "Clan project $id must use confirmed group combat objectives"
                }
                require(Material.matchMaterial(material) != null) { "Unknown clan project material in $id: $material" }
                ClanQuestDefinition(id, config.string("$key.text"), plan, material, minContributors, capPercent, renown)
            }
            return ClanQuestCatalog(pool, config.int("selection.projects-per-week", 3))
        }

        fun defaults(): ClanQuestCatalog = ClanQuestCatalog(listOf(
            project("elite_hunt", "IRON_SWORD", "clan.elite_kill" to 12L),
            project("boss_hunt", "WITHER_SKELETON_SKULL", "clan.boss_kill" to 1L),
            project("dungeon_run", "ENDER_EYE", "clan.dungeon_run" to 1L),
            project("arena_training", "SHIELD", "clan.arena_match" to 3L),
            project("combat_patrol", "CROSSBOW", "clan.elite_kill" to 8L, "clan.boss_kill" to 1L),
            project("battle_campaign", "NETHERITE_SWORD", "clan.arena_match" to 2L, "clan.dungeon_run" to 1L),
        ))

        private fun project(id: String, material: String, vararg steps: Pair<String, Long>) = ClanQuestDefinition(
            id, id, QuestPlan(QuestMode.ALL, steps.map { (objective, target) ->
                QuestStep(objective, target, objective.replace(':', '_').replace('.', '_'))
            }), material,
        )
    }
}
