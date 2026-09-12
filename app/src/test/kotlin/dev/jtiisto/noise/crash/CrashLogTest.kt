package dev.jtiisto.noise.crash

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class CrashLogTest {

    private val moment = Instant.parse("2026-09-12T04:31:07Z")

    private val pixel = CrashEnvironment(
        versionName = "0.1.0",
        versionCode = 1,
        manufacturer = "Google",
        model = "Pixel 7a",
        sdkInt = 35,
        release = "15",
    )

    private fun report(
        threadName: String? = "main",
        error: Throwable = IllegalStateException("boom"),
        environment: CrashEnvironment = pixel,
    ) = CrashLog.format(threadName, error, environment, moment)

    @Test
    fun `the header carries the timestamp, thread, build and device`() {
        val text = report()

        assertTrue(text.startsWith("Hush crash report"), text)
        assertTrue(text.contains("time: 2026-09-12T04:31:07Z"), text)
        assertTrue(text.contains("thread: main"), text)
        assertTrue(text.contains("app: 0.1.0 (1)"), text)
        assertTrue(text.contains("device: Google Pixel 7a"), text)
        assertTrue(text.contains("android: 15 (API 35)"), text)
    }

    @Test
    fun `the stack trace keeps the whole cause chain`() {
        val root = IllegalArgumentException("no such sound")
        val middle = IllegalStateException("mix rejected", root)
        val text = report(error = RuntimeException("render failed", middle))

        assertTrue(text.contains("java.lang.RuntimeException: render failed"), text)
        assertTrue(text.contains("Caused by: java.lang.IllegalStateException: mix rejected"), text)
        assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException: no such sound"), text)
        // The frames themselves have to survive, not just the messages.
        assertTrue(text.contains("CrashLogTest"), text)
    }

    @Test
    fun `missing device fields become 'unknown' rather than an exception`() {
        val text = report(
            threadName = null,
            error = RuntimeException(),
            environment = CrashEnvironment(
                versionName = null,
                versionCode = 0,
                manufacturer = null,
                model = "   ",
                sdkInt = 26,
                release = null,
            ),
        )

        assertTrue(text.contains("thread: unknown"), text)
        assertTrue(text.contains("app: unknown (0)"), text)
        assertTrue(text.contains("device: unknown unknown"), text)
        assertTrue(text.contains("android: unknown (API 26)"), text)
        assertFalse(text.contains("null"), text)
    }

    @Test
    fun `the report ends with the trace and no trailing blank lines`() {
        val text = report()

        assertTrue(text.contains("java.lang.IllegalStateException: boom"), text)
        assertEquals(text.trimEnd(), text)
    }
}
