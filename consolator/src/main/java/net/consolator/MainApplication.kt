package net.consolator

import iso.consolator.*

internal open class MainApplication : BaseApplication() {
    override fun onCreate() {
        super.onCreate()
        commit @Scope @Tag(APP_INIT) {
            R.integer.view_min_delay.toLong().let {
            if (VIEW_MIN_DELAY != it)
                log(info, LogValue(it), "Minimum delay for view was found in resource.") } }
    }
}