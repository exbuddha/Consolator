@file:JvmName(JVM_CLASS_NAME)
@file:JvmMultifileClass

package iso.consolator

import android.app.*
import android.content.*
import android.content.pm.*
import android.net.*
import android.util.*
import androidx.annotation.*
import androidx.core.content.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import androidx.room.*
import ctx.consolator.*
import data.consolator.*
import data.consolator.dao.*
import data.consolator.entity.*
import iso.consolator.Path.*
import java.lang.*
import java.lang.ref.*
import java.util.*
import kotlin.annotation.AnnotationRetention.*
import kotlin.annotation.AnnotationTarget.*
import kotlin.reflect.*
import kotlin.reflect.jvm.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import android.Manifest.permission.ACCESS_NETWORK_STATE
import android.Manifest.permission.INTERNET

lateinit var instance: Application
var service: BaseServiceScope? = null

internal var receiver: BroadcastReceiver? = null
    get() = field.singleton().also { field = it }

internal val foregroundContext: Context
    get() = service.asContext() ?: instance

internal val foregroundActivity: Activity?
    get() = foregroundLifecycleOwner?.let {
        if (it is Activity) it
        else it.asFragment()?.activity }

var foregroundLifecycleOwner: LifecycleOwner? = null

const val VIEW_MIN_DELAY = 300L

internal typealias ContextStep = suspend Context.(Any?) -> Any?

internal fun Context.change(stage: ContextStep) =
    commit { stage(this) }

internal fun Context.changeLocally(owner: LifecycleOwner, stage: ContextStep) =
    commit { stage(this) }

internal fun Context.changeBroadly(ref: WeakContext = asWeakReference(), stage: ContextStep) =
    commit { stage(this) }

internal fun Context.changeGlobally(ref: WeakContext = asWeakReference(), owner: LifecycleOwner, stage: ContextStep) =
    commit { stage(this) }

@Diverging([STAGE_BUILD_APP_DB])
fun Context.stageAppDbCreated(scope: Any?) {
    // bootstrap
}

@Diverging([STAGE_BUILD_SESSION])
fun Context.stageSessionCreated(scope: Any?) {
    // update db records
}

@Diverging([STAGE_BUILD_LOG_DB])
internal fun Context.stageLogDbCreated(scope: Any?) {
    mainUncaughtExceptionHandler = @Tag(UNCAUGHT_DB) ExceptionHandler { th, ex ->
        // record in db safely
    }
}

@Diverging([STAGE_BUILD_NET_DB])
internal fun Context.stageNetDbInitialized(scope: Any?) {
    // update net function pointers
}

suspend fun buildSession() {
    if (isSessionNull)
        buildNewSession(foregroundContext.startTime()) }

fun Job.isSessionCreated() = isSessionNotNull

internal suspend fun updateNetworkState() {
    NetworkDao {
    updateNetworkState(
        isConnected,
        hasInternet,
        hasMobile,
        hasWifi) } }

internal suspend fun updateNetworkCapabilities(network: Network? = iso.consolator.network, networkCapabilities: NetworkCapabilities? = iso.consolator.networkCapabilities) {
    networkCapabilities?.run {
    NetworkDao {
    updateNetworkCapabilities(
        Json.encodeToString(capabilities),
        linkDownstreamBandwidthKbps,
        linkUpstreamBandwidthKbps,
        signalStrength,
        network.hashCode()) } } }

internal inline fun <reified D : RoomDatabase> Context.buildDatabase() =
    buildDatabase(D::class, this)

internal inline fun <reified D : RoomDatabase> Context.commitBuildDatabase(instance: KMutableProperty<out D?>) =
    instance.requireAsync(constructor = { buildDatabase<D>().also(instance::set) })

fun Context.buildAppDatabase() =
    commitBuildDatabase(::db)

fun Job.isAppDbCreated() = isAppDbNotNull

internal val isAppDbNull get() = db === null
internal val isAppDbNotNull get() = db !== null
internal val isSessionNull get() = session === null
internal val isSessionNotNull get() = session !== null
val isLogDbNull get() = logDb === null
internal val isLogDbNotNull get() = logDb !== null
val isNetDbNull get() = netDb === null
internal val isNetDbNotNull get() = netDb !== null
internal val isAppDbOrSessionNull get() = isAppDbNull || isSessionNull
internal val isLogDbOrNetDbNull get() = isLogDbNull || isNetDbNull

internal fun clearAppDbObjects() {
    db = null }
internal fun clearLogDbObjects() {
    logDb = null }
internal fun clearNetDbObjects() {
    netDb = null }
internal fun clearAllDbObjects() {
    clearAppDbObjects()
    clearLogDbObjects()
    clearNetDbObjects()
    clearObjects() }
internal fun clearSessionObjects() {
    session = null }

val Context.isNetworkStateAccessPermitted
    get() = isPermissionGranted(ACCESS_NETWORK_STATE)

val Context.isInternetAccessPermitted
    get() = isPermissionGranted(INTERNET)

internal fun Context.isPermissionGranted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

internal fun Context.intendFor(cls: Class<*>) = Intent(this, cls)
private fun Context.intendFor(cls: AnyKClass) = intendFor(cls.java)

interface ReferredContext { var ref: WeakContext? }

typealias WeakContext = WeakReference<out Context>

fun Context.asWeakReference() =
    if (this is ReferredContext) ref!!
    else WeakReference(this)

fun <T : Context> WeakReference<out T>?.unique(context: T) =
    require { WeakReference(context) }

internal fun Context.startTime() = asUniqueContext()?.startTime ?: -1L

internal fun getDelayTime(interval: Long, last: Long) =
    interval + last - now()

internal fun isTimeIntervalExceeded(interval: Long, last: Long) =
    getDelayTime(interval, last) <= 0 || last == 0L

internal inline fun <R> Boolean.then(block: () -> R) =
    if (this) block() else null

internal inline fun <R> Boolean.otherwise(block: () -> R) =
    not().then(block)

internal inline fun <R> Predicate.then(block: () -> R) =
    this().then(block)

internal inline fun <R> Predicate.otherwise(block: () -> R) =
    this().not().then(block)

internal fun Predicate.not(): Predicate = { this().not() }

@Suppress("UNCHECKED_CAST")
internal fun <R> Unit.type() = this as R

internal inline fun <reified T : Throwable, R> tryCatching(block: () -> R, predicate: ThrowablePredicate = { it is T }, exit: ThrowableNothing = { throw it }) =
    try { block() }
    catch (ex: Throwable) {
    if (predicate(ex)) exit(ex)
    else throw ex }

internal inline fun <reified T : Throwable, reified U : Throwable, R> tryMapping(block: () -> R) =
    tryCatching<T, _>(block) { with(it) { throw U::class.new(message, cause) } }

internal inline fun <reified T : Throwable, reified U : Throwable, R> tryFlatMapping(block: () -> R) =
    tryCatching<T, _>(block) { throw it.cause?.run { U::class.new(message, cause) } ?: U::class.new() }

internal inline fun <reified T : Throwable, reified U : Throwable, R> tryOuterMapping(block: () -> R) =
    tryCatching<T, _>(block, { it !is T }) { with(it) { throw U::class.new(message, cause) } }

internal inline fun <reified T : Throwable, reified U : Throwable, R> tryOuterFlatMapping(block: () -> R) =
    tryCatching<T, _>(block, { it !is T }) { throw it.cause?.run { U::class.new(message, cause) } ?: U::class.new() }

internal inline fun <reified T : Throwable, R, S : R> tryMapping(block: () -> R, predicate: ThrowablePredicate = { it is T }, transform: (Throwable) -> S) =
    try { block() }
    catch (ex: Throwable) {
    if (predicate(ex)) transform(ex)
    else throw ex }

internal inline fun <reified T : Throwable, R> tryBypassing(block: () -> R) =
    tryMapping<T, _, _>(block) { null }

internal inline fun <R> tryAvoiding(block: () -> R) =
    try { block() } catch (_: Propagate) {}

internal inline fun <R, S : R> tryPropagating(block: () -> R, transform: (Throwable) -> S) =
    try { block() }
    catch (ex: Propagate) { throw ex }
    catch (ex: Throwable) { transform(ex) }

inline fun <R> trySafely(block: () -> R) =
    try { block() } catch (_: Throwable) {}

internal inline fun <R> trySafelyForResult(block: () -> R) =
    try { block() } catch (_: Throwable) { null }

internal inline fun <R, S : R> tryFinally(block: () -> R, final: (R?) -> S): R {
    var result: R? = null
    return try { block().also { result = it } }
    catch(ex: Throwable) { throw ex }
    finally { final(result) } }

internal inline fun <R, S : R> tryFinallyForResult(block: () -> R, final: (R?) -> S): R? {
    var result: R? = null
    return try { block().also { result = it } }
    catch(_: Throwable) { null }
    finally { final(result) } }

inline fun <R> tryCanceling(block: () -> R) =
    try { block() } catch (ex: Throwable) { throw CancellationException(null, ex) }

internal inline fun <R> trySafelyCanceling(block: () -> R) =
    tryCancelingForResult(block)

internal inline fun <R> tryCancelingForResult(block: () -> R, exit: (Throwable) -> R? = { null }) =
    try { block() } catch (ex: CancellationException) { throw ex } catch (ex: Throwable) { exit(ex) }

suspend inline fun <R> tryCancelingSuspended(crossinline block: suspend () -> R) = tryCanceling { block() }
suspend inline fun <T, R> tryCancelingSuspended(scope: T, crossinline block: suspend T.() -> R) = tryCanceling { scope.block() }
suspend inline fun <T, R> tryCancelingSuspended(crossinline scope: suspend () -> T, crossinline block: suspend T.() -> R) = tryCancelingSuspended(scope(), block)

internal inline fun <R> tryInterrupting(block: () -> R) =
    try { block() } catch (ex: Throwable) { throw InterruptedException() }

internal fun <R> tryInterrupting(step: suspend CoroutineScope.() -> R) =
    try { blockOf(step)() } catch (ex: Throwable) { throw InterruptedStepException(step, cause = ex) }

internal inline fun <R> trySafelyInterrupting(block: () -> R) =
    try { block() } catch (ex: InterruptedException) { throw ex } catch (_: Throwable) {}

internal fun <R> trySafelyInterrupting(step: suspend CoroutineScope.() -> R) =
    try { blockOf(step)() } catch (ex: InterruptedException) { throw InterruptedStepException(step, cause = ex) } catch (_: Throwable) {}

internal inline fun <R> tryInterruptingForResult(noinline step: suspend CoroutineScope.() -> R, exit: (Throwable) -> R? = { null }) =
    try { blockOf(step)() } catch (ex: InterruptedException) { throw InterruptedStepException(step, cause = ex) } catch (ex: Throwable) { exit(ex) }

internal inline fun <T, reified R> Array<out T>.mapToTypedArray(transform: (T) -> R) =
    map(transform).toTypedArray()

internal fun <T> Array<out T>.secondOrNull(): T? =
    if (size > 1) get(1)
    else null

internal inline fun <reified T : Any> Any?.asTypeOf(instance: T): T? =
    instance::class.safeCast(this)

inline fun <reified T : Any> Any?.asType(): T? =
    T::class.safeCast(this)

internal inline fun <reified T : Any> T?.singleton(vararg args: Any?, lock: Any = T::class.lock) =
    commitAsyncForResult(lock, { this === null }, { T::class.new(*args) }, { this }) as T

internal inline fun <T> T?.require(constructor: () -> T) =
    this ?: constructor()

internal inline fun <reified T : Any> T?.reconstruct(vararg args: Any?, constructor: KCallable<T?> = T::class::new) =
    this ?: constructor.call(*args)

internal inline fun <reified T : Any> T?.reconstruct(vararg args: Any?) =
    this ?: T::class.new(*args)

internal val <T : Any> KClass<out T>.lock
    get() = objectInstance ?: this

internal fun <T : Any> KClass<out T>.reconstruct(vararg args: Any?) =
    if (isCompanion) objectInstance!!
    else new(*args)

internal fun <T : Any> KClass<out T>.new(vararg args: Any?) =
    if (args.isEmpty()) emptyConstructor.call()
    else firstConstructor.call(*args)

internal val <T : Any> KClass<out T>.emptyConstructor
    get() = constructors.first { it.parameters.isEmpty() }

internal val <T : Any> KClass<out T>.firstConstructor
    get() = constructors.first()

internal inline fun <reified T> KMutableProperty<out T?>.reconstruct(provider: Any = T::class) =
    apply { renew {
    if (provider is AnyKClass)
        provider.emptyConstructor.call()
    else
        provider.asObjectProvider()?.invoke(T::class) } }

internal inline fun <T> KMutableProperty<out T?>.renew(constructor: () -> T? = ::get) {
    if (isNull())
        set(constructor()) }

internal inline fun <T> KMutableProperty<out T?>.require(predicate: (T) -> Boolean = ::trueWhenNull, constructor: () -> T? = ::get) =
    get().let { old ->
        if (old === null || predicate(old))
            constructor()!!.also(::set)
        else old }

internal inline fun <T> KMutableProperty<out T?>.requireAsync(predicate: (T) -> Boolean = ::trueWhenNull, constructor: () -> T? = ::get) =
    require(predicate) {
        synchronized(this) {
            require(predicate, constructor) } }

internal fun <T> KMutableProperty<out T?>.set(value: T?) = setter.call(value)

internal fun <T> KProperty<T?>.get() = getter.call()

internal fun <T> KProperty<T?>.isNull() = get() === null

internal fun <T> KProperty<T?>.isNotNull() = get() !== null

internal fun <T> KProperty<T?>.isTrue() = get() == true

internal fun <T> KProperty<T?>.isFalse() = get() == false

fun <R> KFunction<R>.returnType() = returnType.jvmErasure

internal fun Byte.toPercentage() =
    (this * 100 / Byte.MAX_VALUE).toByte()

fun Any?.asObjectProvider() = asType<ObjectProvider>()
fun Any?.asFunctionProvider() = asType<FunctionProvider>()
internal fun Any?.asActivity() = asType<Activity>()
internal fun Any?.asFragment() = asType<Fragment>()
internal fun Any?.asContext() = asType<Context>()
internal fun Any?.asUniqueContext() = asType<UniqueContext>()
internal fun Any?.asString() = asType<String>()
internal fun Any?.asInt() = asType<Int>()
internal fun Any?.asLong() = asType<Long>()
internal fun Any?.asAnyArray() = asType<AnyArray>()
internal fun Any?.asAnyToBooleanPair() = asType<Pair<Any, Boolean>>()
internal fun Any?.asAnyFunction() = asType<AnyFunction>()

typealias ObjectProvider = (AnyKClass) -> Any
internal typealias ObjectPointer = () -> Any

internal typealias AnyArray = Array<*>
internal typealias StringArray = Array<String>
internal typealias AnyMutableList = MutableList<Any?>
internal typealias AnyFunction = () -> Any?
internal typealias AnyFunctionList = MutableList<AnyFunction>
internal typealias AnyToAnyFunction = (Any?) -> Any?
internal typealias IntMutableList = MutableList<Int>
internal typealias IntFunction = () -> Int
internal typealias LongFunction = () -> Long
internal typealias StringFunction = Any?.() -> String
internal typealias CharsFunction = Any?.() -> CharSequence
internal typealias StringPointer = () -> String?
internal typealias CharsPointer = () -> CharSequence?
internal typealias ThrowableFunction = (Throwable?) -> Unit
internal typealias BooleanPointer = () -> Boolean?
internal typealias Predicate = () -> Boolean
internal typealias AnyPredicate = (Any?) -> Boolean
internal typealias ObjectPredicate = (Any) -> Boolean
internal typealias IntPredicate = (Int) -> Boolean
internal typealias ThrowablePredicate = (Throwable) -> Boolean
internal typealias ThrowableNothing = (Throwable) -> Nothing

lateinit var mainUncaughtExceptionHandler: ExceptionHandler

open class BaseImplementationRestriction(
    override val message: String? = "Base implementation restricted",
    override val cause: Throwable? = null
) : UnsupportedOperationException(message, cause)

internal open class InterruptedStepException(
    @JvmField val step: Any,
    override val message: String? = null,
    override val cause: Throwable? = null
) : InterruptedException()

internal lateinit var log: Logger

lateinit var info: LogFunction
lateinit var debug: LogFunction
lateinit var warning: LogFunction

operator fun LogFunction.plus(other: LogFunction): LogFunction = { tag, msg ->
    this(tag, msg)
    other(tag, msg) }

operator fun LogFunction.times(other: LogFunction): LogFunction = { tag, msg ->
    if (isOn) this(tag, msg)
    if (other.isOn) other(tag, msg) }

private val bypass: LogFunction = { _, _ -> }
private val LogFunction.isOn
    get() = this !== bypass

fun enableLogger() { log = { log, tag, msg -> log(tag, msg) } }
fun restrictLogger() { log = { log, tag, msg -> if (log.isOn) log(tag, msg) } }
fun disableLogger() { log = { _, _, _ -> } }

fun enableInfoLog() { info = { tag, msg -> Log.i(tag.toString(), msg.toString()) } }
fun enableDebugLog() { debug = { tag, msg -> Log.d(tag.toString(), msg.toString()) } }
fun enableWarningLog() { warning = { tag, msg -> Log.w(tag.toString(), msg.toString()) } }
fun enableAllLogs() {
    enableInfoLog()
    enableDebugLog()
    enableWarningLog() }
fun bypassInfoLog() { info = bypass }
fun bypassDebugLog() { debug = bypass }
fun bypassWarningLog() { info = bypass }
fun bypassAllLogs() {
    bypassInfoLog()
    bypassDebugLog()
    bypassWarningLog() }

internal typealias Logger = (LogFunction, CharSequence, CharSequence) -> Any?
private typealias LogFunction = (CharSequence, CharSequence) -> Any?

@JvmInline
value class LogValue(private val value: Any) : CharSequence {
    override fun toString() =
        value.toString()

    override fun get(index: Int) =
        toString()[index]

    override fun subSequence(startIndex: Int, endIndex: Int) =
        toString().subSequence(startIndex, endIndex)

    override val length: Int
        get() = toString().length
}

internal const val TAG_DOT = "."
internal const val TAG_AT = "@"
internal const val TAG_HASH = "#"
internal const val TAG_DASH = "-"

internal const val IS = "is"
internal const val MIN = "min"
internal const val NULL = "null"

const val MAIN = "main"
internal const val KEEP = "keep"
internal const val JOB = "job"
internal const val BUILD = "build"
internal const val INIT = "init"
internal const val LAUNCH = "launch"
internal const val COMMIT = "commit"
internal const val EXEC = "exec"
internal const val ATTACH = "attach"
internal const val WORK = "work"
internal const val LIVEWORK = "livework"
internal const val STEP = "step"
internal const val FORM = "form"
internal const val REFORM = "reform"
internal const val INDEX = "index"
internal const val REGISTER = "register"
internal const val UNREGISTER = "unregister"
internal const val REPEAT = "repeat"
internal const val DELAY = "delay"
internal const val YIELD = "yield"
internal const val CALL = "call"
internal const val POST = "post"
internal const val CALLBACK = "callback"
internal const val MSG = "msg"
internal const val WHAT = "what"
internal const val FUNC = "function"
internal const val ACTIVE = "active"
internal const val IS_ACTIVE = "$IS-$ACTIVE"
internal const val PREDICATE = "predicate"
internal const val SUCCESS = "success"
internal const val ERROR = "error"
internal const val UPDATE = "update"
const val EXCEPTION = "exception"
internal const val CAUSE = "cause"
internal const val MESSAGE = "msg"
const val EXCEPTION_CAUSE = "ex-$CAUSE"
const val EXCEPTION_MESSAGE = "ex-$MESSAGE"
const val EXCEPTION_CAUSE_MESSAGE = "ex-$CAUSE-$MESSAGE"
internal const val IGNORE = "ignore"
const val UNCAUGHT = "uncaught"
const val NOW = "now"
internal const val INTERVAL = "interval"
internal const val MIN_INTERVAL = "$MIN-$INTERVAL"

internal const val CONFIG = "config"
const val START = "start"
const val RESTART = "restart"
const val RESUME = "resume"
const val PAUSE = "pause"
const val STOP = "stop"
const val DESTROY = "destroy"
const val SAVE = "save"

internal const val APP = "app"
internal const val ACTIVITY = "activity"
internal const val FRAGMENT = "fragment"
internal const val OVERLAY = "overlay"
internal const val VIEW = "view"
internal const val CONTEXT = "context"
internal const val CTX = "ctx"
internal const val OWNER = "owner"
internal const val SHARED = "shared"
internal const val SERVICE = "service"
internal const val SVC = "svc"
internal const val CLOCK = "clock"
internal const val CLK = "clk"
internal const val CTRL = "ctrl"
internal const val FLO = "flo"
internal const val SCH = "sch"
internal const val SEQ = "seq"
internal const val LOG = "log"
internal const val NET = "net"
internal const val DB = "db"

const val APP_INIT = "$APP-$INIT"

const val MAIN_ACTIVITY = "$MAIN-$ACTIVITY"
const val MAIN_FRAGMENT = "$MAIN-$FRAGMENT"
const val OVERLAY_FRAGMENT = "$OVERLAY-$FRAGMENT"

internal const val APP_DB = "$APP-$DB"
internal const val LOG_DB = "$LOG-$DB"
internal const val NET_DB = "$NET-$DB"
internal const val SESSION = "session"

const val VIEW_ATTACH = "$VIEW.$ATTACH"
internal const val SCH_CONFIG = "$SCH.$CONFIG"
internal const val CLOCK_INIT = "$CLOCK.$INIT"
internal const val CLK_ATTACH = "$CLK.$ATTACH"
internal const val CLK_EXEC = "$CLK.$EXEC"
internal const val CTX_REFORM = "$CTX.$REFORM"
internal const val CTX_STEP = "$CTX.$STEP"
internal const val JOB_LAUNCH = "$JOB.$LAUNCH"
internal const val JOB_REPEAT = "$JOB.$REPEAT"
internal const val FLO_LAUNCH = "$FLO.$LAUNCH"
internal const val SCH_COMMIT = "$SCH.$COMMIT"
internal const val SCH_LAUNCH = "$SCH.$LAUNCH"
internal const val SCH_EXEC = "$SCH.$EXEC"
internal const val SCH_POST = "$SCH.$POST"
internal const val SEQ_ATTACH = "$SEQ.$ATTACH"
internal const val SEQ_LAUNCH = "$SEQ.$LAUNCH"
internal const val SERVICE_INIT = "$SERVICE.$INIT"
internal const val SVC_COMMIT = "$SVC.$COMMIT"
internal const val NULL_STEP = "$NULL-$STEP"
internal const val UNCAUGHT_DB = "$UNCAUGHT-$DB"
const val UNCAUGHT_SHARED = "$UNCAUGHT-$SHARED"

const val STAGE_BUILD_APP_DB = "$APP_DB.$BUILD"
const val STAGE_BUILD_SESSION = "$SESSION.$BUILD"
internal const val STAGE_BUILD_LOG_DB = "$LOG_DB.$BUILD"
internal const val STAGE_BUILD_NET_DB = "$NET_DB.$BUILD"
internal const val STAGE_INIT_NET_DB = "$NET_DB.$INIT"

internal const val START_TIME_KEY = "1"
internal const val MODE_KEY = "2"
internal const val ACTION_KEY = "3"

const val JVM_CLASS_NAME = ctx.consolator.JVM_CLASS_NAME