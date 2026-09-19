package ru.ruscrafting.ranks.reward

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.onetime.OneTimeUseAbandonResult
import ru.arc.onetime.OneTimeUseClaim
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseCommitResult
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.onetime.OneTimeUseReleaseResult
import ru.ruscrafting.ranks.contract.ContractRewardApplyResult
import ru.ruscrafting.ranks.contract.ContractRewardComponent
import ru.ruscrafting.ranks.contract.ContractRewardDeliveryResult
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

class RankRewardDeliveryServiceTest : StringSpec({
    "applied money and tokens commit independently before granting the reward" {
        val fixture = fixture()
        val reward = RankReward(
            "daily-success",
            "daily",
            listOf(ContractRewardComponent.Money(250), ContractRewardComponent.Tokens(3, "tokens")),
        )

        val result = fixture.service.deliver(fixture.player, reward)
        drain(fixture.scheduler)

        result.join() shouldBe ContractRewardDeliveryResult.GRANTED
        fixture.provider.applied shouldContainExactly reward.components
        fixture.ledger.commits.size shouldBe 2
        fixture.repository.granted shouldBe listOf(reward.id)
        fixture.granted shouldBe listOf(reward.id)
    }

    "already consumed skips the provider and still finalizes the reward" {
        val fixture = fixture()
        val reward = reward("already-consumed")
        fixture.ledger.claimHandler = { OneTimeUseClaimResult.AlreadyConsumed }

        val result = fixture.service.deliver(fixture.player, reward)
        drain(fixture.scheduler)

        result.join() shouldBe ContractRewardDeliveryResult.GRANTED
        fixture.provider.applied shouldBe emptyList()
        fixture.ledger.commits shouldBe emptyList()
        fixture.repository.granted shouldBe listOf(reward.id)
    }

    "provider rejection releases the claim and a later delivery retries it" {
        val fixture = fixture()
        val reward = reward("retry")
        var attempts = 0
        fixture.provider.handler = { _, component ->
            fixture.provider.applied += component
            attempts++
            if (attempts == 1) ContractRewardApplyResult.REJECTED else ContractRewardApplyResult.APPLIED
        }

        val first = fixture.service.deliver(fixture.player, reward)
        drain(fixture.scheduler)
        first.join() shouldBe ContractRewardDeliveryResult.PENDING
        fixture.ledger.releases.size shouldBe 1
        fixture.repository.granted shouldBe emptyList()

        val second = fixture.service.deliver(fixture.player, reward)
        drain(fixture.scheduler)
        second.join() shouldBe ContractRewardDeliveryResult.GRANTED
        fixture.ledger.commits.size shouldBe 1
        fixture.repository.granted shouldBe listOf(reward.id)
    }

    "effect failure abandons the claim for recovery and never applies the next component" {
        val fixture = fixture()
        val reward = RankReward(
            "effect-unknown",
            "daily",
            listOf(ContractRewardComponent.Money(100), ContractRewardComponent.Tokens(1, "tokens")),
        )
        fixture.provider.handler = { _, _ -> error("provider outcome unknown") }

        val result = fixture.service.deliver(fixture.player, reward)
        drain(fixture.scheduler)

        result.join() shouldBe ContractRewardDeliveryResult.RECOVERY
        fixture.provider.applied.size shouldBe 0
        fixture.ledger.abandons.size shouldBe 1
        fixture.repository.recovery shouldBe listOf(reward.id to "effect_unknown")
        fixture.repository.granted shouldBe emptyList()
    }

    "a recovered claim is abandoned into recovery without applying the provider" {
        val fixture = fixture()
        val reward = reward("claim-recovered")
        fixture.ledger.claimHandler = { request ->
            OneTimeUseClaimResult.Acquired(OneTimeUseClaim.acquired(request, newlyCreated = false))
        }

        val result = fixture.service.deliver(fixture.player, reward)
        drain(fixture.scheduler)

        result.join() shouldBe ContractRewardDeliveryResult.RECOVERY
        fixture.provider.applied shouldBe emptyList()
        fixture.ledger.abandons.size shouldBe 1
        fixture.repository.recovery shouldBe listOf(reward.id to "claim_recovered")
    }

    "an ambiguous commit resolved as already committed completes without a second effect" {
        val fixture = fixture()
        val reward = reward("commit-resolved")
        fixture.ledger.commitHandler = { CompletableFuture.failedFuture(IllegalStateException("commit timeout")) }
        fixture.ledger.abandonHandler = { CompletableFuture.completedFuture(OneTimeUseAbandonResult.ALREADY_COMMITTED) }

        val result = fixture.service.deliver(fixture.player, reward)
        drain(fixture.scheduler)

        result.join() shouldBe ContractRewardDeliveryResult.GRANTED
        fixture.provider.applied shouldBe reward.components
        fixture.ledger.abandons.size shouldBe 1
        fixture.repository.granted shouldBe listOf(reward.id)
        fixture.repository.recovery shouldBe emptyList()
    }

    "overlapping pending deliveries drain the queued request without losing notifications" {
        val fixture = fixture()
        val first = reward("pending-one")
        val second = reward("pending-two")
        val firstLoad = CompletableFuture<List<RankReward>>()
        var pendingCalls = 0
        fixture.repository.pendingHandler = {
            pendingCalls++
            if (pendingCalls == 1) firstLoad else CompletableFuture.completedFuture(listOf(second))
        }

        fixture.service.deliverPending(fixture.player)
        fixture.service.deliverPending(fixture.player)
        pendingCalls shouldBe 1

        firstLoad.complete(listOf(first))
        drain(fixture.scheduler)

        pendingCalls shouldBe 2
        fixture.granted shouldContainExactly listOf(first.id, second.id)
        fixture.repository.granted shouldContainExactly listOf(first.id, second.id)
    }
})

private class DeliveryFixture(
    val scheduler: TestTaskScheduler,
    val player: Player,
    val repository: FakeRewardRepository,
    val ledger: FakeRewardLedger,
    val provider: FakeRewardProvider,
    val granted: MutableList<String>,
    val service: RankRewardDeliveryService,
)

private fun fixture(): DeliveryFixture {
    val scheduler = TestTaskScheduler()
    val tasks = LifecycleTaskScope(scheduler)
    val playerId = UUID.randomUUID()
    val player = mockk<Player> {
        every { uniqueId } returns playerId
        every { isOnline } returns true
    }
    val repository = FakeRewardRepository()
    val ledger = FakeRewardLedger()
    val provider = FakeRewardProvider()
    val granted = mutableListOf<String>()
    val service = RankRewardDeliveryService(
        repository,
        ledger,
        provider,
        tasks,
        Logger.getLogger("RankRewardDeliveryServiceTest"),
    ) { _, reward -> granted += reward.id }
    return DeliveryFixture(scheduler, player, repository, ledger, provider, granted, service)
}

private fun reward(id: String) = RankReward(id, "daily", listOf(ContractRewardComponent.Money(10)))

private fun drain(scheduler: TestTaskScheduler) {
    repeat(32) {
        if (scheduler.pendingCount() == 0) return
        scheduler.executeImmediate()
    }
    check(scheduler.pendingCount() == 0) { "delivery callback queue did not drain" }
}

private class FakeRewardRepository : RankRewardRepository {
    var pendingHandler: (UUID) -> CompletableFuture<List<RankReward>> = {
        CompletableFuture.completedFuture(emptyList())
    }
    val granted = mutableListOf<String>()
    val recovery = mutableListOf<Pair<String, String>>()

    override fun pendingRewards(playerId: UUID) = pendingHandler(playerId)

    override fun markRewardGranted(playerId: UUID, rewardId: String) =
        CompletableFuture.completedFuture(true).also { granted += rewardId }

    override fun markRewardRecovery(playerId: UUID, rewardId: String, failureCode: String) =
        CompletableFuture.completedFuture(true).also { recovery += rewardId to failureCode }
}

private class FakeRewardLedger : OneTimeUseLedger {
    var claimHandler: (OneTimeUseClaimRequest) -> OneTimeUseClaimResult = { request ->
        OneTimeUseClaimResult.Acquired(OneTimeUseClaim.acquired(request, newlyCreated = true))
    }
    var commitHandler: (OneTimeUseClaim) -> CompletableFuture<OneTimeUseCommitResult> = {
        CompletableFuture.completedFuture(OneTimeUseCommitResult.COMMITTED)
    }
    var releaseHandler: (OneTimeUseClaim) -> CompletableFuture<OneTimeUseReleaseResult> = {
        CompletableFuture.completedFuture(OneTimeUseReleaseResult.RELEASED)
    }
    var abandonHandler: (OneTimeUseClaim) -> CompletableFuture<OneTimeUseAbandonResult> = {
        CompletableFuture.completedFuture(OneTimeUseAbandonResult.RETAINED_FOR_RECOVERY)
    }
    val claims = mutableListOf<OneTimeUseClaimRequest>()
    val commits = mutableListOf<OneTimeUseClaim>()
    val releases = mutableListOf<OneTimeUseClaim>()
    val abandons = mutableListOf<OneTimeUseClaim>()

    override fun claim(request: OneTimeUseClaimRequest) =
        CompletableFuture.completedFuture(claimHandler(request)).also { claims += request }

    override fun commit(claim: OneTimeUseClaim) = commitHandler(claim).also { commits += claim }

    override fun release(claim: OneTimeUseClaim) = releaseHandler(claim).also { releases += claim }

    override fun abandon(claim: OneTimeUseClaim) = abandonHandler(claim).also { abandons += claim }
}

private class FakeRewardProvider : ru.ruscrafting.ranks.contract.ContractRewardProvider {
    val applied = mutableListOf<ContractRewardComponent>()
    var handler: (Player, ContractRewardComponent) -> ContractRewardApplyResult = { _, component ->
        applied += component
        ContractRewardApplyResult.APPLIED
    }

    override fun apply(player: Player, component: ContractRewardComponent) = handler(player, component)
}
