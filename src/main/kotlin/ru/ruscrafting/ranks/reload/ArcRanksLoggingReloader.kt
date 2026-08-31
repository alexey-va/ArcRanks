package ru.ruscrafting.ranks.reload

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

sealed interface ArcRanksLoggingReloadResult {
    data object Unchanged : ArcRanksLoggingReloadResult
    data object Applied : ArcRanksLoggingReloadResult
    data class Invalid(val reason: String) : ArcRanksLoggingReloadResult
}

/** Tracks logging.yml independently because it belongs to arc-core rather than the gameplay snapshot. */
class ArcRanksLoggingReloader(
    private val path: Path,
    private val reload: () -> Unit,
) {
    private var activeFingerprint = fingerprint(path)

    @Synchronized
    fun reloadIfChanged(): ArcRanksLoggingReloadResult {
        val candidate = runCatching { fingerprint(path) }
            .getOrElse { return ArcRanksLoggingReloadResult.Invalid(it.safeLoggingMessage()) }
        if (candidate == activeFingerprint) return ArcRanksLoggingReloadResult.Unchanged
        return try {
            reload()
            val afterReload = fingerprint(path)
            require(afterReload == candidate) { "logging.yml changed while it was being reloaded; retry reload" }
            activeFingerprint = afterReload
            ArcRanksLoggingReloadResult.Applied
        } catch (failure: Throwable) {
            ArcRanksLoggingReloadResult.Invalid(failure.safeLoggingMessage())
        }
    }

    private companion object {
        fun fingerprint(path: Path): String {
            require(Files.isRegularFile(path)) { "Missing logging configuration: ${path.fileName}" }
            return MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }
}

private fun Throwable.safeLoggingMessage(): String =
    (message ?: javaClass.simpleName).replace('\n', ' ').replace('\r', ' ').take(240)
