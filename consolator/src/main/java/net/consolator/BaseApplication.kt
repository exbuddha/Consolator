package net.consolator

import android.app.*
import android.content.*
import net.consolator.application.*
import net.consolator.Scheduler.applicationMemoryManager

open class BaseApplication : Application(), ObjectProvider, UniqueContext {
    override var startTime = now()

    init {
        mainUncaughtExceptionHandler = @Tag("uncaught-shared") ExceptionHandler { th, ex ->
            with(getSharedPreferences("uncaught", MODE_PRIVATE).edit()) {
                putLong("start", startTime)
                putLong("now", now())
                putBoolean("main", th.isMainThread())
                putException("exception", ex)
            }
        }
        Thread.setDefaultUncaughtExceptionHandler(mainUncaughtExceptionHandler)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        service("start")
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

    override fun invoke(type: AnyKClass) = when (type) {
        MemoryManager::class ->
            ::applicationMemoryManager.require(constructor = ::MemoryManager)!!
        else ->
            throw BaseImplementationRestriction
    }

    companion object {
        const val ACTION_MIGRATE_APP: Short = 1
        const val COMMIT_NAV_MAIN_UI: Short = 2
        const val ABORT_NAV_MAIN_UI: Short = 3
    }
}

typealias Clock = Scheduler.Clock