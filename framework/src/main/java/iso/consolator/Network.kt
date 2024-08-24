@file:JvmName(JVM_CLASS_NAME)
@file:JvmMultifileClass

package iso.consolator

import android.net.*
import android.net.ConnectivityManager.*
import android.net.NetworkCapabilities.*
import androidx.lifecycle.*
import ctx.consolator.*
import kotlin.annotation.AnnotationRetention.*
import kotlin.annotation.AnnotationTarget.*
import kotlin.reflect.*
import kotlinx.coroutines.*
import okhttp3.*
import kotlinx.coroutines.Dispatchers.IO

internal val network
    get() = connectivityManager.activeNetwork

internal val isConnected
    get() = connectivityRequest?.canBeSatisfiedBy(networkCapabilities) ?: false

internal var hasInternet = false
    get() = isConnected && field

internal val hasMobile
    get() = networkCapabilities?.hasTransport(TRANSPORT_CELLULAR) ?: false

internal val hasWifi
    get() = networkCapabilities?.hasTransport(TRANSPORT_WIFI) ?: false

internal val networkCapabilities
    get() = with(connectivityManager) { getNetworkCapabilities(activeNetwork) }

private val connectivityManager
    get() = foregroundContext.getSystemService(ConnectivityManager::class.java)!!

@Coordinate @Tag(NET_CAP_REGISTER)
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
            trySafely { reactToNetworkCapabilitiesChanged.invoke(network, networkCapabilities) } } }
        .also { field = it }

internal var reactToNetworkCapabilitiesChanged: (Network, NetworkCapabilities) -> Unit = { network, networkCapabilities ->
    commit @Tag(NET_CAP_UPDATE) {
        updateNetworkCapabilities(network, networkCapabilities) } }

@Coordinate @Tag(INET_REGISTER)
internal fun CoroutineScope.registerInternetCallback() {
    relaunch(::networkCaller, calls, IO, step = ::repeatNetworkCallFunction) }

@Coordinate @Tag(INET_REGISTER)
fun LifecycleOwner.registerInternetCallback() {
    relaunch(::networkCaller, calls, IO, step = ::repeatNetworkCallFunction) }

internal fun pauseInternetCallback() {
    isNetCallbackResumed = false }

internal fun resumeInternetCallback() {
    isNetCallbackResumed = true }

@Tag(INET_UNREGISTER)
fun unregisterInternetCallback() {
    networkCaller?.cancel()
    netCallDelayTime = -1L
    clearInternetCallbackObjects() }

@JobTreeRoot @NetworkListener
@Coordinate @Tag(INET)
internal var networkCaller: Job? = null
    set(value) {
        // update addressable layer
        field = value }

@Tag(INET_CALL)
internal var netCall: Call? = null
    private set

private suspend fun repeatNetworkCallFunction(scope: CoroutineScope) {
    scope.repeatSuspended(
        block = networkCallFunction,
        delayTime = @Tag(INET_DELAY) { netCallDelayTime }) }

private var networkCallFunction: JobFunction =
    @Tag(INET_FUNCTION) { scope ->
    if (isNetCallbackResumed && isNetCallTimeIntervalExceeded)
        scope.asCoroutineScope()?.run {
        log(info, INET_TAG, "Trying to send out http request for network caller...")
        with(::netCall) {
        with(scope) {
        commit(this) {
            set(this, INET_CALL,
                @Keep ::buildNetCall.with("https://httpbin.org/delay/1")) // convert to contextual function
            launch { send(this) } } } } } }

private var reactToNetCallResponseReceived: JobResponseFunction =
    @Tag(INET_SUCCESS) { scope, response ->
    with(response) {
        hasInternet = isSuccessful
        if (isSuccessful)
            lastNetCallResponseTime = now()
        netCallDelayTime = -1L
        close() }
    scope.asCoroutineScope()?.log?.invoke(info, INET_TAG, "Received response for network caller.") }

private var reactToNetCallRequestFailed: JobThrowableFunction =
    @Tag(INET_ERROR) { scope, _ ->
    hasInternet = false
    scope.asCoroutineScope()?.log?.invoke(warning, INET_TAG, "Failed to send http request for network caller.") }

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

private fun <R> NetCall.commit(scope: Any?, block: () -> R) =
    scope.asCoroutineScope()?.run {
    lock(this, block) }

private fun <R> NetCall.lock(scope: Any?, block: () -> R) =
    synchronized(asCallable(), block)

private fun NetCall.asCallable() =
    if (this === ::netCall) ::netCall
    else if (this is CallableReference<*>) asType()!!
    else asProperty().get().asReference() // register lock

private fun NetCall.asProperty() = this as KProperty

private suspend fun NetCall.send(scope: Any?) =
    scope.asCoroutineScope()?.run {
    tryCancelingForResult({
        exec(this) { response ->
        trySafelyCanceling {
            reactToNetCallResponseReceived.commit(this, response) } }
    }, { ex ->
        trySafelyCanceling {
            reactToNetCallRequestFailed.commit(this, ex) }
    }) }

private fun NetCall.exec(scope: Any?, respond: Respond) {
    markTag(calls)
    this[INET_CALL].asType<NetCall>()?.call()
    ?.execute()
    ?.run(respond) }

private fun JobResponseFunction.commit(scope: Any?, response: Response) {
    markTag(calls)
    invoke(scope, response) }

private fun JobThrowableFunction.commit(scope: Any?, ex: Throwable) {
    markTag(calls)
    invoke(scope, ex) }

private var calls: FunctionSet? = null

internal operator fun NetCall.get(id: TagType): Any? = when {
    id === INET_CALL -> this
    id === INET_FUNCTION -> networkCallFunction
    id === INET_SUCCESS -> reactToNetCallResponseReceived
    id === INET_ERROR -> reactToNetCallRequestFailed
    id === INET_DELAY -> netCallDelayTime
    id === INET_INTERVAL -> netCallTimeInterval
    id === INET_MIN_INTERVAL -> minNetCallTimeInterval
    else -> null }

internal operator fun NetCall.set(scope: Any?, id: TagType, value: Any?) {
    if (value !== null && value.isKept) {
        value.markSequentialTag(INET_CALL, id, calls) }
    lock(id) { when {
        id === INET_CALL -> netCall = take(value)
        id === INET_FUNCTION -> networkCallFunction = take(value)
        id === INET_SUCCESS -> reactToNetCallResponseReceived = take(value)
        id === INET_ERROR -> reactToNetCallRequestFailed = take(value)
        id === INET_DELAY -> netCallDelayTime = take(value)
        id === INET_INTERVAL -> netCallTimeInterval = take(value)
        id === INET_MIN_INTERVAL -> minNetCallTimeInterval = take(value) } } }

internal interface NetworkContext : SchedulerContext

@Retention(SOURCE)
@Target(FUNCTION, PROPERTY)
private annotation class NetworkListener

internal fun buildNetCall(scope: Any?, key: Any) = buildHttpCall(key)

private fun NetCall.with(key: Any): Call? = call(key)

internal fun buildHttpCall(key: Any, method: String = "GET", body: RequestBody? = null, headers: Headers? = null, retry: Boolean = false) =
    when (key) {
        is Call -> key
        else ->
            httpClient(retry)
            .newCall { newRequest {
                url(key.asUrl()).also {
                method(method, body)
                headers?.run(::headers) } } } }

private fun httpClient(retry: Boolean = false) =
    OkHttpClient.Builder()
    .retryOnConnectionFailure(retry)
    .build()

private inline fun OkHttpClient.newCall(block: Request.Builder.() -> Request) =
    newCall(Request.Builder().block())

private inline fun Request.Builder.newRequest(block: Request.Builder.() -> Request.Builder) =
    block().build()

private fun Any.asUrl() = toString()

private var connectivityRequest: NetworkRequest? = null
    get() = field ?: buildNetworkRequest {
        addCapability(NET_CAPABILITY_INTERNET) }
        .also { field = it }

private inline fun buildNetworkRequest(block: NetworkRequest.Builder.() -> Unit) =
    NetworkRequest.Builder().apply(block).build()

private fun clearNetworkCallbackObjects() {
    networkCallback = null
    connectivityRequest = null }
private fun clearInternetCallbackObjects() {
    networkCaller = null
    netCall = null }

private typealias NetCall = KCallable<Call?>
private typealias Respond = (Response) -> Unit
private typealias JobResponseFunction = (Any?, Response) -> Unit
private typealias JobThrowableFunction = (Any?, Throwable) -> Unit

private inline fun <reified T : Any> take(value: Any?): T = value.asType()!!

internal const val NET_CAP = "$NET-cap"
internal const val NET_CAP_REGISTER = "$NET_CAP.$REGISTER"
internal const val NET_CAP_UNREGISTER = "$NET_CAP.$UNREGISTER"
internal const val NET_CAP_UPDATE = "$NET_CAP.$UPDATE"

internal const val INET = "inet"
internal const val INET_REGISTER = "$INET.$REGISTER"
internal const val INET_UNREGISTER = "$INET.$UNREGISTER"
internal const val INET_CALL = "$INET.$CALL"
internal const val INET_FUNCTION = "$INET.$FUNC"
internal const val INET_SUCCESS = "$INET.$SUCCESS"
internal const val INET_ERROR = "$INET.$ERROR"
internal const val INET_DELAY = "$INET.$DELAY"
internal const val INET_INTERVAL = "$INET.$INTERVAL"
internal const val INET_MIN_INTERVAL = "$INET.$MIN_INTERVAL"
internal const val INET_TAG = "INTERNET"