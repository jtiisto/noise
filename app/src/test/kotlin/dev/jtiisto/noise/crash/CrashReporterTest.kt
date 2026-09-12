package dev.jtiisto.noise.crash

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

class FileCrashReportStoreTest {

    @TempDir
    lateinit var root: File

    private fun store() = FileCrashReportStore(File(root, "crash"))

    @Test
    fun `an empty directory holds no report`() {
        assertNull(store().read())
    }

    @Test
    fun `a written report reads back and only the last one survives`() {
        val store = store()

        store.write("first")
        store.write("second")

        assertEquals("second", store.read())
        assertEquals(1, File(root, "crash").listFiles()?.size)
    }

    @Test
    fun `clearing removes the report`() {
        val store = store()
        store.write("boom")

        store.clear()

        assertNull(store.read())
    }

    @Test
    fun `an unwritable directory is swallowed rather than thrown`() {
        // A file where the directory should be: mkdirs and writeText both fail.
        val blocked = File(root, "blocked").apply { writeText("not a directory") }
        val store = FileCrashReportStore(blocked)

        store.write("boom")

        assertNull(store.read())
    }
}

class CrashReportHandlerTest {

    private val environment = CrashEnvironment("0.1.0", 1, "Google", "Pixel 7a", 35, "15")
    private val moment = Instant.parse("2026-09-12T04:31:07Z")

    @Test
    fun `the report is written and the crash handed on to the previous handler`() {
        val store = InMemoryCrashReportStore()
        var delegated: Throwable? = null
        val handler = CrashReportHandler(
            store = store,
            environment = environment,
            delegate = { _, error -> delegated = error },
            now = { moment },
        )
        val error = IllegalStateException("boom")

        handler.uncaughtException(Thread.currentThread(), error)

        assertTrue(store.read()!!.contains("java.lang.IllegalStateException: boom"))
        assertEquals(error, delegated)
    }

    @Test
    fun `a store that throws does not stop the crash reaching Android`() {
        var delegated = false
        val handler = CrashReportHandler(
            store = object : CrashReportStore {
                override fun read(): String? = null
                override fun write(report: String) = throw OutOfMemoryError("no room")
                override fun clear() = Unit
            },
            environment = environment,
            delegate = { _, _ -> delegated = true },
            now = { moment },
        )

        handler.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertTrue(delegated)
    }

    @Test
    fun `no previous handler is not an error`() {
        val store = InMemoryCrashReportStore()
        val handler = CrashReportHandler(store, environment, delegate = null, now = { moment })

        handler.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertTrue(store.read()!!.contains("boom"))
    }
}
