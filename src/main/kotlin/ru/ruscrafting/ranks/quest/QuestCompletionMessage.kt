package ru.ruscrafting.ranks.quest

import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import ru.ruscrafting.ranks.contract.ContractRewardComponent
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.reward.RankReward
import ru.ruscrafting.ranks.text.RankLocale

/** One chat packet: real blank boundary lines and two-space content insets. */
object QuestCompletionMessage {
    fun render(locale: RankLocale, audience: CommandSender?, reward: RankReward): Component {
        val summary = reward.questSummary
        val money = reward.components.filterIsInstance<ContractRewardComponent.Money>().sumOf { it.amount }
        val tokens = reward.components.filterIsInstance<ContractRewardComponent.Tokens>().sumOf { it.amount }
        val values = mutableMapOf("money" to locale.text(money), "tokens" to locale.text(tokens))
        summary?.let { values["quest-name"] = locale.render("daily.${it.textId}.name", audience) }
        var payout = locale.render("daily.notification.${if (tokens > 0) "reward-rare" else "reward"}", audience, values)
        if (summary != null && summary.bonus > 0) {
            val path = SpecializationPath.entries.firstOrNull { it.owns(summary.metric) }
            if (path != null) payout = payout.append(locale.render("daily.notification.path", audience,
                mapOf("bonus" to locale.text(summary.bonus), "path" to locale.render("daily.notification.paths.${path.name.lowercase()}", audience))))
        }
        return Component.newline()
            .append(Component.text("  "))
            .append(locale.render("daily.notification.${if (summary == null) "legacy-title" else "title"}", audience, values))
            .append(Component.newline()).append(Component.text("  ")).append(payout)
            .append(Component.newline())
    }
}
