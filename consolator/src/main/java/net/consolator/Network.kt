package net.consolator

import android.net.*
import android.net.ConnectivityManager.*
import android.net.NetworkCapabilities.*
import androidx.lifecycle.*
import kotlin.annotation.AnnotationRetention.*
import kotlin.annotation.AnnotationTarget.*
import kotlin.reflect.*
import kotlinx.coroutines.*
import okhttp3.*
import kotlinx.coroutines.Dispatchers.IO

val network
    get() = connectivityManager.activeNetwork

val isConnected
    get() = connectivityRequest?.canBeSatisfiedBy(networkCapabilities) ?: false

var hasInternet = false
    get() = isConnected && field

val hasMobile
    get() = networkCapabilities?.hasTransport(TRANSPORT_CELLULAR) ?: false

val hasWifi
    get() = networkCapabilities?.hasTransport(TRANSPORT_WIFI) ?: false

val networkCapabilities
    get() = with(connectivityManager) { getNetworkCapabilities(activeNetwork) }

private val connectivityManager
    get() = foregroundContext.getSystemService(ConnectivityManager::class.java)!!

@Tag(NET_CAP_REGISTER)
fun registerNetworkCallback() {
    networkCallback?.apply(connectivityManager::registerDefaultNetworkCallback) }

@Tag(NET_CAP_UNREGISTER)
fun unregisterNetworkCallback() {
    networkCallback?.apply(connectivityManager::unregisterNetworkCallback)
    clearNetworkCallbackObjects() }

private var networkCallback: NetworkCallback? = null
    get() = field ?: object : NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            trySafely { reactToNetworkCapabilitiesChanged.invoke(network, networkCapabilities) } }
    }.also { field = it }

var reactToNetworkCapabilitiesChanged: (Network, NetworkCapabilities) -> Unit = { network, networkCapabilities ->
    commit @Tag(NET_CAP_UPDATE) { updateNetworkCapabilities(network, networkCapabilities) } }

@Tag(INET_REGISTER)
fun LifecycleOwner.registerInternetCallback() {
    relaunch(::networkCaller, IO, step = ::repeatNetworkCallFunction) }

@Tag(INET_REGISTER)
fun registerInternetCallback() {
    Scheduler.relaunch(::networkCaller, IO, step = ::repeatNetworkCallFunction) }

fun pauseInternetCallback() {
    isNetCallbackResumed = false }

fun resumeInternetCallback() {
    isNetCallbackResumed = true }

@Tag(INET_UNREGISTER)
fun unregisterInternetCallback() {
    networkCaller?.cancel()
    netCallDelayTime = -1L
    clearInternetCallbackObjects() }

@Tag(INET)
@JobTreeRoot @NetworkListener
var networkCaller: Job? = null
    set(value) {
        // update addressable layer
        field = value }

@Tag(INET_CALL)
var netCall: Call? = null
    private set

private suspend fun repeatNetworkCallFunction(scope: CoroutineScope) {
    scope.repeatSuspended(
        block = networkCallFunction,
        delayTime = @Tag(INET_DELAY) { netCallDelayTime }) }

private var networkCallFunction: JobFunction = @Tag(INET_FUNCTION) { scope ->
    if (isNetCallbackResumed && isNetCallTimeIntervalExceeded) {
        info(INET_TAG, "Trying to send out http request for network caller...")
        ::netCall.commit(scope) {
            ::netCall[INET_CALL] = ::buildNetCallRequest.with("https://httpbin.org/delay/1")()
            tryCancelingForResult({
                ::netCall.exec { response ->
                    trySafelyCanceling { reactToNetCallResponseReceived.commit(scope, response) } }
            }, { ex ->
                trySafelyCanceling { reactToNetCallRequestFailed.commit(scope, ex) }
            }) } } }

private var reactToNetCallResponseReceived: JobResponseFunction = @Tag(INET_SUCCESS) { _, response ->
    with(response) {
        hasInternet = isSuccessful
        if (isSuccessful)
            lastNetCallResponseTime = now()
        netCallDelayTime = -1L
        close() }
    info(INET_TAG, "Received response for internet availability.") }

private var reactToNetCallRequestFailed: JobThrowableFunction = @Tag(INET_ERROR) { _, _ ->
    hasInternet = false
    warning(INET_TAG, "Failed to send http request for internet availability.") }

@Tag(INET_DELAY)
private var netCallDelayTime = -1L
    get() = if (field < 0)
        getDelayTime(netCallTimeInterval, lastNetCallResponseTime)
    else field

@Tag(INET_MIN_INTERVAL)
private var minNetCallTimeInterval = 5000L

@Tag(INET_INTERVAL)
private var netCallTimeInterval = minNetCallTimeInterval
    set(value) {
        field = maxOf(value, minNetCallTimeInterval) }

private var lastNetCallResponseTime = 0L
    set(value) {
        if (value > field || value == 0L)
            field = value }

private val isNetCallTimeIntervalExceeded
    get() = isTimeIntervalExceeded(netCallTimeInterval, lastNetCallResponseTime)

private var isNetCallbackResumed = true

fun buildNetCallRequest(cmd: String) = buildHttpRequest(cmd)

fun buildHttpRequest(
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

private fun String.asUrl() = this

private fun <R> NetCall.commit(scope: Any?, block: () -> R) =
    lock(INET_CALL, block)

private fun <R> NetCall.lock(cmd: String, block: () -> R) =
    synchronized(asNullable(), block)

private fun NetCall.asNullable() =
    if (this === ::netCall) ::netCall
    else get().asNullable()

private fun NetCall.exec(cmd: String = INET_CALL, respond: Respond) {
    markTag()
    respond(this[cmd].asType<NetCall>()!!.call()!!.execute()) }

private fun JobResponseFunction.commit(scope: Any?, response: Response) {
    markTag()
    invoke(scope, response) }

private fun JobThrowableFunction.commit(scope: Any?, ex: Throwable) {
    markTag()
    invoke(scope, ex) }

operator fun NetCall.get(cmd: String): Any? = when {
    cmd === INET_CALL -> this
    cmd === INET_FUNCTION -> networkCallFunction
    cmd === INET_SUCCESS -> reactToNetCallResponseReceived
    cmd === INET_ERROR -> reactToNetCallRequestFailed
    cmd === INET_DELAY -> netCallDelayTime
    cmd === INET_INTERVAL -> netCallTimeInterval
    cmd === INET_MIN_INTERVAL -> minNetCallTimeInterval
    else -> null }

operator fun NetCall.set(cmd: String, value: Any?) {
    // keep old value
    lock(cmd) { when {
        cmd === INET_CALL -> netCall = take(value)
        cmd === INET_FUNCTION -> networkCallFunction = take(value)
        cmd === INET_SUCCESS -> reactToNetCallResponseReceived = take(value)
        cmd === INET_ERROR -> reactToNetCallRequestFailed = take(value)
        cmd === INET_DELAY -> netCallDelayTime = take(value)
        cmd === INET_INTERVAL -> netCallTimeInterval = take(value)
        cmd === INET_MIN_INTERVAL -> minNetCallTimeInterval = take(value) } } }

@Retention(SOURCE)
@Target(FUNCTION, PROPERTY)
private annotation class NetworkListener

private var connectivityRequest: NetworkRequest? = null
    get() = field ?: buildNetworkRequest {
        addCapability(NET_CAPABILITY_INTERNET)
    }.also { field = it }

private inline fun buildNetworkRequest(block: NetworkRequest.Builder.() -> Unit) =
    NetworkRequest.Builder().apply(block).build()

private fun clearNetworkCallbackObjects() {
    networkCallback = null
    connectivityRequest = null }
private fun clearInternetCallbackObjects() {
    networkCaller = null
    netCall = null }

private typealias NetCall = KProperty<Call?>
private typealias Respond = (Response) -> Unit
private typealias JobResponseFunction = (Any?, Response) -> Unit
private typealias JobThrowableFunction = (Any?, Throwable) -> Unit

private inline fun <reified T : Any> take(value: Any?): T = value.asType()!!

const val NET_CAP = "$NET-cap"
const val NET_CAP_REGISTER = "$NET_CAP.$REGISTER"
const val NET_CAP_UNREGISTER = "$NET_CAP.$UNREGISTER"
const val NET_CAP_UPDATE = "$NET_CAP.$UPDATE"

const val INET = "inet"
const val INET_REGISTER = "$INET.$REGISTER"
const val INET_UNREGISTER = "$INET.$UNREGISTER"
const val INET_CALL = "$INET.$CALL"
const val INET_FUNCTION = "$INET.$FUNC"
const val INET_SUCCESS = "$INET.$SUCCESS"
const val INET_ERROR = "$INET.$ERROR"
const val INET_DELAY = "$INET.$DELAY"
const val INET_INTERVAL = "$INET.$INTERVAL"
const val INET_MIN_INTERVAL = "$INET.$MIN_INTERVAL"
const val INET_TAG = "INTERNET"