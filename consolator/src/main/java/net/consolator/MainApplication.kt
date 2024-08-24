package net.consolator

import iso.consolator.*
import kotlin.reflect.*

internal open class MainApplication : BaseApplication() {
    override fun onCreate() {
        super.onCreate()
        commit @Scope @Synchronous @Tag(APP_INIT) {
            log(info,
            registerValue(
                @Tag(INET_MIN_INTERVAL)
                R.integer.netcall_min_time_interval,
                ::netcall_min_time_interval),
            "Minimum interval for netcall was found in resource.")
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

        @JvmStatic fun <V> registerValueByTag(value: R, tag: TagType) =
            Value(value).setTag(tag)
    }
}