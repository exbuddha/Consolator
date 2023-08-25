package backgammon.module

import android.net.*
import android.net.ConnectivityManager.*
import android.net.NetworkCapabilities.*
import android.os.*
import android.util.*
import androidx.lifecycle.*
import kotlin.annotation.AnnotationRetention.*
import kotlin.annotation.AnnotationTarget.*
import kotlin.coroutines.*
import kotlin.reflect.*
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
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

fun LifecycleOwner.registerInternetAvailabilityCallback() {
    relaunchJobIfNotActive(::networkCaller, IO) {
        repeatSuspended(::isActive,
            networkCallFunction,
            ::netCallDelayTime)
    }
}
fun registerInternetAvailabilityCallback() {
    Scheduler.relaunchJobIfNotActive(::networkCaller, IO) {
        repeatSuspended(::isActive,
            networkCallFunction,
            ::netCallDelayTime)
    }
}
fun pauseInternetAvailabilityCallback() {
    repeatNetCallback = false
}
fun resumeInternetAvailabilityCallback() {
    repeatNetCallback = true
}
fun unregisterInternetAvailabilityCallback() {
    networkCaller?.cancel()
    clearInternetAvailabilityCallbackObjects()
}
private fun clearInternetAvailabilityCallbackObjects() {
    networkCaller = null
}

@JobTreeRoot @NetworkListener @Tag(NET)
var networkCaller: Job? = null
    set(value) {
        // update addressable layer?
        field = value
    }
private var networkCallFunction: JobFunction = @Tag(NET_FUNCTION) { scope ->
    if (repeatNetCallback && isNetCallTimeIntervalExceeded) {
        if (infoLogIsNotBypassed)
            info(INET_TAG, "Trying to send out http request for network caller...")
        synchronized(netCall) {
            tryCancelingForResult({
                netCall.asType<NetCall>()!!.commit { response ->
                    trySafelyCanceling { reactToNetCallResponseReceived.commit(scope, response) }
                }
            }, { ex ->
                trySafelyCanceling { reactToNetCallRequestFailed.commit(scope, ex) }
            })
        }
    }
}

@Tag(NET_CALL)
var netCall = with<Call>("https://httpbin.org/delay/1")(::buildNetworkRequest)
private var reactToNetCallResponseReceived: JobResponseFunction = @Tag(NET_SUCCESS) { _, response ->
    hasInternet = response.isSuccessful
    if (response.isSuccessful)
        lastNetCallResponseTime = now()
    response.close()
    if (infoLogIsNotBypassed)
        info(INET_TAG, "Received response for internet availability.")
}
private var reactToNetCallRequestFailed: JobThrowableFunction = @Tag(NET_ERROR) { _, _ ->
    if (warningLogIsNotBypassed)
        warning(INET_TAG, "Failed to send http request for internet availability.")
}

@Tag(NET_DELAY)
private var netCallDelayTime = -1L
    get() = if (field < 0) getDelayTime(netCallTimeInterval, lastNetCallResponseTime) else field
@Tag(NET_MIN_INTERVAL)
private var minNetCallTimeInterval = 5000L
@Tag(NET_INTERVAL)
private var netCallTimeInterval = minNetCallTimeInterval
    set(value) {
        field = maxOf(value, minNetCallTimeInterval)
    }
private var lastNetCallResponseTime = 0L
    set(value) {
        if (value == 0L || value > field)
            field = value
    }
private val isNetCallTimeIntervalExceeded
    get() = isTimeIntervalExceeded(netCallTimeInterval, lastNetCallResponseTime)
private var repeatNetCallback = true

fun buildNetworkRequest(
    command: String,
    method: String = "GET",
    headers: Headers? = null,
    body: RequestBody? = null,
    retry: Boolean = false
) = OkHttpClient.Builder()
    .retryOnConnectionFailure(retry)
    .build()
    .newCall(
        Request.Builder()
            .url(command)
            .apply { if (headers !== null) headers(headers) }
            .method(method, body)
            .build())
operator fun NetCall.set(param: String, value: Any?) {
    // keep old value
    synchronized(this) {
        when (param) {
            NET_CALL -> netCall = take(value)
            NET_FUNCTION -> networkCallFunction = take(value)
            NET_SUCCESS -> reactToNetCallResponseReceived = take(value)
            NET_ERROR -> reactToNetCallRequestFailed = take(value)
            NET_DELAY -> netCallDelayTime = take(value)
            NET_INTERVAL -> netCallTimeInterval = take(value)
            NET_MIN_INTERVAL -> minNetCallTimeInterval = take(value)
        }
    }
}

private typealias NetCall = KCallable<Call>
private inline fun <reified T : Any> take(value: Any?): T = value.asType()!!
private typealias Respond = (Response) -> Unit
private fun KCallable<Call>.commit(respond: Respond) {
    markTagAsFunction()
    respond(call().asType()!!)
}
private typealias JobResponseFunction = (Any?, Response) -> Unit
private fun JobResponseFunction.commit(scope: Any?, response: Response) {
    markTagAsFunction()
    invoke(scope, response)
}
private typealias JobThrowableFunction = (Any?, Throwable) -> Unit
private fun JobThrowableFunction.commit(scope: Any?, ex: Throwable) {
    markTagAsFunction()
    invoke(scope, ex)
}

@Retention(SOURCE)
@Target(FUNCTION, PROPERTY)
private annotation class NetworkListener

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

const val NET = "net"
const val NET_CALL = "$NET.call"
const val NET_FUNCTION = "$NET.function"
const val NET_SUCCESS = "$NET.success"
const val NET_ERROR = "$NET.error"
const val NET_DELAY = "$NET.delay"
const val NET_INTERVAL = "$NET.interval"
const val NET_MIN_INTERVAL = "$NET.min-interval"
const val INET_TAG = "INTERNET"