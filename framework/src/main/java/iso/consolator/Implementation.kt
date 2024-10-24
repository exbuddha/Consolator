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
import androidx.core.util.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import androidx.room.*
import ctx.consolator.*
import data.consolator.*
import data.consolator.dao.*
import data.consolator.entity.*
import iso.consolator.Path.*
import iso.consolator.activity.*
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

internal lateinit var instance: Application

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
    set(value) {
        field = ::foregroundLifecycleOwner.receiveUniquely(value) }

@Tag(VIEW_MIN_DELAY)
const val view_min_delay = 300L

internal typealias ContextStep = suspend Context.(Any?) -> Any?

internal fun Context.change(stage: ContextStep) =
    commit { stage(this) }

internal fun Context.changeLocally(owner: LifecycleOwner, stage: ContextStep) =
    commit { stage(this) }

internal fun Context.changeBroadly(ref: WeakContext = asWeakReference(), stage: ContextStep) =
    commit { stage(this) }

internal fun Context.changeGlobally(owner: LifecycleOwner, ref: WeakContext = asWeakReference(), stage: ContextStep) =
    commit { stage(this) }

@Diverging(["$STAGE_BUILD_APP_DB"])
fun Context.stageAppDbCreated(scope: Any?) {
    // bootstrap
}

@Diverging(["$STAGE_BUILD_SESSION"])
fun Context.stageSessionCreated(scope: Any?) {
    // update db records
}

@Diverging(["$STAGE_BUILD_LOG_DB"])
internal fun Context.stageLogDbCreated(scope: Any?) {
    mainUncaughtExceptionHandler = @Tag(UNCAUGHT_DB) ExceptionHandler { th, ex ->
        // record in db safely
    }
}

@Diverging(["$STAGE_BUILD_NET_DB"])
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
internal fun Context.intendFor(cls: AnyKClass) = intendFor(cls.java)

interface ReferredContext { var ref: WeakContext? }

typealias WeakContext = WeakReference<out Context>

fun Context.asWeakReference() =
    if (this is ReferredContext) ref!!
    else WeakReference(this)

fun <T : Context> WeakReference<out T>?.unique(context: T) =
    require { WeakReference(context) }

internal fun Context.startTime() = asUniqueContext()?.startTime ?: -1L

internal typealias TimeInterval = LongFunctionPair

internal fun TimeInterval.hasTimeIntervalElapsed() =
    hasTimeIntervalElapsed(first(), second())

internal fun TimeInterval.getDelayTime() =
    getDelayTime(first(), second())

internal fun hasTimeIntervalElapsed(last: Long, interval: Long) =
    getDelayTime(last, interval) <= 0 || last == 0L

internal fun getDelayTime(last: Long, interval: Long) =
    last + interval - now()

internal inline fun <R> Boolean.then(block: () -> R) =
    if (this) block() else null

internal inline fun <R> Boolean.otherwise(block: () -> R) =
    not().then(block)

internal inline fun <R> Predicate.then(block: () -> R) =
    this().then(block)

internal inline fun <R> Predicate.otherwise(block: () -> R) =
    this().not().then(block)

internal fun Predicate.not(): Predicate = { this().not() }

internal fun trueWhenNull(it: Any?) = it === null

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
    try { block() }
    catch (ex: Throwable) { throw CancellationException(null, ex) }

internal inline fun <R> trySafelyCanceling(block: () -> R) =
    tryCancelingForResult(block)

internal inline fun <R> tryCancelingForResult(block: () -> R, exit: (Throwable) -> R? = { null }) =
    try { block() }
    catch (ex: CancellationException) { throw ex }
    catch (ex: Throwable) { exit(ex) }

suspend inline fun <R> tryCancelingSuspended(crossinline block: suspend () -> R) = tryCanceling { block() }
suspend inline fun <T, R> tryCancelingSuspended(scope: T, crossinline block: suspend T.() -> R) = tryCanceling { scope.block() }
suspend inline fun <T, R> tryCancelingSuspended(crossinline scope: suspend () -> T, crossinline block: suspend T.() -> R) = tryCancelingSuspended(scope(), block)

internal inline fun <R> tryInterrupting(block: () -> R) =
    try { block() }
    catch (ex: Throwable) { throw InterruptedException() }

internal fun <R> tryInterrupting(step: suspend CoroutineScope.() -> R) =
    try { blockOf(step)() }
    catch (ex: Throwable) { throw InterruptedStepException(step, cause = ex) }

internal inline fun <R> trySafelyInterrupting(block: () -> R) =
    try { block() }
    catch (ex: InterruptedException) { throw ex }
    catch (_: Throwable) {}

internal fun <R> trySafelyInterrupting(step: suspend CoroutineScope.() -> R) =
    try { blockOf(step)() }
    catch (ex: InterruptedException) { throw InterruptedStepException(step, cause = ex) }
    catch (_: Throwable) {}

internal inline fun <R> tryInterruptingForResult(noinline step: suspend CoroutineScope.() -> R, exit: (Throwable) -> R? = { null }) =
    try { blockOf(step)() }
    catch (ex: InterruptedException) { throw InterruptedStepException(step, cause = ex) }
    catch (ex: Throwable) { exit(ex) }

internal inline fun <T, reified R> Array<out T>.mapToTypedArray(transform: (T) -> R) =
    map(transform).toTypedArray()

internal fun <T> Array<out T>.secondOrNull(): T? =
    if (size > 1) get(1)
    else null

internal inline fun <reified T : Any> Any?.asTypeOf(instance: KCallable<T>): T? =
    instance.call()::class.safeCast(this)

internal inline fun <reified T : Any> Any?.asTypeOf(obj: T): T? =
    obj::class.safeCast(this)

inline fun <reified T : Any> Any?.asType(): T? =
    T::class.safeCast(this)

internal inline fun <reified T : Any> T?.singleton(vararg args: Any?, lock: Any = T::class.lock) =
    commitAsyncForResult(lock, { this === null }, { T::class.new(*args) }, { this }) as T

internal inline fun <T> provided(constructor: () -> T?) =
    constructor()!!

internal inline fun <T> T?.require(constructor: () -> T) =
    this ?: constructor()

internal inline fun <reified T : Any> T?.reconstruct(vararg args: Any?, constructor: KCallable<T?> = T::class::new) =
    this ?: constructor.call(*args)

internal inline fun <reified T : Any> T?.reconstruct(vararg args: Any?) =
    this ?: T::class.new(*args)

internal val <T : Any> KClass<out T>.companion
    get() = objectInstance as T

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

@Suppress("UNCHECKED_CAST")
internal fun <R> Unit.type() = this as R

fun Any?.asObjectProvider() = asType<ObjectProvider>()
fun Any?.asFunctionProvider() = asType<FunctionProvider>()
fun Any?.asTransitFunction() = asType<(TransitionManager) -> Unit>()
internal fun Any?.asActivity() = asType<Activity>()
internal fun Any?.asFragment() = asType<Fragment>()
internal fun Any?.asContext() = asType<Context>()
internal fun Any?.asWeakContext() = asType<WeakContext>()
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
internal typealias IntArray = Array<Int>
internal typealias ShortArray = Array<Short>
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
internal typealias LongFunctionPair = Pair<LongFunction, LongFunction>

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

internal const val TAG_DOT = 1
internal const val TAG_DASH = 2
internal const val TAG_AT = 3
internal const val TAG_HASH = 4

internal const val TRUE = 0
internal const val FALSE = 1
internal const val IS = 2
internal const val MIN = 3
internal const val MAX = 4
internal const val ACTIVE = 5
internal const val NULL = 6

const val MAIN = 1700
internal const val KEEP = 1810
internal const val JOB = 1811
internal const val BUILD = 1701
internal const val INIT = 1702
internal const val LAUNCH = 1812
internal const val COMMIT = 1703
internal const val EXEC = 1813
internal const val ATTACH = 1704
internal const val WORK = 1814
internal const val LIVEWORK = 1815
internal const val STEP = 1816
internal const val FORM = 1705
internal const val REFORM = 1817
internal const val INDEX = 1706
internal const val REGISTER = 1707
internal const val UNREGISTER = 1708
internal const val REPEAT = 1828
internal const val DELAY = 1709
internal const val MIN_DELAY = 1819
internal const val YIELD = 1820
internal const val CALL = 1710
internal const val POST = 1711
internal const val CALLBACK = 1712
internal const val FUNC = 1713
internal const val MSG = 1821
internal const val WHAT = 1822
internal const val IS_ACTIVE = 7
internal const val PREDICATE = 1714
internal const val SUCCESS = 1715
internal const val ERROR = 1716
internal const val UPDATE = 1823
const val EXCEPTION = 1717
internal const val CAUSE = 1824
internal const val MESSAGE = 1825
const val EXCEPTION_CAUSE = 1718
const val EXCEPTION_MESSAGE = 1826
const val EXCEPTION_CAUSE_MESSAGE = 1827
internal const val IGNORE = 1719
const val UNCAUGHT = 1828
const val NOW = 1720
internal const val INTERVAL = 1721
internal const val MIN_INTERVAL = 1829

internal const val CONFIG = 1830
const val START = 1831
const val RESTART = 1832
const val RESUME = 1833
const val PAUSE = 1834
const val STOP = 1835
const val SAVE = 1847
const val DESTROY = 1848
internal const val EXPIRE = 1849

internal const val APP = 1850
internal const val ACTIVITY = 1851
internal const val FRAGMENT = 1852
internal const val VIEW = 18753
internal const val CONTEXT = 1854
internal const val OWNER = 1855
internal const val SHARED = 1878
internal const val SERVICE = 1879
internal const val CLOCK = 1880
internal const val HANDLER = 1881
internal const val CTRL = 1882
internal const val CTX = 1883
internal const val FLO = 1884
internal const val SCH = 1885
internal const val SEQ = 1886
internal const val LOG = 1887
internal const val NET = 1888
internal const val DB = 1889

const val APP_INIT = 1890

const val MAIN_ACTIVITY = 1
const val MAIN_FRAGMENT = 2
const val OVERLAY_FRAGMENT = 3

internal const val APP_DB = 7
internal const val LOG_DB = 8
internal const val NET_DB = 9
internal const val SESSION = 10

const val VIEW_ATTACH = 1200
const val VIEW_MIN_DELAY = 1701
internal const val CLK_INIT = 1891
internal const val CLK_ATTACH = 1877
internal const val CLK_EXEC = 1878
internal const val CTX_REFORM = 1879
internal const val CTX_STEP = 1880
internal const val JOB_LAUNCH = 1881
internal const val JOB_REPEAT = 1890
internal const val FLO_LAUNCH = 1883
internal const val SCH_COMMIT = 1884
internal const val SCH_LAUNCH = 1885
internal const val SCH_EXEC = 1886
internal const val SCH_POST = 1887
internal const val SEQ_ATTACH = 1891
internal const val SEQ_LAUNCH = 1889
internal const val SVC_INIT = 1892
internal const val SVC_COMMIT = 1892
internal const val SCH_CONFIG = 1893
internal const val NULL_STEP = 2000
internal const val UNCAUGHT_DB = 1894
const val UNCAUGHT_SHARED = 1895

const val STAGE_BUILD_APP_DB = 1001
const val STAGE_BUILD_SESSION = 1002
internal const val STAGE_BUILD_LOG_DB = 1003
internal const val STAGE_BUILD_NET_DB = 1004
internal const val STAGE_INIT_NET_DB = 1005

internal const val START_TIME_KEY = "1"
internal const val MODE_KEY = "2"
internal const val ACTION_KEY = "3"

const val JVM_CLASS_NAME = ctx.consolator.JVM_CLASS_NAME