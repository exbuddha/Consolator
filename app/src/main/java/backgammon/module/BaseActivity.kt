package backgammon.module

import android.os.*
import androidx.appcompat.app.*
import androidx.lifecycle.*
import backgammon.module.activity.*
import backgammon.module.application.*

abstract class BaseActivity : AppCompatActivity() {
    abstract val backgroundLayout: Int
    abstract val viewModel: VM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState === null) {
            setContentView(backgroundLayout)
            viewModel.apply {
                enableNetworkCapabilitiesCallbackOnStart = isNetworkStateAccessPermitted() and enableNetworkCapabilitiesCallbackOnStart
                enableInternetAvailabilityCallbackOnStart = isInternetAccessPermitted() and enableInternetAvailabilityCallbackOnStart
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.apply {
            if (enableNetworkCapabilitiesCallbackOnStart)
                registerNetworkCapabilitiesCallback()
            if (enableInternetAvailabilityCallbackOnStart)
                registerInternetAvailabilityCallback()
        }
    }

    override fun onStop() {
        viewModel.apply {
            if (disableNetworkCapabilityCallbackOnStop)
                unregisterNetworkCapabilitiesCallback()
            if (disableInternetAvailabilityCallbackOnStop)
                unregisterInternetAvailabilityCallback()
        }
        super.onStop()
    }

    override fun onLowMemory() {
        defer<MemoryManager>(::onLowMemory) { super.onLowMemory() }
    }

    abstract class VM : ViewModel() {
        var enableNetworkCapabilitiesCallbackOnStart = true
        var enableInternetAvailabilityCallbackOnStart = true
        var disableNetworkCapabilityCallbackOnStop = true
        var disableInternetAvailabilityCallbackOnStop = true
    }

    abstract inner class ConfigurationChangeManager : Reconfiguration()
    abstract inner class NightModeChangeManager : Reconfiguration()
    abstract inner class LocalesChangeManager : Reconfiguration()
}