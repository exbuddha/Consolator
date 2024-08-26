@file:JvmName(JVM_CLASS_NAME)
@file:JvmMultifileClass

package net.consolator

import android.os.*
import androidx.annotation.*
import androidx.appcompat.app.*
import iso.consolator.*
import iso.consolator.activity.*

@LayoutRes
internal var layoutId = R.layout.background

abstract class BaseActivity : AppCompatActivity(), TransitionManager, ReferredContext {
    @Coordinate(key = 1)
    internal var enableNetworkCallbacks: Work? = null
    @Coordinate(key = 2)
    internal var disableNetworkCallbacks: Work? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState === null)
            setContentView(layoutId)
        if (savedInstanceState?.getBoolean(ENABLE_NETWORK_CALLBACKS_KEY) != false) {
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
        commitStart()
    }

    override fun onRestart() {
        super.onRestart()
        commitRestart()
    }

    override fun onResume() {
        super.onResume()
        commitResume()
    }

    override fun onPause() {
        super.onPause()
        commitPause()
    }

    override fun onStop() {
        disableNetworkCallbacks?.invoke()
        commitStop()
        foregroundLifecycleOwner = null
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        commitDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(
            ENABLE_NETWORK_CALLBACKS_KEY,
            enableNetworkCallbacks !== null)
        commitSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override var ref: WeakContext? = null
        get() = field.unique(this).also { field = it }

    internal abstract inner class ConfigurationChangeManager : iso.consolator.activity.ConfigurationChangeManager()
    internal abstract inner class NightModeChangeManager : iso.consolator.activity.NightModeChangeManager()
    internal abstract inner class LocalesChangeManager : iso.consolator.activity.LocalesChangeManager()

    internal companion object {
        const val ENABLE_NETWORK_CALLBACKS_KEY = "4"
        const val COMMIT_NAV_MAIN_UI: Short = 2
        const val ABORT_NAV_MAIN_UI: Short = 3
        const val VIEW_TAG = "ACTIVITY"
    }
}