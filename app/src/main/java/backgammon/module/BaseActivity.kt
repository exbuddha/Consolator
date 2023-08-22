package backgammon.module

import android.os.*
import androidx.appcompat.app.*
import androidx.lifecycle.*
import backgammon.module.activity.*
import backgammon.module.application.*

abstract class BaseActivity : AppCompatActivity() {
    abstract val backgroundLayoutResId: Int
    abstract val viewModel: VM

    lateinit var enableNetworkCallbacks: Work
    lateinit var disableNetworkCallbacks: Work

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState === null) {
            setContentView(backgroundLayoutResId)
            if (isNetworkStateAccessPermitted())
                enableNetworkCallbacks =
                    ::registerNetworkCapabilitiesCallback then
                    ::registerInternetAvailabilityCallback
            if (isInternetAccessPermitted())
                disableNetworkCallbacks =
                    ::unregisterNetworkCapabilitiesCallback then
                    ::unregisterInternetAvailabilityCallback
        }
    }

    override fun onStart() {
        super.onStart()
        enableNetworkCallbacks.invoke()
    }

    override fun onStop() {
        disableNetworkCallbacks.invoke()
        super.onStop()
    }

    override fun onLowMemory() {
        defer<MemoryManager>(::onLowMemory) { super.onLowMemory() }
    }

    abstract class VM : ViewModel()

    abstract inner class ConfigurationChangeManager : Reconfiguration()
    abstract inner class NightModeChangeManager : Reconfiguration()
    abstract inner class LocalesChangeManager : Reconfiguration()
}