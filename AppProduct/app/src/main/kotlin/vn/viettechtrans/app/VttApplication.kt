package vn.viettechtrans.app

import android.app.Application
import android.content.ComponentCallbacks2

class VttApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this)
        container = AppContainer(this)
    }

    /** RAM policy: free the speech models when the app goes to the background or memory is low. */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            container.speaker.stop()
            container.speechEngines.releaseAll()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        container.speechEngines.releaseAll()
    }
}
