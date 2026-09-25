package ru.ruscrafting.ranks.service

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import ru.ruscrafting.ranks.config.RankReminderSettings
import ru.ruscrafting.ranks.domain.MasteryLevel
import ru.ruscrafting.ranks.domain.NextStep
import ru.ruscrafting.ranks.domain.PathAvailability
import ru.ruscrafting.ranks.domain.PlayerProgressProfile
import ru.ruscrafting.ranks.domain.ProgressSnapshot
import ru.ruscrafting.ranks.domain.RankDefinition
import ru.ruscrafting.ranks.domain.RankEligibility
import ru.ruscrafting.ranks.domain.RankEvaluation
import ru.ruscrafting.ranks.domain.RankId
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.perk.PerkId
import ru.ruscrafting.ranks.rankstate.RankState
import ru.ruscrafting.ranks.text.RankLocale

class RankReminderServiceTest : StringSpec({
    "joining with an already available rank stays silent past the cooldown" {
        val clock = MutableReminderClock()
        val schedule = RankReminderSchedule(clock) { RankReminderSettings(60, 1_800) }
        val playerId = UUID.randomUUID()
        schedule.join(playerId)

        schedule.due().shouldBeEmpty()
        clock.advanceSeconds(59)
        schedule.due().shouldBeEmpty()
        clock.advanceSeconds(1)
        val first = schedule.due().single()
        schedule.due().shouldBeEmpty()
        schedule.complete(
            first,
            RankReminderObservation(RankId("pioneer"), ready = true),
            success = true,
        ).notify shouldBe false

        clock.advanceSeconds(59)
        schedule.due().shouldBeEmpty()
        clock.advanceSeconds(1)
        val repeated = schedule.due().single()
        schedule.complete(
            repeated,
            RankReminderObservation(RankId("pioneer"), ready = true),
            success = true,
        ).notify shouldBe false

        clock.advanceSeconds(1_799)
        schedule.complete(
            schedule.due().single(),
            RankReminderObservation(RankId("pioneer"), ready = true),
            success = true,
        ).notify shouldBe false
    }

    "quit and rejoin fence an old asynchronous completion" {
        val clock = MutableReminderClock()
        val schedule = RankReminderSchedule(clock) { RankReminderSettings(60, 1_800) }
        val playerId = UUID.randomUUID()
        schedule.join(playerId)
        clock.advanceSeconds(60)
        val oldRequest = schedule.due().single()

        schedule.quit(playerId)
        schedule.join(playerId)
        schedule.complete(
            oldRequest,
            RankReminderObservation(RankId("pioneer"), ready = true),
            success = true,
        ).accepted shouldBe false

        clock.advanceSeconds(59)
        schedule.due().shouldBeEmpty()
        clock.advanceSeconds(1)
        schedule.due().shouldHaveSize(1)
    }

    "failed state retries after the join delay without making the request hot" {
        val clock = MutableReminderClock()
        val schedule = RankReminderSchedule(clock) { RankReminderSettings(60, 1_800) }
        val playerId = UUID.randomUUID()
        schedule.join(playerId)
        clock.advanceSeconds(60)
        val request = schedule.due().single()
        schedule.complete(request, observation = null, success = false).accepted shouldBe true

        schedule.due().shouldBeEmpty()
        clock.advanceSeconds(60)
        schedule.due().shouldHaveSize(1)
    }

    "newly available rank is announced after a silent baseline and repeats are bounded" {
        val clock = MutableReminderClock()
        val schedule = RankReminderSchedule(clock) { RankReminderSettings(60, 1_800) }
        val playerId = UUID.randomUUID()
        schedule.join(playerId)
        clock.advanceSeconds(60)
        val progress = schedule.due().single()
        schedule.complete(
            progress,
            RankReminderObservation(RankId("pioneer"), ready = false),
            success = true,
        ).notify shouldBe false

        clock.advanceSeconds(60)
        val ready = schedule.due().single()
        schedule.complete(
            ready,
            RankReminderObservation(RankId("pioneer"), ready = true),
            success = true,
        ).notify shouldBe true

        clock.advanceSeconds(60)
        schedule.complete(
            schedule.due().single(),
            RankReminderObservation(RankId("pioneer"), ready = true),
            success = true,
        ).notify shouldBe false

        clock.advanceSeconds(1_740)
        schedule.complete(
            schedule.due().single(),
            RankReminderObservation(RankId("pioneer"), ready = true),
            success = true,
        ).notify shouldBe true
    }

    "incomplete progress never sends a personal hint even after cooldown or reload" {
        val clock = MutableReminderClock()
        val schedule = RankReminderSchedule(clock) { RankReminderSettings(60, 1_800) }
        val playerId = UUID.randomUUID()
        schedule.join(playerId)
        for (delay in listOf(60L, 1_800L)) {
            clock.advanceSeconds(delay)
            schedule.complete(
                schedule.due().single(),
                RankReminderObservation(RankId("pioneer"), ready = false),
                success = true,
            ).notify shouldBe false
        }
        schedule.reload()
        clock.advanceSeconds(60)
        schedule.complete(
            schedule.due().single(),
            RankReminderObservation(RankId("pioneer"), ready = false),
            success = true,
        ).notify shouldBe false
    }

    "reload fences old work and applies the fresh first-delay window" {
        val clock = MutableReminderClock()
        val schedule = RankReminderSchedule(clock) { RankReminderSettings(60, 1_800) }
        val playerId = UUID.randomUUID()
        schedule.join(playerId)
        clock.advanceSeconds(60)
        val oldRequest = schedule.due().single()

        schedule.reload()
        schedule.complete(
            oldRequest,
            RankReminderObservation(RankId("pioneer"), ready = true),
            success = true,
        ).accepted shouldBe false
        schedule.due().shouldBeEmpty()
        clock.advanceSeconds(59)
        schedule.due().shouldBeEmpty()
        clock.advanceSeconds(1)
        schedule.due().shouldHaveSize(1)
    }

    "composer only renders the localized clickable rank-ready action" {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>(relaxed = true)
        every { player.uniqueId } returns playerId
        val locale = mockk<RankLocale>(relaxed = true)
        var path: String? = null
        var values: Map<String, Component>? = null
        every { locale.chat(any<String>(), any<CommandSender>(), any<Map<String, Component>>()) } answers {
            path = firstArg()
            values = thirdArg()
            Component.empty()
        }
        every { locale.render(any<String>(), any<CommandSender>()) } returns Component.text("Pioneer")
        val composer = RankReminderMessageComposer { locale }

        composer.compose(player, reminderSnapshot(RankEligibility.READY, null))
        path shouldBe "reminders.rank.ready"
        values!!.getValue("action").clickEvent()?.action() shouldBe ClickEvent.Action.RUN_COMMAND
        values!!.getValue("action").clickEvent()?.value() shouldBe "/rank"

        composer.compose(
            player,
            reminderSnapshot(RankEligibility.CHOICES_INCOMPLETE, NextStep.PathGoal(SpecializationPath.FARMING, 3)),
        ) shouldBe null
    }
})

private fun reminderSnapshot(eligibility: RankEligibility, recommendation: NextStep?): RankPlayerSnapshot {
    val current = reminderRank("settler", 1, "ranks.settler.name")
    val next = reminderRank("pioneer", 2, "ranks.pioneer.name")
    return RankPlayerSnapshot(
        rankState = RankState.Exact(current.id),
        profile = PlayerProgressProfile(ProgressSnapshot.EMPTY, SpecializationPath.FARMING),
        evaluation = RankEvaluation(
            currentRank = current,
            nextRank = next,
            eligibility = eligibility,
            activeMinutesCurrent = 0,
            activeMinutesRequired = 0,
            completedChoices = 0,
            availableChoices = SpecializationPath.entries.size,
            requiredChoices = 1,
            goals = emptyList(),
            recommendation = recommendation,
        ),
        mastery = SpecializationPath.entries.associateWith { MasteryLevel.NONE },
        availability = PathAvailability.allAvailable(),
        activePerks = emptySet<PerkId>(),
    )
}

private fun reminderRank(id: String, order: Int, displayNameKey: String) = RankDefinition(
    id = RankId(id),
    luckPermsGroup = id,
    order = order,
    displayNameKey = displayNameKey,
    activeMinutesRequired = 0,
    requiredChoices = 0,
    pathGoals = SpecializationPath.entries.associateWith { 0L },
    benefitKeys = listOf("$displayNameKey.benefit"),
)

private class MutableReminderClock(
    private var current: Instant = Instant.EPOCH,
) : Clock() {
    override fun instant(): Instant = current
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId): Clock = this

    fun advanceSeconds(seconds: Long) {
        current = current.plus(Duration.ofSeconds(seconds))
    }
}
