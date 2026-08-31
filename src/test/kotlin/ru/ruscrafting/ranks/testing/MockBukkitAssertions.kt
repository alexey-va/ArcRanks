package ru.ruscrafting.ranks.testing

/**
 * arc-core-paper-testing 2.1.3 predates its shared fail-fast guard. Keep unsupported
 * platform operations as assertion failures instead of silently aborted scenarios.
 */
fun <T> failOnUnsupportedMockBukkitOperation(block: () -> T): T = try {
    block()
} catch (failure: Throwable) {
    val unsupported = generateSequence(failure as Throwable?) { it.cause }
        .firstOrNull { it.javaClass.simpleName == MOCK_BUKKIT_UNIMPLEMENTED_OPERATION }
    if (unsupported != null) {
        throw AssertionError("MockBukkit scenario reached an unsupported Paper API operation").apply {
            initCause(unsupported)
        }
    }
    throw failure
}

private const val MOCK_BUKKIT_UNIMPLEMENTED_OPERATION = "UnimplementedOperationException"
