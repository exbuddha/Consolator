package net.consolator

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

fun registerNetworkCallback() {
    connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
}
fun unregisterNetworkCallback() {
    connectivityManager.unregisterNetworkCallback(networkCallback!!)
    clearNetworkCallbackObjects()
}
private fun clearNetworkCallbackObjects() {
    networkCallback = null
    connectivityRequest = null
}

var reactToNetworkCapabilitiesChanged: (Network, NetworkCapabilities) -> Unit = { _, _ -> }
private var networkCallback: NetworkCallback? = null
    get() = field ?: object : NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            trySafely { reactToNetworkCapabilitiesChanged.invoke(network, networkCapabilities) }
        }
    }.also { field = it }

fun LifecycleOwner.registerInternetCallback() {
    relaunchJobIfNotActive(::networkCaller, IO) {
        repeatSuspended(::isActive,
            networkCallFunction,
            ::netCallDelayTime)
    }
}
fun registerInternetCallback() {
    Scheduler.relaunchJobIfNotActive(::networkCaller, IO) {
        repeatSuspended(::isActive,
            networkCallFunction,
            ::netCallDelayTime)
    }
}
fun pauseInternetCallback() {
    repeatNetCallback = false
}
fun resumeInternetCallback() {
    repeatNetCallback = true
}
fun unregisterInternetCallback() {
    networkCaller?.cancel()
    clearInternetCallbackObjects()
}
private fun clearInternetCallbackObjects() {
    networkCaller = null
}

@JobTreeRoot @NetworkListener @Tag(INET)
var networkCaller: Job? = null
    set(value) {
        // update addressable layer?
        field = value
    }
private var networkCallFunction: JobFunction = @Tag(INET_FUNCTION) { scope ->
    if (repeatNetCallback && isNetCallTimeIntervalExceeded) {
        if (infoLogIsNotBypassed)
            info(INET_TAG, "Trying to send out http request for network caller...")
        synchronized(::netCall) {
            tryCancelingForResult({
                netCall.asType<NetCall>()!!.commit { response ->
                    trySafelyCanceling { reactToNetCallResponseReceived.commit(scope, response) } }
            }, { ex ->
                trySafelyCanceling { reactToNetCallRequestFailed.commit(scope, ex) }
            })
        }
    }
}

@Tag(INET_CALL)
var netCall = with<Call>("https://httpbin.org/delay/1")(::buildNetworkRequest)
    private set
private var reactToNetCallResponseReceived: JobResponseFunction = @Tag(INET_SUCCESS) { _, response ->
    with(response) {
        hasInternet = isSuccessful
        if (isSuccessful)
            lastNetCallResponseTime = now()
        close()
    }
    if (infoLogIsNotBypassed)
        info(INET_TAG, "Received response for internet availability.")
}
private var reactToNetCallRequestFailed: JobThrowableFunction = @Tag(INET_ERROR) { _, _ ->
    if (warningLogIsNotBypassed)
        warning(INET_TAG, "Failed to send http request for internet availability.")
}

@Tag(INET_DELAY)
private var netCallDelayTime = -1L
    get() = if (field < 0) getDelayTime(netCallTimeInterval, lastNetCallResponseTime) else field
@Tag(INET_MIN_INTERVAL)
private var minNetCallTimeInterval = 5000L
@Tag(INET_INTERVAL)
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
    cmd: String,
    method: String = "GET",
    headers: Headers? = null,
    body: RequestBody? = null,
    retry: Boolean = false
) = OkHttpClient.Builder()
    .retryOnConnectionFailure(retry)
    .build()
    .newCall(
        Request.Builder()
            .url(cmd.asUrl())
            .apply { if (headers !== null) headers(headers) }
            .method(method, body)
            .build())
operator fun NetCall.set(cmd: String, value: Any?) {
    // keep old value
    synchronized(asProperty()) {
        when (cmd) {
            INET_CALL -> netCall = take(value)
            INET_FUNCTION -> networkCallFunction = take(value)
            INET_SUCCESS -> reactToNetCallResponseReceived = take(value)
            INET_ERROR -> reactToNetCallRequestFailed = take(value)
            INET_DELAY -> netCallDelayTime = take(value)
            INET_INTERVAL -> netCallTimeInterval = take(value)
            INET_MIN_INTERVAL -> minNetCallTimeInterval = take(value)
        }
    }
}
operator fun NetCall.get(cmd: String): Result<Response> = TODO()
private fun String.asUrl() = this

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

const val INET = "inet"
const val INET_CALL = "$INET.call"
const val INET_FUNCTION = "$INET.function"
const val INET_SUCCESS = "$INET.success"
const val INET_ERROR = "$INET.error"
const val INET_DELAY = "$INET.delay"
const val INET_INTERVAL = "$INET.interval"
const val INET_MIN_INTERVAL = "$INET.min-interval"
const val INET_TAG = "INTERNET"