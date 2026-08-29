package ru.ruscrafting.ranks.promotion

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.ranks.domain.PathAvailability
import ru.ruscrafting.ranks.domain.PlayerProgressProfile
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.domain.ProgressSnapshot
import ru.ruscrafting.ranks.domain.RankCatalog
import ru.ruscrafting.ranks.domain.RankDefinition
import ru.ruscrafting.ranks.domain.RankEvaluator
import ru.ruscrafting.ranks.domain.RankId
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.progress.ProgressBuffer
import ru.ruscrafting.ranks.progress.ProgressMutation
import ru.ruscrafting.ranks.rankstate.RankReplaceResult
import ru.ruscrafting.ranks.rankstate.RankState
import ru.ruscrafting.ranks.rankstate.RankStateGateway
import ru.ruscrafting.ranks.storage.ExternalProgressEvent
import ru.ruscrafting.ranks.storage.ExternalProgressResult
import ru.ruscrafting.ranks.storage.ProgressRepository
import java.util.UUID
import java.util.concurrent.CompletableFuture

class PromotionServiceTest : StringSpec({
    "eligible promotion completes saga before celebration" {
        val fixture = fixture(readyProgress())

        fixture.service.promote(fixture.player).join() shouldBe PromotionResult.Promoted(RankId("peasant"))
        fixture.gateway.state shouldBe RankState.Exact(RankId("peasant"))
        fixture.promotions.active(fixture.player).join() shouldBe null
        fixture.promotions.history shouldBe listOf(PromotionState.PREPARED, PromotionState.APPLIED, PromotionState.COMPLETED)
        fixture.celebrations shouldBe listOf(RankId("peasant"))
    }

    "requirements are evaluated without preparing a saga" {
        val fixture = fixture(ProgressSnapshot.EMPTY)

        val result = fixture.service.promote(fixture.player).join()

        (result is PromotionResult.NotEligible) shouldBe true
        fixture.promotions.history shouldBe emptyList()
        fixture.gateway.mutations shouldBe 0
        fixture.celebrations shouldBe emptyList()
    }

    "conflicting direct rank parents fail closed" {
        val fixture = fixture(readyProgress())
        fixture.gateway.state = RankState.Conflict(listOf("default", "pesant"))

        fixture.service.promote(fixture.player).join() shouldBe
            PromotionResult.RankStateProblem(RankState.Conflict(listOf("default", "pesant")))
        fixture.promotions.history shouldBe emptyList()
    }

    "already applied active saga is recovered without a second LuckPerms mutation or celebration" {
        val fixture = fixture(readyProgress())
        val saga = fixture.promotions.seed(fixture.player, RankId("settler"), RankId("peasant"), PromotionState.APPLIED)
        fixture.gateway.state = RankState.Exact(RankId("peasant"))

        fixture.service.promote(fixture.player).join() shouldBe PromotionResult.Recovered(RankId("peasant"))
        fixture.promotions.active(fixture.player).join() shouldBe null
        fixture.promotions.history shouldBe listOf(PromotionState.APPLIED, PromotionState.COMPLETED)
        fixture.gateway.mutations shouldBe 0
        fixture.celebrations shouldBe emptyList()
        saga.generation shouldBe 1
    }

    "failed LuckPerms apply remains retryable and never celebrates" {
        val fixture = fixture(readyProgress())
        fixture.gateway.replaceResult = RankReplaceResult.VERIFICATION_FAILED

        fixture.service.promote(fixture.player).join() shouldBe PromotionResult.Retryable
        fixture.promotions.active(fixture.player).join()?.state shouldBe PromotionState.RETRYABLE
        fixture.celebrations shouldBe emptyList()
    }
})

private data class PromotionFixture(
    val player: UUID,
    val gateway: FakeRankGateway,
    val promotions: MemoryPromotionRepository,
    val celebrations: MutableList<RankId>,
    val service: PromotionService,
)

private fun fixture(progress: ProgressSnapshot): PromotionFixture {
    val player = UUID.randomUUID()
    val catalog = promotionCatalog()
    val progressRepository = MemoryProgressRepository(PlayerProgressProfile(progress, SpecializationPath.FARMING))
    val buffer = ProgressBuffer(16) { id, mutations -> progressRepository.applyMutations(id, mutations) }
    val gateway = FakeRankGateway()
    val promotions = MemoryPromotionRepository()
    val celebrations = mutableListOf<RankId>()
    val service = PromotionService(
        catalog = catalog,
        evaluator = RankEvaluator(catalog),
        progress = progressRepository,
        buffer = buffer,
        rankState = gateway,
        promotions = promotions,
        availability = { PathAvailability.allAvailable() },
        celebrate = { _, rank -> celebrations += rank },
    )
    return PromotionFixture(player, gateway, promotions, celebrations, service)
}

private fun readyProgress(): ProgressSnapshot = ProgressSnapshot(
    mapOf(
        ProgressMetric.ACTIVE_MINUTES to 60,
        ProgressMetric.CROPS_HARVESTED to 10,
        ProgressMetric.BLOCKS_PLACED to 10,
    ),
)

private fun promotionCatalog(): RankCatalog = RankCatalog(
    listOf(
        promotionRank("settler", "default", 1, 0, 0),
        promotionRank("peasant", "pesant", 2, 60, 2),
    ),
)

private fun promotionRank(id: String, group: String, order: Int, active: Long, choices: Int) = RankDefinition(
    RankId(id), group, order, "ranks.$id.name", active, choices,
    SpecializationPath.entries.associateWith { 10L }, listOf("ranks.$id.benefit"),
)

private class FakeRankGateway : RankStateGateway {
    var state: RankState = RankState.Exact(RankId("settler"))
    var replaceResult = RankReplaceResult.APPLIED
    var mutations = 0

    override fun load(playerId: UUID): CompletableFuture<RankState> = CompletableFuture.completedFuture(state)

    override fun replaceExact(playerId: UUID, expected: RankId, target: RankId): CompletableFuture<RankReplaceResult> {
        mutations++
        if (replaceResult == RankReplaceResult.APPLIED) state = RankState.Exact(target)
        return CompletableFuture.completedFuture(replaceResult)
    }
}

private class MemoryProgressRepository(initial: PlayerProgressProfile) : ProgressRepository {
    private var profile = initial
    override fun initialize() = CompletableFuture.completedFuture(Unit)
    override fun load(playerId: UUID) = CompletableFuture.completedFuture(profile)
    override fun applyMutations(playerId: UUID, mutations: List<ProgressMutation>): CompletableFuture<Unit> =
        CompletableFuture.completedFuture(Unit)
    override fun selectFocus(playerId: UUID, path: SpecializationPath): CompletableFuture<Unit> {
        profile = profile.selectFocus(path)
        return CompletableFuture.completedFuture(Unit)
    }
    override fun recordExternalEvent(event: ExternalProgressEvent) =
        CompletableFuture.completedFuture(ExternalProgressResult.APPLIED)
}

private class MemoryPromotionRepository : PromotionRepository {
    private val states = mutableMapOf<UUID, PromotionSaga>()
    val history = mutableListOf<PromotionState>()

    override fun active(playerId: UUID) = CompletableFuture.completedFuture(states[playerId]?.takeUnless { it.state == PromotionState.COMPLETED })

    override fun prepare(playerId: UUID, from: RankId, target: RankId): CompletableFuture<PromotionPrepareResult> {
        val existing = states[playerId]
        if (existing != null && existing.state != PromotionState.COMPLETED) {
            return CompletableFuture.completedFuture(PromotionPrepareResult.Ready(existing, resumed = true))
        }
        val saga = PromotionSaga(playerId, (existing?.generation ?: 0) + 1, from, target, PromotionState.PREPARED)
        states[playerId] = saga
        history += PromotionState.PREPARED
        return CompletableFuture.completedFuture(PromotionPrepareResult.Ready(saga, resumed = false))
    }

    override fun transition(saga: PromotionSaga, expected: PromotionState, target: PromotionState): CompletableFuture<Boolean> {
        val current = states[saga.playerId]
        if (current?.generation != saga.generation || current.state != expected) return CompletableFuture.completedFuture(false)
        states[saga.playerId] = current.copy(state = target)
        history += target
        return CompletableFuture.completedFuture(true)
    }

    fun seed(player: UUID, from: RankId, target: RankId, state: PromotionState): PromotionSaga {
        val saga = PromotionSaga(player, 1, from, target, state)
        states[player] = saga
        history += state
        return saga
    }
}
