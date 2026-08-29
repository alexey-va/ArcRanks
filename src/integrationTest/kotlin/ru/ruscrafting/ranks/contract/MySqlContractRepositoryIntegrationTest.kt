package ru.ruscrafting.ranks.contract

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlRuntime
import ru.arc.sql.SqlSslMode
import ru.arc.testing.containers.MySqlTestService
import ru.arc.testing.containers.MySqlTestSettings
import ru.ruscrafting.ranks.domain.ProgressMetric
import ru.ruscrafting.ranks.domain.SpecializationPath
import ru.ruscrafting.ranks.progress.ProgressMutation
import ru.ruscrafting.ranks.storage.MySqlProgressRepository
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MySqlContractRepositoryIntegrationTest : StringSpec({
    "concurrent accept and claim converge to one active contract and one reward" {
        MySqlTestService.start(MySqlTestSettings(database = "arc_ranks_contract_test")).use { mysql ->
            val endpoint = mysql.endpoint
            SqlRuntime.create(
                SqlConnectionConfig(
                    endpoint.host, endpoint.port, endpoint.database, endpoint.username, endpoint.password,
                    sslMode = SqlSslMode.DISABLED, minimumIdle = 0, maximumPoolSize = 4,
                ),
                "arc-ranks-contract-test",
            ).use { runtime ->
                val contracts = MySqlContractRepository(runtime)
                val progress = MySqlProgressRepository(runtime)
                contracts.initialize().join()
                val player = UUID.randomUUID()
                val cycle = ContractCycle.at(Instant.parse("2026-08-29T18:42:00Z"))
                val offer = ContractOffer(
                    ContractId("aaaaaaaaaaaaaaaaaaaaaaaa"),
                    cycle,
                    0,
                    0,
                    SpecializationPath.BUILDING,
                    10,
                    2,
                )

                val accepts = listOf(contracts.accept(player, offer), contracts.accept(player, offer))
                CompletableFuture.allOf(*accepts.toTypedArray()).join()
                accepts.count { it.join() is ContractAcceptStorageResult.Accepted } shouldBe 1
                accepts.count { it.join() is ContractAcceptStorageResult.AlreadyActive } shouldBe 1

                progress.applyMutations(
                    player,
                    listOf(ProgressMutation.Add(ProgressMetric.BLOCKS_PLACED, 10)),
                ).join()
                val claims = listOf(contracts.claim(player, cycle), contracts.claim(player, cycle))
                CompletableFuture.allOf(*claims.toTypedArray()).join()
                claims.count { it.join() is ContractClaimStorageResult.Claimed } shouldBe 1
                claims.count { it.join() is ContractClaimStorageResult.AlreadyClaimed } shouldBe 1
                progress.load(player).join().progress.value(ProgressMetric.BLOCKS_PLACED) shouldBe 12
                contracts.state(player, cycle).join().claimedStamps shouldBe 1
            }
        }
    }
})
