package net.consolator

import android.content.Context
import android.os.*
import androidx.appcompat.app.*
import net.consolator.activity.*
import net.consolator.application.*

abstract class BaseActivity : AppCompatActivity(), SystemContext {
    var enableNetworkCallbacks: Work? = null
    var disableNetworkCallbacks: Work? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState === null) {
            setContentView(layoutId)
            if (isNetworkStateAccessPermitted) {
                enableNetworkCallbacks = ::registerNetworkCallback
                disableNetworkCallbacks = ::unregisterNetworkCallback
            }
            if (isInternetAccessPermitted) {
                enableNetworkCallbacks = enableNetworkCallbacks?.then(::registerInternetCallback)
                disableNetworkCallbacks = disableNetworkCallbacks?.then(::unregisterInternetCallback)
            }
        }
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
        (this as Context).defer<MemoryManager>(::onLowMemory) {
            super.onLowMemory()
        }
    }

    override val ref: WeakContext? = null
        get() = field.unique(this)

    abstract inner class ConfigurationChangeManager : Reconfiguration()
    abstract inner class NightModeChangeManager : Reconfiguration()
    abstract inner class LocalesChangeManager : Reconfiguration()
}