package ru.ruscrafting.ranks.kit

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.ruscrafting.ranks.config.GuiItemSpec
import ru.ruscrafting.ranks.domain.RankId

class CmiWeeklyKitProviderTest : FreeSpec({
    "native CMI delivery completes only after the main-thread callback runs" {
        val scheduler = TestTaskScheduler()
        val provider = CmiWeeklyKitProvider(LifecycleTaskScope(scheduler)) { kitId, playerName ->
            kitId == "arcranks_weekly_settler" && playerName == "GrocerMC"
        }

        val result = provider.deliver(definition(), "GrocerMC")
        result.isDone shouldBe false

        scheduler.executeImmediate()
        result.join() shouldBe true
    }

    "native CMI rejection remains retryable" {
        val scheduler = TestTaskScheduler()
        val provider = CmiWeeklyKitProvider(LifecycleTaskScope(scheduler)) { _, _ -> false }

        val result = provider.deliver(definition(), "GrocerMC")
        scheduler.executeImmediate()

        result.join() shouldBe false
    }
}) {
    companion object {
        private fun definition() = WeeklyKitDefinition(
            rankId = RankId("settler"),
            kitId = "arcranks_weekly_settler",
            minimumFreeSlots = 3,
            contentLines = 2,
            icon = GuiItemSpec("CHEST", 0),
        )
    }
}
