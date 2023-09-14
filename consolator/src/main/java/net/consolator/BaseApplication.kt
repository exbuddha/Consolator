package net.consolator

import android.app.*
import android.content.*
import net.consolator.application.*

open class BaseApplication : Application(), UniqueContext {
    override var startTime = now()

    override fun onCreate() {
        mainUncaughtExceptionHandler = @Tag("uncaught-shared") ExceptionHandler { th, ex ->
            with(getSharedPreferences("uncaught", MODE_PRIVATE).edit()) {
                putLong("start", startTime)
                putLong("now", now())
                putBoolean("main", th.isMainThread())
                putException("exception", ex)
            }
        }
        Thread.setDefaultUncaughtExceptionHandler(mainUncaughtExceptionHandler)
        super.onCreate()
        instance = this
        Scheduler {
            clock = Scheduler.Clock(priority = Thread.MAX_PRIORITY)
                .alsoStart()
            observe()
        }
        startService(intendFor(BaseService::class).putExtra(START_TIME_KEY, startTime))
    }

    override fun onTrimMemory(level: Int) {
        defer<MemoryManager>(::onTrimMemory, level) {
            super.onTrimMemory(level)
        }
    }
    override fun onLowMemory() {
        defer<MemoryManager>(::onLowMemory) {
            super.onLowMemory()
        }
    }

    private fun SharedPreferences.Editor.putException(name: String, ex: Throwable) {
        putString("${name}-message", ex.message)
        ex.cause?.let { cause ->
            putString("${name}-cause", cause::class.qualifiedName)
            putString("${name}-cause-msg", cause.message)
        }
    }

    companion object {
        const val ACTION_MIGRATE_APP: Short = 1
        const val COMMIT_NAV_MAIN_UI: Short = 2
        const val ABORT_NAV_MAIN_UI: Short = 3
    }
}