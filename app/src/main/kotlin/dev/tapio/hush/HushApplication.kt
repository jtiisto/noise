package dev.tapio.hush

import android.app.Application
import dev.tapio.hush.core.playback.di.playbackModule
import dev.tapio.hush.crash.installCrashReporter
import dev.tapio.hush.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class HushApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before the graph, before anything else that can throw: a crash while
        // Koin is starting is precisely the one worth having a trace of.
        installCrashReporter(this)
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@HushApplication)
            // appModule binds the AudioEngine that playbackModule requires; the
            // controller is created at start so a process restarted by the
            // sticky service resumes playback without waiting for the UI.
            modules(appModule, playbackModule)
        }
    }
}
