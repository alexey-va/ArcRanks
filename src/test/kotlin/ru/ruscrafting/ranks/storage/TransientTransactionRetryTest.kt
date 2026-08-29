package ru.ruscrafting.ranks.storage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.sql.SQLTransactionRollbackException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class TransientTransactionRetryTest : StringSpec({
    "retries a wrapped serialization rollback and returns the successful result" {
        var attempts = 0

        val result = TransientTransactionRetry.execute {
            attempts++
            if (attempts == 1) {
                CompletableFuture.failedFuture(
                    CompletionException(SQLTransactionRollbackException("deadlock", "40001", 1213)),
                )
            } else {
                CompletableFuture.completedFuture("committed")
            }
        }.join()

        result shouldBe "committed"
        attempts shouldBe 2
    }

    "does not retry a permanent transaction failure" {
        var attempts = 0
        val expected = IllegalStateException("invalid state")

        val failure = shouldThrow<CompletionException> {
            TransientTransactionRetry.execute<String> {
                attempts++
                CompletableFuture.failedFuture(expected)
            }.join()
        }

        failure.cause shouldBe expected
        attempts shouldBe 1
    }

    "stops after the bounded number of rollback attempts" {
        var attempts = 0

        shouldThrow<CompletionException> {
            TransientTransactionRetry.execute<String>(maximumAttempts = 3) {
                attempts++
                CompletableFuture.failedFuture(SQLTransactionRollbackException("deadlock", "40001", 1213))
            }.join()
        }

        attempts shouldBe 3
    }
})
