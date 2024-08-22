package net.consolator

import iso.consolator.*
import iso.consolator.Value.Companion.setTarget
import kotlin.reflect.*

internal open class MainApplication : BaseApplication() {
    override fun onCreate() {
        super.onCreate()
        commit @Scope @Synchronous @Tag(APP_INIT) {
            log(info,
            registerValue(
                @Tag(VIEW_MIN_DELAY)
                R.integer.view_min_delay.toLong(),
                ::view_min_delay),
            "Minimum delay for view was found in resource.") }
    }

    companion object {
        @JvmStatic fun <V> registerValue(value: V, target: KProperty<V>) =
            Value(value).setTarget(target)
    }
}