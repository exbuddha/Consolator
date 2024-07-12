package net.consolator

import android.content.*
import android.content.pm.*
import android.net.*
import android.util.*
import androidx.annotation.*
import androidx.core.content.*
import androidx.lifecycle.*
import androidx.room.*
import java.lang.*
import java.lang.ref.*
import kotlin.annotation.AnnotationRetention.*
import kotlin.annotation.AnnotationTarget.*
import kotlin.reflect.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import net.consolator.Path.Diverging
import net.consolator.Scheduler.EventBus.commit
import android.Manifest.permission.ACCESS_NETWORK_STATE
import android.Manifest.permission.INTERNET
import androidx.core.content.ContextCompat.RECEIVER_EXPORTED
import net.consolator.Scheduler.clock

var instance: BaseApplication? = null
var service: BaseService? = null
var receiver: BaseReceiver? = null
    get() = field.singleton().also { field = it }

var db: AppDatabase? = null
var logDb: LogDatabase? = null
var netDb: NetworkDatabase? = null
var session: RuntimeSessionEntity? = null

@LayoutRes
var layoutId = R.layout.background

@IdRes
var containerViewId = R.id.layout_background

@LayoutRes
var contentLayoutId = R.layout.background

val foregroundContext: Context
    get() = service ?: instance!!

val foregroundLifecycleOwner: LifecycleOwner?
    get() = null

const val VIEW_MIN_DELAY = 300L

typealias ContextStep = suspend Context.(Any?) -> Any?

fun Context.change(stage: ContextStep) =
    commit(stage)

fun Context.changeLocally(owner: LifecycleOwner, stage: ContextStep) =
    commit(stage)

fun Context.changeBroadly(ref: WeakContext = weakRef(), stage: ContextStep) =
    commit(stage)

fun Context.changeGlobally(ref: WeakContext = weakRef(), owner: LifecycleOwner, stage: ContextStep) =
    commit(stage)

@Diverging([STAGE_BUILD_APP_DB])
fun Context.stageAppDbCreated(scope: Any?) {
    // bootstrap
}

@Diverging([STAGE_BUILD_SESSION])
fun Context.stageSessionCreated(scope: Any?) {
    // update db records
}

@Diverging([STAGE_BUILD_LOG_DB])
fun Context.stageLogDbCreated(scope: Any?) {
    mainUncaughtExceptionHandler = @Tag(UNCAUGHT_DB) ExceptionHandler { th, ex ->
        // record in db safely
    }
}

@Diverging([STAGE_BUILD_NET_DB])
fun Context.stageNetDbInitialized(scope: Any?) {
    // update net function pointers
}

suspend fun buildSession() {
    if (session === null)
        buildNewSession() }

suspend fun buildNewSession() {
    runtimeDao {
        session = getSession(
            newSession(foregroundContext.startTime())) } }

suspend fun updateNetworkState() {
    networkDao {
        updateNetworkState(
            isConnected,
            hasInternet,
            hasMobile,
            hasWifi) } }

suspend fun updateNetworkCapabilities(network: Network? = net.consolator.network, networkCapabilities: NetworkCapabilities? = net.consolator.networkCapabilities) {
    networkCapabilities?.run {
        networkDao {
            updateNetworkCapabilities(
                Json.encodeToString(capabilities),
                linkDownstreamBandwidthKbps,
                linkUpstreamBandwidthKbps,
                signalStrength,
                network.hashCode()) } } }

fun <D : RoomDatabase> Context.createDatabase(cls: Class<D>, name: String?) =
    Room.databaseBuilder(this, cls, name).build()

fun <D : RoomDatabase> Context.createDatabase(cls: KClass<D>) =
    with(cls) { createDatabase(java, lastAnnotatedFilename()) }

inline fun <reified D : RoomDatabase> Context.buildDatabase() =
    with(D::class, ::createDatabase)

inline fun <reified D : RoomDatabase> Context.commitBuildDatabase(instance: KMutableProperty<out D?>) =
    instance.requireAsync(constructor = { buildDatabase<D>().also(instance::set) })

fun Context.buildAppDatabase() =
    commitBuildDatabase(::db)

fun Context.registerReceiver(filter: IntentFilter) =
    ContextCompat.registerReceiver(this, receiver, filter, null,
        clock?.alsoStartAsync()?.handler,
        RECEIVER_EXPORTED)

val Context.isNetworkStateAccessPermitted
    get() = isPermissionGranted(ACCESS_NETWORK_STATE)

val Context.isInternetAccessPermitted
    get() = isPermissionGranted(INTERNET)

fun Context.isPermissionGranted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun Context.intendFor(cls: Class<*>) = Intent(this, cls)
fun Context.intendFor(cls: AnyKClass) = intendFor(cls.java)

interface ReferredContext { var ref: WeakContext? }

typealias WeakContext = WeakReference<out Context>

fun Context.weakRef() =
    if (this is ReferredContext) ref!!
    else WeakReference(this)

fun <T : Context> WeakReference<out T>?.unique(context: T) =
    this ?: WeakReference(context)

interface UniqueContext { var startTime: Long }

fun Context.startTime() = asUniqueContext()?.startTime ?: -1L

fun now() = java.util.Calendar.getInstance().timeInMillis

fun getDelayTime(interval: Long, last: Long) =
    interval + last - now()

fun isTimeIntervalExceeded(interval: Long, last: Long) =
    getDelayTime(interval, last) <= 0 || last == 0L

inline fun <R> trySafely(block: () -> R) =
    try { block() } catch (_: Throwable) {}

inline fun <R> trySafelyForResult(block: () -> R) =
    try { block() } catch (_: Throwable) { null }

inline fun <R> tryCanceling(block: () -> R) =
    try { block() } catch (ex: Throwable) { throw CancellationException(null, ex) }

inline fun <R> trySafelyCanceling(block: () -> R) =
    tryCancelingForResult(block)

inline fun <R> tryCancelingForResult(block: () -> R, exit: (Throwable) -> R? = { null }) =
    try { block() } catch (ex: CancellationException) { throw ex } catch (ex: Throwable) { exit(ex) }

suspend inline fun <R> tryCancelingSuspended(crossinline block: suspend () -> R) = tryCanceling { block() }
suspend inline fun <T, R> tryCancelingSuspended(scope: T, crossinline block: suspend T.() -> R) = tryCanceling { scope.block() }
suspend inline fun <T, R> tryCancelingSuspended(crossinline scope: suspend () -> T, crossinline block: suspend T.() -> R) = tryCancelingSuspended(scope(), block)

inline fun <R> tryInterrupting(block: () -> R) =
    try { block() } catch (ex: Throwable) { throw InterruptedException() }

fun <R> tryInterrupting(step: suspend CoroutineScope.() -> R) =
    try { blockOf(step)() } catch (ex: Throwable) { throw InterruptedStepException(step, cause = ex) }

inline fun <R> trySafelyInterrupting(block: () -> R) =
    try { block() } catch (ex: InterruptedException) { throw ex } catch (_: Throwable) {}

fun <R> trySafelyInterrupting(step: suspend CoroutineScope.() -> R) =
    try { blockOf(step)() } catch (ex: InterruptedException) { throw InterruptedStepException(step, cause = ex) } catch (_: Throwable) {}

inline fun <R> tryInterruptingForResult(noinline step: suspend CoroutineScope.() -> R, exit: (Throwable) -> R? = { null }) =
    try { blockOf(step)() } catch (ex: InterruptedException) { throw InterruptedStepException(step, cause = ex) } catch (ex: Throwable) { exit(ex) }

fun <T> Array<out T>.secondOrNull(): T? =
    if (size > 1) get(1)
    else null

inline fun <reified T : Any> Any?.asType(): T? =
    T::class.safeCast(this)

inline fun <reified T : Any> T?.singleton(vararg args: Any?, lock: Any = T::class.lock()) =
    commitAsyncForResult(lock, { this === null }, { T::class.new(*args) }, { this }) as T

inline fun <T> T?.require(constructor: () -> T) =
    this ?: constructor()

inline fun <reified T : Any> T?.reconstruct(constructor: KCallable<T?>, vararg args: Any?) =
    this ?: constructor.call(*args)

inline fun <reified T : Any> T?.reconstruct(vararg args: Any?) =
    this ?: T::class.new(*args)

fun <T : Any> KClass<out T>.lock() =
    objectInstance ?: this

fun <T : Any> KClass<out T>.reconstruct(vararg args: Any?) =
    if (isCompanion) objectInstance!!
    else new(*args)

fun <T : Any> KClass<out T>.new(vararg args: Any?) =
    if (args.isEmpty()) emptyConstructor().call()
    else firstConstructor().call(*args)

fun <T : Any> KClass<out T>.emptyConstructor() =
    constructors.first { it.parameters.isEmpty() }

fun <T : Any> KClass<out T>.firstConstructor() =
    constructors.first()

inline fun <reified T> KMutableProperty<out T?>.reconstruct(provider: Any = T::class) =
    apply { renew {
        if (provider is AnyKClass)
            provider.emptyConstructor().call()
        else
            provider.asObjectProvider()?.invoke(T::class) } }

inline fun <T> KMutableProperty<out T?>.renew(constructor: () -> T? = ::get) {
    if (isNull())
        set(constructor()) }

inline fun <T> KMutableProperty<out T?>.require(predicate: (T) -> Boolean = ::trueWhenNull, constructor: () -> T? = ::get) =
    get().let { old ->
        if (old === null || predicate(old))
            constructor()!!.also(::set)
        else old }

inline fun <T> KMutableProperty<out T?>.requireAsync(predicate: (T) -> Boolean = ::trueWhenNull, constructor: () -> T? = ::get) =
    require(predicate) {
        synchronized(this) {
            require(predicate, constructor) } }

fun <T> KMutableProperty<out T?>.set(value: T?) = setter.call(value)

fun <T> KProperty<T?>.get() = getter.call()

fun <T> KProperty<T?>.isNull() = get() === null

fun <T> KProperty<T?>.isNotNull() = get() !== null

@Retention(SOURCE)
@Target(CLASS)
@Repeatable
annotation class File(val name: String)

fun <T : Any> KClass<out T>.annotatedFiles() =
    annotations.filterIsInstance<File>()

fun <T : Any> KClass<out T>.lastAnnotatedFile() =
    annotations.last { it is File } as File

fun <T : Any> KClass<out T>.lastAnnotatedFilename() =
    lastAnnotatedFile().name

fun Byte.toPercentage() =
    (this * 100 / Byte.MAX_VALUE).toByte()

fun Any?.asContext() = asType<Context>()
fun Any?.asUniqueContext() = asType<UniqueContext>()
fun Any?.asObjectProvider() = asType<ObjectProvider>()
fun Any?.asString() = asType<String>()
fun Any?.asInt() = asType<Int>()
fun Any?.asLong() = asType<Long>()
fun Any?.asAnyArray() = asType<AnyArray>()

typealias ObjectProvider = (AnyKClass) -> Any

typealias AnyArray = Array<*>
typealias StringArray = Array<String>
typealias AnyFunction = () -> Any?
typealias AnyToAnyFunction = (Any?) -> Any?
typealias IntMutableList = MutableList<Int>
typealias IntFunction = () -> Int
typealias LongFunction = () -> Long
typealias StringFunction = Any?.() -> String
typealias StringPointer = () -> String?
typealias ThrowableFunction = (Throwable?) -> Unit
typealias Predicate = () -> Boolean
typealias AnyPredicate = (Any?) -> Boolean
typealias IntPredicate = (Int) -> Boolean

open class BaseImplementationRestriction(
    override val message: String? = "Base implementation restricted",
    override val cause: Throwable? = null
) : UnsupportedOperationException(message, cause) {
    companion object : BaseImplementationRestriction() }

open class InterruptedStepException(
    val step: Any,
    override val message: String? = null,
    override val cause: Throwable? = null
) : InterruptedException()

var log = fun(log: LogFunction, tag: String, msg: String) { if (log.isOn) log(tag, msg) }

var info: LogFunction = fun(tag: String, msg: String) = Log.i(tag, msg)
var debug: LogFunction = fun(tag: String, msg: String) = Log.d(tag, msg)
var warning: LogFunction = fun(tag: String, msg: String) = Log.w(tag, msg)
private val bypass: LogFunction = { _, _ -> }

val LogFunction.isOn
    get() = this !== bypass
val LogFunction.isOff
    get() = this === bypass

fun bypassInfoLog() { info = bypass }
fun bypassDebugLog() { debug = bypass }
fun bypassWarningLog() { info = bypass }
fun bypassAllLogs() {
    bypassInfoLog()
    bypassDebugLog()
    bypassWarningLog() }

private typealias LogFunction = (String, String) -> Any?

const val IS = "is"
const val MIN = "min"
const val NULL = "null"

const val JOB = "job"
const val BUILD = "build"
const val INIT = "init"
const val START = "start"
const val LAUNCH = "launch"
const val COMMIT = "commit"
const val EXEC = "exec"
const val ATTACH = "attach"
const val WORK = "work"
const val STEP = "step"
const val FORM = "form"
const val REFORM = "reform"
const val INDEX = "index"
const val REGISTER = "register"
const val UNREGISTER = "unregister"
const val REPEAT = "repeat"
const val DELAY = "delay"
const val YIELD = "yield"
const val CALL = "call"
const val CALLBACK = "callback"
const val MSG = "msg"
const val WHAT = "what"
const val FUNC = "function"
const val ACTIVE = "active"
const val IS_ACTIVE = "$IS-$ACTIVE"
const val PREDICATE = "predicate"
const val SUCCESS = "success"
const val ERROR = "error"
const val UPDATE = "update"
const val EXCEPTION = "exception"
const val CAUSE = "cause"
const val MESSAGE = "message"
const val EXCEPTION_CAUSE = "$EXCEPTION-$CAUSE"
const val EXCEPTION_MESSAGE = "$EXCEPTION-$MESSAGE"
const val EXCEPTION_CAUSE_MESSAGE = "$EXCEPTION-$CAUSE-$MESSAGE"
const val IGNORE = "ignore"
const val UNCAUGHT = "uncaught"
const val NOW = "now"
const val INTERVAL = "interval"
const val MIN_INTERVAL = "$MIN-$INTERVAL"

const val APP = "app"
const val VIEW = "view"
const val CONTEXT = "context"
const val CTX = "ctx"
const val MAIN = "main"
const val SHARED = "shared"
const val SERVICE = "service"
const val SVC = "svc"
const val CLOCK = "clock"
const val CLK = "clk"
const val SCH = "sch"
const val SEQ = "seq"
const val LOG = "log"
const val NET = "net"
const val DB = "db"

const val APP_DB = "$APP-$DB"
const val LOG_DB = "$LOG-$DB"
const val NET_DB = "$NET-$DB"
const val SESSION = "session"

const val CLOCK_INIT = "$CLOCK.$INIT"
const val CLK_ATTACH = "$CLK.$ATTACH"
const val VIEW_ATTACH = "$VIEW.$ATTACH"
const val CTX_REFORM = "$CTX.$REFORM"
const val JOB_LAUNCH = "$JOB.$LAUNCH"
const val JOB_REPEAT = "$JOB.$REPEAT"
const val SCH_COMMIT = "$SCH.$COMMIT"
const val SCH_EXEC = "$SCH.$EXEC"
const val SEQ_ATTACH = "$SEQ.$ATTACH"
const val SEQ_LAUNCH = "$SEQ.$LAUNCH"
const val SVC_COMMIT = "$SVC.$COMMIT"
const val NULL_STEP = "$NULL-$STEP"
const val UNCAUGHT_DB = "$UNCAUGHT-$DB"
const val UNCAUGHT_SHARED = "$UNCAUGHT-$SHARED"

const val STAGE_BUILD_APP_DB = "$APP_DB.$BUILD"
const val STAGE_BUILD_SESSION = "$SESSION.$BUILD"
const val STAGE_BUILD_LOG_DB = "$LOG_DB.$BUILD"
const val STAGE_BUILD_NET_DB = "$NET_DB.$BUILD"
const val STAGE_INIT_NET_DB = "$NET_DB.$INIT"

const val START_TIME_KEY = "1"
const val MODE_KEY = "2"
const val ACTION_KEY = "3"