package dev.jtiisto.noise

import android.app.Application
import dev.jtiisto.noise.core.playback.di.playbackModule
import dev.jtiisto.noise.crash.installCrashReporter
import dev.jtiisto.noise.di.appModule
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
