package backgammon.module

import android.net.*
import android.net.ConnectivityManager.*
import android.net.NetworkCapabilities.*
import android.util.*
import androidx.lifecycle.*
import kotlin.annotation.AnnotationRetention.*
import kotlin.annotation.AnnotationTarget.*
import kotlin.coroutines.*
import kotlinx.coroutines.*
import okhttp3.*

val isConnected
    get() = connectivityRequest!!.canBeSatisfiedBy(networkCapabilities)
var hasInternet = false
    get() = isConnected && field
val hasMobile
    get() = networkCapabilities?.hasTransport(TRANSPORT_CELLULAR) ?: false
val hasWifi
    get() = networkCapabilities?.hasTransport(TRANSPORT_WIFI) ?: false

fun registerNetworkCapabilitiesCallback() {
    connectivityManager.registerDefaultNetworkCallback(networkCapabilitiesListener!!)
}
fun unregisterNetworkCapabilitiesCallback() {
    connectivityManager.unregisterNetworkCallback(networkCapabilitiesListener!!)
    clearNetworkCapabilitiesCallbackObjects()
}
private fun clearNetworkCapabilitiesCallbackObjects() {
    networkCapabilitiesListener = null
    connectivityRequest = null
}

var reactToNetworkCapabilitiesChanged: (Network, NetworkCapabilities) -> Unit = { _, _ -> }
private var networkCapabilitiesListener: NetworkCallback? = null
    get() = field ?: object : NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            trySafely { reactToNetworkCapabilitiesChanged.invoke(network, networkCapabilities) }
        }
    }.also { field = it }

fun LifecycleOwner.registerInternetAvailabilityCallback(context: CoroutineContext = Dispatchers.IO) {
    relaunchJobIfNotActive(
        ::internetAvailabilityJob, context) {
        repeatSuspended(
            ::isActive,
            internetAvailabilityJobFunction,
            ::internetAvailabilityDelayTime)
    }
}
fun sendInternetAvailabilityRequest(block: (Response) -> Unit) = block(
    OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .build()
        .newCall(Request.Builder()
            .url("https://httpbin.org/delay/1")
            .build())
        .execute())
fun pauseInternetAvailabilityCallback() {
    repeatInternetAvailabilityRequest = false
}
fun resumeInternetAvailabilityCallback() {
    repeatInternetAvailabilityRequest = true
}
fun unregisterInternetAvailabilityCallback() {
    internetAvailabilityJob?.cancel()
    clearInternetAvailabilityCallbackObjects()
}
private fun clearInternetAvailabilityCallbackObjects() {
    internetAvailabilityJob = null
}

@JobTreeRoot @NetworkListener
var internetAvailabilityJob: Job? = null
    set(value) {
        // update addressable layer?
        field = value
    }
var internetAvailabilityJobFunction: JobFunction = @Tag("inet-avail") { scope ->
    if (repeatInternetAvailabilityRequest && isInternetAvailabilityTimeIntervalExceeded) {
        if (infoLogIsNotBypassed)
            info(INET_TAG, "Trying to send out http request for internet availability...")
        tryCanceling({
            sendInternetAvailabilityRequest { response ->
                trySafelyCanceling { reactToInternetAvailabilityResponseReceived.invoke(scope, response) } }
        }, { ex ->
            trySafelyCanceling { reactToInternetAvailabilityRequestFailed.invoke(scope, ex) }
        })
    }
}
var reactToInternetAvailabilityResponseReceived: (Any?, Response) -> Any? = @Tag("inet-avail") { _, response ->
    hasInternet = response.isSuccessful
    if (response.isSuccessful)
        lastInternetAvailabilityResponseTime = now()
    response.close()
    if (infoLogIsNotBypassed)
        info(INET_TAG, "Received response for internet availability.")
}
var reactToInternetAvailabilityRequestFailed: (Any?, Throwable) -> Any? = @Tag("inet-avail") { _, _ ->
    if (warningLogIsNotBypassed)
        warning(INET_TAG, "Failed to send http request for internet availability..")
}
private const val MIN_TIME_INTERVAL_INET_AVAIL = 5000L
var internetAvailabilityTimeInterval = MIN_TIME_INTERVAL_INET_AVAIL
    set(value) {
        field = maxOf(value, MIN_TIME_INTERVAL_INET_AVAIL)
    }
private var lastInternetAvailabilityResponseTime = 0L
    set(value) {
        if (value == 0L || value > field)
            field = value
    }
private val internetAvailabilityDelayTime
    get() = getDelayTime(internetAvailabilityTimeInterval, lastInternetAvailabilityResponseTime)
private val isInternetAvailabilityTimeIntervalExceeded
    get() = isTimeIntervalExceeded(internetAvailabilityTimeInterval, lastInternetAvailabilityResponseTime)
private var repeatInternetAvailabilityRequest = true

@Retention(SOURCE)
@Target(FUNCTION, PROPERTY)
annotation class NetworkListener

private val connectivityManager
    get() = instance!!.getSystemService(ConnectivityManager::class.java)!!
val network
    get() = connectivityManager.activeNetwork
val networkCapabilities
    get() = with(connectivityManager) { getNetworkCapabilities(activeNetwork) }
private var connectivityRequest: NetworkRequest? = null
    get() = field ?: buildNetworkRequest {
        addCapability(NET_CAPABILITY_INTERNET)
    }.also { field = it }
private inline fun buildNetworkRequest(block: NetworkRequest.Builder.() -> Unit) =
    NetworkRequest.Builder().apply(block).build()

const val INET_TAG = "INTERNET"