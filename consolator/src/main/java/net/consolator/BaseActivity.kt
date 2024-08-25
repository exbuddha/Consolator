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

internal abstract class BaseActivity : AppCompatActivity(), TransitionManager, ReferredContext {
    internal var enableNetworkCallbacks: Work? = null
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
        commitStartActivity(this)
    }

    override fun onRestart() {
        super.onRestart()
        commitRestartActivity(this)
    }

    override fun onResume() {
        super.onResume()
        commitResumeActivity(this)
    }

    override fun onPause() {
        super.onPause()
        commitPauseActivity(this)
    }

    override fun onStop() {
        disableNetworkCallbacks?.invoke()
        commitStopActivity(this)
        foregroundLifecycleOwner = null
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        commitDestroyActivity(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(
            ENABLE_NETWORK_CALLBACKS_KEY,
            enableNetworkCallbacks !== null)
        commitSaveActivity(this, outState)
        super.onSaveInstanceState(outState)
    }

    override var ref: WeakContext? = null
        get() = field.unique(this).also { field = it }

    abstract inner class ConfigurationChangeManager : iso.consolator.activity.ConfigurationChangeManager()
    abstract inner class NightModeChangeManager : iso.consolator.activity.NightModeChangeManager()
    abstract inner class LocalesChangeManager : iso.consolator.activity.LocalesChangeManager()

    companion object {
        const val VIEW_TAG = "ACTIVITY"
        const val ENABLE_NETWORK_CALLBACKS_KEY = "4"
    }
}