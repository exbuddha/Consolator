package backgammon.module

import android.os.*
import androidx.appcompat.app.*
import backgammon.module.activity.*
import backgammon.module.application.*

abstract class BaseActivity : AppCompatActivity() {
    abstract val backgroundLayoutResId: Int

    var enableNetworkCallbacks: Work? = null
    var disableNetworkCallbacks: Work? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState === null) {
            setContentView(backgroundLayoutResId)
            if (isNetworkStateAccessPermitted()) {
                enableNetworkCallbacks = ::registerNetworkCapabilitiesCallback
                disableNetworkCallbacks = ::unregisterNetworkCapabilitiesCallback
            }
            if (isInternetAccessPermitted()) {
                enableNetworkCallbacks = enableNetworkCallbacks?.then(::registerInternetAvailabilityCallback)
                disableNetworkCallbacks = disableNetworkCallbacks?.then(::unregisterInternetAvailabilityCallback)
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
        defer<MemoryManager>(::onLowMemory) { super.onLowMemory() }
    }

    abstract inner class ConfigurationChangeManager : Reconfiguration()
    abstract inner class NightModeChangeManager : Reconfiguration()
    abstract inner class LocalesChangeManager : Reconfiguration()
}