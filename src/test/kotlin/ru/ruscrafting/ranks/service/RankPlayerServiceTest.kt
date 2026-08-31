package ru.ruscrafting.ranks.service

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.ranks.domain.MasteryLevel
import ru.ruscrafting.ranks.domain.MasteryThresholds
import ru.ruscrafting.ranks.domain.PathAvailability
import ru.ruscrafting.ranks.domain.PlayerProgressProfile
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.domain.ProgressSnapshot
import ru.ruscrafting.ranks.domain.RankCatalog
import ru.ruscrafting.ranks.domain.RankDefinition
import ru.ruscrafting.ranks.domain.RankEvaluator
import ru.ruscrafting.ranks.domain.RankId
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.perk.PerkId
import ru.ruscrafting.ranks.perk.PerkSelection
import ru.ruscrafting.ranks.progress.ProgressMutation
import ru.ruscrafting.ranks.rankstate.RankReplaceResult
import ru.ruscrafting.ranks.rankstate.RankState
import ru.ruscrafting.ranks.rankstate.RankStateGateway
import ru.ruscrafting.ranks.storage.ExternalProgressEvent
import ru.ruscrafting.ranks.storage.ExternalProgressResult
import ru.ruscrafting.ranks.storage.ProgressRepository
import java.util.UUID
import java.util.concurrent.CompletableFuture

class RankPlayerServiceTest : StringSpec({
    "late load cannot repopulate the cache after player invalidation" {
        val fixture = playerServiceFixture()

        val pendingLoad = fixture.service.load(fixture.playerId)
        fixture.cache.remove(fixture.playerId)
        fixture.completeLoad()

        pendingLoad.join() shouldBe testSnapshot()
        fixture.cache.get(fixture.playerId) shouldBe null
        fixture.cache.size() shouldBe 0
    }

    "late load cannot repopulate the cache after a global clear" {
        val fixture = playerServiceFixture()

        val pendingLoad = fixture.service.load(fixture.playerId)
        fixture.cache.clear()
        fixture.completeLoad()

        pendingLoad.join() shouldBe testSnapshot()
        fixture.cache.get(fixture.playerId) shouldBe null
        fixture.cache.size() shouldBe 0
    }

    "perk mutation wins over a stale reload warmup" {
        val fixture = playerServiceFixture()
        val oldPerk = PerkId("farming_momentum")
        val newPerk = PerkId("builder_precision")
        fixture.cache.put(
            fixture.playerId,
            testSnapshot().copy(activePerks = setOf(oldPerk), perkSlots = mapOf(1 to oldPerk)),
        )
        fixture.cache.invalidateSnapshots()

        val pendingLoad = fixture.service.load(fixture.playerId)
        fixture.cache.updatePerks(fixture.playerId, PerkSelection(listOf(newPerk)))
        fixture.completeLoad()

        pendingLoad.join() shouldBe testSnapshot()
        fixture.cache.get(fixture.playerId) shouldBe null
        fixture.cache.perkProgress(fixture.playerId) shouldBe PerkProgressContext(testProfile(), setOf(newPerk))
    }

    "select focus captures cache validity before its first async operation" {
        val fixture = playerServiceFixture()

        val pendingSelection = fixture.service.selectFocus(fixture.playerId, SpecializationPath.BUILDING)
        fixture.cache.remove(fixture.playerId)
        fixture.focusSelection.complete(Unit)
        fixture.completeLoad()

        pendingSelection.join() shouldBe testSnapshot()
        fixture.cache.get(fixture.playerId) shouldBe null
        fixture.cache.size() shouldBe 0
    }

    "load keeps evaluator and mastery thresholds from one configuration revision" {
        val playerId = UUID.randomUUID()
        val rankLoad = CompletableFuture<RankState>()
        val progressLoad = CompletableFuture<PlayerProgressProfile>()
        val perkLoad = CompletableFuture<PerkSelection>()
        val initialCatalog = testRankCatalog("ranks.initial.name")
        val reloadedCatalog = testRankCatalog("ranks.reloaded.name")
        var currentConfiguration = RankPlayerEvaluationConfiguration(
            evaluator = RankEvaluator(initialCatalog),
            masteryThresholds = SpecializationPath.entries.associateWith { MasteryThresholds(1, 2, 3) },
        )
        var configurationReads = 0
        val service = RankPlayerService(
            rankState = PendingRankStateGateway(rankLoad),
            progress = PendingProgressRepository(progressLoad, CompletableFuture.completedFuture(Unit)),
            evaluationConfiguration = {
                configurationReads++
                currentConfiguration
            },
            availability = PathAvailability::allAvailable,
            cache = RankSnapshotCache(),
            perks = { perkLoad },
        )

        val load = service.load(playerId)
        currentConfiguration = RankPlayerEvaluationConfiguration(
            evaluator = RankEvaluator(reloadedCatalog),
            masteryThresholds = SpecializationPath.entries.associateWith { MasteryThresholds(100, 200, 300) },
        )
        rankLoad.complete(RankState.Exact(RankId("settler")))
        progressLoad.complete(
            PlayerProgressProfile(
                ProgressSnapshot(mapOf(ProgressMetric.BLOCKS_PLACED to 10)),
                SpecializationPath.FARMING,
            ),
        )
        perkLoad.complete(PerkSelection.EMPTY)

        val snapshot = load.join()
        snapshot.evaluation?.currentRank?.displayNameKey shouldBe "ranks.initial.name"
        snapshot.mastery[SpecializationPath.BUILDING] shouldBe MasteryLevel.III
        configurationReads shouldBe 1
    }
})

class RankSnapshotCacheTest : StringSpec({
    "a current write token permits exactly the current cache generation" {
        val cache = RankSnapshotCache()
        val playerId = UUID.randomUUID()
        val token = cache.captureToken(playerId)

        cache.putIfCurrent(playerId, token, testSnapshot()) shouldBe true
        cache.get(playerId) shouldBe testSnapshot()
    }

    "remove invalidates the player token without blocking a later session" {
        val cache = RankSnapshotCache()
        val playerId = UUID.randomUUID()
        val staleToken = cache.captureToken(playerId)

        cache.remove(playerId)

        cache.putIfCurrent(playerId, staleToken, testSnapshot()) shouldBe false
        val currentToken = cache.captureToken(playerId)
        cache.putIfCurrent(playerId, currentToken, testSnapshot()) shouldBe true
        cache.get(playerId) shouldBe testSnapshot()
    }

    "clear invalidates every captured token and removes every snapshot" {
        val cache = RankSnapshotCache()
        val firstPlayer = UUID.randomUUID()
        val secondPlayer = UUID.randomUUID()
        cache.put(firstPlayer, testSnapshot())
        cache.put(secondPlayer, testSnapshot())
        val firstToken = cache.captureToken(firstPlayer)
        val secondToken = cache.captureToken(secondPlayer)

        cache.clear()

        cache.putIfCurrent(firstPlayer, firstToken, testSnapshot()) shouldBe false
        cache.putIfCurrent(secondPlayer, secondToken, testSnapshot()) shouldBe false
        cache.size() shouldBe 0

        val nextGeneration = cache.captureToken(firstPlayer)
        cache.putIfCurrent(firstPlayer, nextGeneration, testSnapshot()) shouldBe true
        cache.get(firstPlayer) shouldBe testSnapshot()
    }

    "snapshot invalidation preserves last-known perk progress context for reload" {
        val cache = RankSnapshotCache()
        val playerId = UUID.randomUUID()
        val perkId = PerkId("farming_momentum")
        val snapshot = testSnapshot().copy(activePerks = setOf(perkId), perkSlots = mapOf(1 to perkId))
        cache.put(playerId, snapshot)
        val staleToken = cache.captureToken(playerId)

        cache.invalidateSnapshots()

        cache.get(playerId) shouldBe null
        cache.perkProgress(playerId) shouldBe PerkProgressContext(snapshot.profile, setOf(perkId))
        cache.putIfCurrent(playerId, staleToken, snapshot) shouldBe false
    }

    "full clear removes preserved perk progress context" {
        val cache = RankSnapshotCache()
        val playerId = UUID.randomUUID()
        cache.put(playerId, testSnapshot())

        cache.clear()

        cache.perkProgress(playerId) shouldBe null
    }
})

private data class RankPlayerServiceFixture(
    val playerId: UUID,
    val cache: RankSnapshotCache,
    val rankLoad: CompletableFuture<RankState>,
    val progressLoad: CompletableFuture<PlayerProgressProfile>,
    val perkLoad: CompletableFuture<PerkSelection>,
    val focusSelection: CompletableFuture<Unit>,
    val service: RankPlayerService,
) {
    fun completeLoad() {
        rankLoad.complete(RankState.Missing)
        progressLoad.complete(testProfile())
        perkLoad.complete(PerkSelection.EMPTY)
    }
}

private fun playerServiceFixture(): RankPlayerServiceFixture {
    val playerId = UUID.randomUUID()
    val cache = RankSnapshotCache()
    val rankLoad = CompletableFuture<RankState>()
    val progressLoad = CompletableFuture<PlayerProgressProfile>()
    val perkLoad = CompletableFuture<PerkSelection>()
    val focusSelection = CompletableFuture<Unit>()
    val progress = PendingProgressRepository(progressLoad, focusSelection)
    val service = RankPlayerService(
        rankState = PendingRankStateGateway(rankLoad),
        progress = progress,
        evaluator = RankEvaluator(testRankCatalog()),
        masteryThresholds = SpecializationPath.entries.associateWith { MasteryThresholds(1, 2, 3) },
        availability = PathAvailability::allAvailable,
        cache = cache,
        perks = { perkLoad },
    )
    return RankPlayerServiceFixture(
        playerId = playerId,
        cache = cache,
        rankLoad = rankLoad,
        progressLoad = progressLoad,
        perkLoad = perkLoad,
        focusSelection = focusSelection,
        service = service,
    )
}

private fun testSnapshot() = RankPlayerSnapshot(
    rankState = RankState.Missing,
    profile = testProfile(),
    evaluation = null,
    mastery = SpecializationPath.entries.associateWith { MasteryLevel.NONE },
    availability = PathAvailability.allAvailable(),
    activePerks = emptySet<PerkId>(),
    perkSlots = emptyMap(),
)

private fun testProfile() = PlayerProgressProfile(ProgressSnapshot.EMPTY, SpecializationPath.FARMING)

private fun testRankCatalog(displayNameKey: String = "ranks.settler.name") = RankCatalog(
    listOf(
        RankDefinition(
            id = RankId("settler"),
            luckPermsGroup = "default",
            order = 1,
            displayNameKey = displayNameKey,
            activeMinutesRequired = 0,
            requiredChoices = 0,
            pathGoals = SpecializationPath.entries.associateWith { 0L },
            benefitKeys = listOf("ranks.settler.benefit"),
        ),
    ),
)

private class PendingRankStateGateway(
    private val pendingLoad: CompletableFuture<RankState>,
) : RankStateGateway {
    override fun load(playerId: UUID): CompletableFuture<RankState> = pendingLoad

    override fun replaceExact(
        playerId: UUID,
        expected: RankId,
        target: RankId,
    ): CompletableFuture<RankReplaceResult> = error("Rank replacement is not used by this fixture")
}

private class PendingProgressRepository(
    private val pendingLoad: CompletableFuture<PlayerProgressProfile>,
    private val pendingFocusSelection: CompletableFuture<Unit>,
) : ProgressRepository {
    override fun initialize(): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)

    override fun load(playerId: UUID): CompletableFuture<PlayerProgressProfile> = pendingLoad

    override fun applyMutations(
        playerId: UUID,
        mutations: List<ProgressMutation>,
    ): CompletableFuture<Unit> = error("Progress mutation is not used by this fixture")

    override fun selectFocus(playerId: UUID, path: SpecializationPath): CompletableFuture<Unit> = pendingFocusSelection

    override fun recordExternalEvent(event: ExternalProgressEvent): CompletableFuture<ExternalProgressResult> =
        error("External progress events are not used by this fixture")
}
