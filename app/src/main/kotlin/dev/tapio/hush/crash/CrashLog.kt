package dev.tapio.hush.crash

import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The build and the device a report came from.
 *
 * Every string is nullable on purpose: `Build.MODEL` and friends are platform
 * types that a stripped OEM image can leave unset, and a crash reporter that
 * throws while reporting a crash is worse than no crash reporter at all.
 */
data class CrashEnvironment(
    val versionName: String?,
    val versionCode: Long,
    val manufacturer: String?,
    val model: String?,
    val sdkInt: Int,
    val release: String?,
)

/**
 * Formats the single report Hush keeps. Pure — no Android types, no clock, no
 * file system — so the whole of it is covered by unit tests, and the parts
 * that can fail at runtime are only the ones that touch a disk.
 */
object CrashLog {

    private const val UNKNOWN = "unknown"

    /** Plain text: it has to survive being pasted into a chat or an email. */
    fun format(
        threadName: String?,
        error: Throwable,
        environment: CrashEnvironment,
        at: Instant,
    ): String = buildString {
        appendLine("Hush crash report")
        appendLine("time: ${DateTimeFormatter.ISO_INSTANT.format(at)}")
        appendLine("thread: ${threadName.orUnknown()}")
        appendLine("app: ${environment.versionName.orUnknown()} (${environment.versionCode})")
        appendLine("device: ${environment.manufacturer.orUnknown()} ${environment.model.orUnknown()}")
        appendLine("android: ${environment.release.orUnknown()} (API ${environment.sdkInt})")
        appendLine()
        // stackTraceToString() walks the whole "Caused by" chain for us.
        append(error.stackTraceToString().trimEnd())
    }

    private fun String?.orUnknown(): String = if (isNullOrBlank()) UNKNOWN else this
}
