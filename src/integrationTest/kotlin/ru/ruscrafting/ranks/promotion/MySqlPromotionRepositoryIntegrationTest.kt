package ru.ruscrafting.ranks.promotion

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlRuntime
import ru.arc.sql.SqlSslMode
import ru.arc.testing.containers.MySqlTestService
import ru.arc.testing.containers.MySqlTestSettings
import ru.ruscrafting.ranks.domain.RankId
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MySqlPromotionRepositoryIntegrationTest : StringSpec({
    "one saga per player resumes, rejects conflicts, and advances generations" {
        MySqlTestService.start(MySqlTestSettings(database = "arc_ranks_saga_test")).use { mysql ->
            val endpoint = mysql.endpoint
            SqlRuntime.create(
                SqlConnectionConfig(
                    endpoint.host, endpoint.port, endpoint.database, endpoint.username, endpoint.password,
                    sslMode = SqlSslMode.DISABLED, minimumIdle = 0, maximumPoolSize = 2,
                ),
                "arc-ranks-saga-test",
            ).use { runtime ->
                val repository = MySqlPromotionRepository(runtime)
                repository.initialize().join()
                val player = UUID.randomUUID()

                val first = repository.prepare(player, RankId("settler"), RankId("peasant")).join()
                    as PromotionPrepareResult.Ready
                first.resumed shouldBe false
                first.saga.generation shouldBe 1
                repository.prepare(player, RankId("settler"), RankId("peasant")).join() shouldBe
                    PromotionPrepareResult.Ready(first.saga, resumed = true)
                (repository.prepare(player, RankId("settler"), RankId("citizen")).join()
                    is PromotionPrepareResult.Conflict) shouldBe true

                repository.transition(first.saga, PromotionState.PREPARED, PromotionState.APPLIED).join() shouldBe true
                repository.transition(first.saga, PromotionState.PREPARED, PromotionState.COMPLETED).join() shouldBe false
                val applied = first.saga.copy(state = PromotionState.APPLIED)
                repository.transition(applied, PromotionState.APPLIED, PromotionState.COMPLETED).join() shouldBe true
                repository.active(player).join() shouldBe null

                val second = repository.prepare(player, RankId("peasant"), RankId("citizen")).join()
                    as PromotionPrepareResult.Ready
                second.saga.generation shouldBe 2
                second.saga.state shouldBe PromotionState.PREPARED
                repository.transition(second.saga, PromotionState.PREPARED, PromotionState.RETRYABLE).join() shouldBe true
                val retryable = second.saga.copy(state = PromotionState.RETRYABLE)
                repository.transition(retryable, PromotionState.RETRYABLE, PromotionState.APPLIED).join() shouldBe true
                val appliedAgain = second.saga.copy(state = PromotionState.APPLIED)
                repository.transition(appliedAgain, PromotionState.APPLIED, PromotionState.RETRYABLE).join() shouldBe true
                repository.transition(retryable, PromotionState.RETRYABLE, PromotionState.APPLIED).join() shouldBe true

                val concurrentPlayer = UUID.randomUUID()
                val attempts = listOf(
                    repository.prepare(concurrentPlayer, RankId("settler"), RankId("peasant")),
                    repository.prepare(concurrentPlayer, RankId("settler"), RankId("peasant")),
                )
                CompletableFuture.allOf(*attempts.toTypedArray()).join()
                attempts.map { it.join() }.all { it is PromotionPrepareResult.Ready } shouldBe true
                attempts.map { (it.join() as PromotionPrepareResult.Ready).saga }.distinct().size shouldBe 1
            }
        }
    }
})
