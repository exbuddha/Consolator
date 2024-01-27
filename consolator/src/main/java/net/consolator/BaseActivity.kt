package net.consolator

import android.os.*
import androidx.appcompat.app.*
import net.consolator.activity.*
import net.consolator.application.*

abstract class BaseActivity : AppCompatActivity(), VolatileContext {
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
        enableNetworkCallbacks?.invoke()
    }

    override fun onStop() {
        disableNetworkCallbacks?.invoke()
        super.onStop()
    }

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