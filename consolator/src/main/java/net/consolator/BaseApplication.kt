package net.consolator

import android.app.*
import android.content.*
import net.consolator.application.*

open class BaseApplication : Application(), UniqueContext {
    override var startTime = now()

    init {
        mainUncaughtExceptionHandler = @Tag(UNCAUGHT_SHARED) ExceptionHandler { th, ex ->
            with(getSharedPreferences(UNCAUGHT, MODE_PRIVATE).edit()) {
                putLong(START, startTime)
                putLong(NOW, now())
                putBoolean(MAIN, th.isMainThread())
                putException(ex) } }
        Thread.setDefaultUncaughtExceptionHandler(mainUncaughtExceptionHandler) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        commit(START)
    }

    override fun onTrimMemory(level: Int) {
        defer<MemoryManager>(::onTrimMemory, level) {
            super.onTrimMemory(level) }
    }

    private fun SharedPreferences.Editor.putException(ex: Throwable) {
        putString(EXCEPTION, ex::class.qualifiedName)
        putString(EXCEPTION_MESSAGE, ex.message)
        ex.cause?.let { cause ->
            putString(EXCEPTION_CAUSE, cause::class.qualifiedName)
            putString(EXCEPTION_CAUSE_MESSAGE, cause.message) } }

    internal companion object {
        const val ACTION_MIGRATE_APP: Short = 1
        const val COMMIT_NAV_MAIN_UI: Short = 2
        const val ABORT_NAV_MAIN_UI: Short = 3
    }
}