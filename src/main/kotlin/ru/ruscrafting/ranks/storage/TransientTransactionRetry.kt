package ru.ruscrafting.ranks.storage

import ru.arc.sql.SqlExecutor
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTransactionRollbackException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

internal object TransientTransactionRetry {
    fun <T> execute(
        maximumAttempts: Int = DEFAULT_MAXIMUM_ATTEMPTS,
        operation: () -> CompletableFuture<T>,
    ): CompletableFuture<T> {
        require(maximumAttempts in 1..MAXIMUM_ALLOWED_ATTEMPTS) {
            "Transaction attempts must be between 1 and $MAXIMUM_ALLOWED_ATTEMPTS"
        }
        return attempt(1, maximumAttempts, operation)
    }

    private fun <T> attempt(
        attempt: Int,
        maximumAttempts: Int,
        operation: () -> CompletableFuture<T>,
    ): CompletableFuture<T> = operation().handle { result, failure ->
        when {
            failure == null -> CompletableFuture.completedFuture(result)
            attempt < maximumAttempts && isRetryable(failure) -> attempt(attempt + 1, maximumAttempts, operation)
            else -> CompletableFuture.failedFuture(unwrap(failure))
        }
    }.thenCompose { it }

    private fun isRetryable(failure: Throwable): Boolean {
        val sql = unwrap(failure) as? SQLException ?: return false
        return sql is SQLTransactionRollbackException ||
            sql.sqlState == SERIALIZATION_FAILURE_SQL_STATE ||
            sql.errorCode == MYSQL_LOCK_WAIT_TIMEOUT ||
            sql.errorCode == MYSQL_DEADLOCK
    }

    private fun unwrap(failure: Throwable): Throwable {
        var current = failure
        while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
            current = requireNotNull(current.cause)
        }
        return current
    }

    private const val DEFAULT_MAXIMUM_ATTEMPTS = 3
    private const val MAXIMUM_ALLOWED_ATTEMPTS = 5
    private const val SERIALIZATION_FAILURE_SQL_STATE = "40001"
    private const val MYSQL_LOCK_WAIT_TIMEOUT = 1205
    private const val MYSQL_DEADLOCK = 1213
}

internal fun <T> SqlExecutor.retryingTransaction(block: (Connection) -> T): CompletableFuture<T> =
    TransientTransactionRetry.execute { transaction(block) }
