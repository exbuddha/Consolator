package net.consolator

import android.os.*
import androidx.appcompat.app.*
import net.consolator.activity.*
import net.consolator.application.*

abstract class BaseActivity : AppCompatActivity(), ReferredContext {
    var enableNetworkCallbacks: Work? = null
    var disableNetworkCallbacks: Work? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState === null) {
            setContentView(layoutId)
            if (isNetworkStateAccessPermitted) {
                enableNetworkCallbacks = ::registerNetworkCallback
                disableNetworkCallbacks = ::unregisterNetworkCallback }
            if (isInternetAccessPermitted) {
                enableNetworkCallbacks = enableNetworkCallbacks?.then(::registerInternetCallback)
                disableNetworkCallbacks = disableNetworkCallbacks?.then(::unregisterInternetCallback) } }
    }

    override fun onStart() {
        super.onStart()
        foregroundLifecycleOwner = this
        enableNetworkCallbacks?.invoke()
    }

    override fun onStop() {
        disableNetworkCallbacks?.invoke()
        foregroundLifecycleOwner = null
        super.onStop()
    }

    @Deprecated("Requires API Level <= 34")
    override fun onLowMemory() {
        defer<MemoryManager>(::onLowMemory) {
            super.onLowMemory() }
    }

    override var ref: WeakContext? = null
        get() = field.unique(this).also { field = it }

    abstract inner class ConfigurationChangeManager : ConfigurationManager()
    abstract inner class NightModeChangeManager : ConfigurationManager()
    abstract inner class LocalesChangeManager : ConfigurationManager()
}