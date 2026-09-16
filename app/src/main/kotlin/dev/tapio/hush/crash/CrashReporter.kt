package dev.tapio.hush.crash

import android.content.Context
import android.os.Build
import dev.tapio.hush.BuildConfig
import java.io.File
import java.time.Instant

/** Where the last crash report lives. One report, because only the last one matters. */
interface CrashReportStore {
    fun read(): String?
    fun write(report: String)
    fun clear()
}

/**
 * One file under [directory], overwritten every time. Every operation swallows
 * its own failures: writing happens while the process is already dying, and a
 * second exception there would replace a useful stack trace with a useless one.
 */
class FileCrashReportStore(private val directory: File) : CrashReportStore {

    private val file: File get() = File(directory, FILE_NAME)

    override fun read(): String? = runCatching {
        file.takeIf { it.isFile }?.readText()?.ifBlank { null }
    }.getOrNull()

    override fun write(report: String) {
        runCatching {
            directory.mkdirs()
            file.writeText(report)
        }
    }

    override fun clear() {
        runCatching { file.delete() }
    }

    companion object {
        private const val DIRECTORY_NAME = "crash"
        private const val FILE_NAME = "last_crash.txt"

        /** The one place the report's path is decided. */
        fun forApp(context: Context): FileCrashReportStore =
            FileCrashReportStore(File(context.filesDir, DIRECTORY_NAME))
    }
}

/** A store with nothing behind it: the default for previews and tests. */
class InMemoryCrashReportStore(private var report: String? = null) : CrashReportStore {
    override fun read(): String? = report
    override fun write(report: String) {
        this.report = report
    }

    override fun clear() {
        report = null
    }
}

/**
 * Writes the report, then hands the crash straight back to whoever was
 * handling it before, so Android still shows its dialog and still kills the
 * process. Nothing here is allowed to throw.
 */
class CrashReportHandler(
    private val store: CrashReportStore,
    private val environment: CrashEnvironment,
    private val delegate: Thread.UncaughtExceptionHandler?,
    private val now: () -> Instant = Instant::now,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, error: Throwable) {
        runCatching { store.write(CrashLog.format(thread.name, error, environment, now())) }
        delegate?.uncaughtException(thread, error)
    }
}

/**
 * Call this first in `Application.onCreate`. A handler installed after the
 * dependency graph cannot report a crash raised while building it, and
 * start-up is exactly when a side-loaded build tends to fall over.
 */
fun installCrashReporter(context: Context) {
    Thread.setDefaultUncaughtExceptionHandler(
        CrashReportHandler(
            store = FileCrashReportStore.forApp(context),
            environment = CrashEnvironment(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                sdkInt = Build.VERSION.SDK_INT,
                release = Build.VERSION.RELEASE,
            ),
            delegate = Thread.getDefaultUncaughtExceptionHandler(),
        ),
    )
}
