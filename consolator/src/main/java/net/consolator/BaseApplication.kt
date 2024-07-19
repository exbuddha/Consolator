package net.consolator

import android.app.*
import android.content.*
import net.consolator.application.*
import net.consolator.Scheduler.applicationMemoryManager

open class BaseApplication : Application(), ObjectProvider, UniqueContext {
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

    @Deprecated("Requires API Level <= 34")
    override fun onLowMemory() {
        defer<MemoryManager>(::onLowMemory) {
            super.onLowMemory() }
    }

    private fun SharedPreferences.Editor.putException(ex: Throwable) {
        putString(EXCEPTION, ex::class.qualifiedName)
        putString(EXCEPTION_MESSAGE, ex.message)
        ex.cause?.let { cause ->
            putString(EXCEPTION_CAUSE, cause::class.qualifiedName)
            putString(EXCEPTION_CAUSE_MESSAGE, cause.message) } }

    override fun invoke(type: AnyKClass) = when (type) {
        MemoryManager::class ->
            ::applicationMemoryManager.require(constructor = ::MemoryManager)
        else ->
            throw BaseImplementationRestriction() }

    companion object {
        const val ACTION_MIGRATE_APP: Short = 1
        const val COMMIT_NAV_MAIN_UI: Short = 2
        const val ABORT_NAV_MAIN_UI: Short = 3
    }
}