package net.consolator

import android.os.*
import androidx.appcompat.app.*
import net.consolator.activity.*
import net.consolator.application.*

abstract class BaseActivity : AppCompatActivity(), ReferredContext {
    internal var enableNetworkCallbacks: Work? = null
    internal var disableNetworkCallbacks: Work? = null

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

    override var ref: WeakContext? = null
        get() = field.unique(this).also { field = it }

    internal abstract inner class ConfigurationChangeManager : ConfigurationManager()
    internal abstract inner class NightModeChangeManager : ConfigurationManager()
    internal abstract inner class LocalesChangeManager : ConfigurationManager()
}