package net.consolator

internal open class MainApplication : BaseApplication() {
    companion object {
        var view_min_delay = R.integer.view_min_delay.toLong() }

    override fun onCreate() {
        super.onCreate()
    }
}