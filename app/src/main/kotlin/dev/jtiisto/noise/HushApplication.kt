package dev.jtiisto.noise

import android.app.Application
import dev.jtiisto.noise.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class HushApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@HushApplication)
            // TODO(integration): add playbackModule (and the audio module) here;
            // appModule then keeps only the ViewModel binding.
            modules(appModule)
        }
    }
}
