package backgammon.module

import android.app.*
import backgammon.module.application.*

open class BaseApplication : Application(), UniqueContext {
    override var startTime = now()
    override fun onCreate() {
        Thread.setDefaultUncaughtExceptionHandler { th, ex ->
            reactToUncaughtExceptionThrown(th, ex)
        }
        super.onCreate()
        instance = this
        with(Scheduler) {
            clock = Scheduler.Clock(priority = Thread.MAX_PRIORITY).alsoStart()
            observe()
        }
        startService(intendFor(BaseService::class).putExtra(START_TIME_KEY, startTime))
    }

    override fun onTrimMemory(level: Int) {
        defer<MemoryManager>(::onTrimMemory, level) { super.onTrimMemory(level) }
    }
    override fun onLowMemory() {
        defer<MemoryManager>(::onLowMemory) { super.onLowMemory() }
    }

    companion object {
        const val ACTION_MIGRATE_APP: Short = 1
        const val ACTION_NAV_MAIN_UI: Short = 2
    }
}