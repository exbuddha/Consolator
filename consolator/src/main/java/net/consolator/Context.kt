package net.consolator

import android.content.*
import android.content.pm.*
import android.net.*
import android.os.*
import android.util.*
import androidx.core.content.*
import androidx.lifecycle.*
import androidx.room.*
import java.lang.*
import java.lang.ref.*
import kotlin.annotation.AnnotationRetention.*
import kotlin.annotation.AnnotationTarget.*
import kotlin.reflect.*
import kotlinx.coroutines.*
import com.google.gson.Gson
import net.consolator.Scheduler.Event
import net.consolator.Scheduler.EventBus.signal
import net.consolator.State.Pending
import net.consolator.State.Resolved
import android.Manifest.permission.ACCESS_NETWORK_STATE
import android.Manifest.permission.INTERNET
import net.consolator.BaseApplication.Companion.ACTION_MIGRATE_APP

var instance: BaseApplication? = null
var service: BaseService? = null
var receiver: BaseReceiver? = null
    get() = field.singleton()
var db: AppDatabase? = null
var logDb: LogDatabase? = null
var netDb: NetworkDatabase? = null
var session: RuntimeSessionEntity? = null
lateinit var mainUncaughtExceptionHandler: ExceptionHandler

val foregroundLifecycleOwner: LifecycleOwner?
    get() = TODO()
val foregroundContext: Context
    get() = instance!!

fun Context.change(stage: ContextStep) =
    signal(stage)
fun Context.changeLocally(owner: LifecycleOwner, stage: ContextStep) =
    signal(stage)
fun Context.changeBroadly(ref: WeakContext = weakRef()!!, stage: ContextStep) =
    signal(stage)
fun Context.changeGlobally(ref: WeakContext = weakRef()!!, owner: LifecycleOwner, stage: ContextStep) =
    signal(stage)

@Event(ACTION_MIGRATE_APP)
fun Context.stageDbCreated() {
    // bootstrap
}

@Event(ACTION_MIGRATE_APP)
fun Context.stageSessionCreated() {
    // update db records
    State[1] = Resolved
}

fun Context.stageLogDbCreated() {
    mainUncaughtExceptionHandler[0] = @Tag("uncaught-db") ExceptionHandler { th, ex ->
        // record in db safely
    }
    State[2] += Pending
}

fun Context.stageNetDbInitialized() {
    // update net function pointers
    State[2] += Pending
}

inline fun <reified D : RoomDatabase> Context.buildDatabase() = with(D::class) {
    Room.databaseBuilder(
        this@buildDatabase,
        java,
        lastAnnotatedFilename()
    ).build()
}
fun Context.buildAppDatabase() = commitAsync(AppDatabase, { db === null }) {
    db = buildDatabase()
}
suspend fun buildSession() {
    if (session === null)
        buildNewSession()
}
suspend fun buildNewSession() {
    runtimeDao {
        session = getSession(
            newSession(instance!!.startTime))
    }
}
suspend fun updateNetworkState() {
    networkDao {
        updateNetworkState(
            isConnected,
            hasInternet,
            hasMobile,
            hasWifi
        )
    }
}
suspend fun updateNetworkCapabilities(networkCapabilities: NetworkCapabilities) {
    networkDao {
        with(networkCapabilities) {
            updateNetworkCapabilities(
                capabilities.toJson(),
                linkDownstreamBandwidthKbps,
                linkUpstreamBandwidthKbps,
                signalStrength
            )
        }
    }
}

fun Context.registerReceiver(filter: IntentFilter) =
    ContextCompat.registerReceiver(this, receiver, filter, null, Scheduler.clock?.handler, 0)

val Context.isNetworkStateAccessPermitted
    get() = isPermissionGranted(ACCESS_NETWORK_STATE)
val Context.isInternetAccessPermitted
    get() = isNetworkStateAccessPermitted and isPermissionGranted(INTERNET)
fun Context.isPermissionGranted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
fun Context.intendFor(cls: Class<*>) = Intent(this, cls)
fun Context.intendFor(cls: KClass<*>) = intendFor(cls.java)

interface SystemContext { val ref: WeakContext? }
typealias WeakContext = WeakReference<out Context>
fun Context.weakRef() = when (this) {
    is SystemContext -> ref
    else -> WeakReference(this)
}
fun <T : Context> WeakReference<out T>?.unique(context: T) = this ?: WeakReference(context)

fun now() = java.util.Calendar.getInstance().timeInMillis
fun getDelayTime(interval: Long, last: Long) =
    last + interval - now()
fun isTimeIntervalExceeded(interval: Long, last: Long) =
    (now() - last) >= interval || last == 0L

interface UniqueContext { var startTime: Long }
typealias ContextStep = suspend Context.() -> Unit

private typealias ExceptionHandler = Thread.UncaughtExceptionHandler
private operator fun ExceptionHandler.plusAssign(other: ExceptionHandler) {}
private operator fun ExceptionHandler.minusAssign(other: String) {}
private operator fun ExceptionHandler.plus(other: String): ExceptionHandler = this
private operator fun ExceptionHandler.minus(other: String): ExceptionHandler = this
private operator fun ExceptionHandler.set(index: Int, other: ExceptionHandler) {}

typealias BundleEditor = Bundle.() -> Unit

typealias AnyArray = Array<out Any?>
typealias AnyFunction = () -> Any?
typealias AnyToAnyFunction = (Any?) -> Any?
typealias LongFunction = () -> Long
typealias ThrowableFunction = (Throwable?) -> Unit
typealias Predicate = () -> Boolean
typealias AnyPredicate = (Any?) -> Boolean
typealias IntPredicate = (Int) -> Boolean

fun CoroutineScope.retrieveContext(): Context = TODO()
suspend fun CoroutineScope.registerContext(context: WeakContext) {
    currentCoroutineContext().job to context
}

suspend fun CoroutineScope.repeatSuspended(
    predicate: Predicate,
    block: JobFunction,
    delayTime: LongFunction = { 0L },
    scope: CoroutineScope = this) {
    scope.markTags(predicate, block, delayTime)
    while (predicate()) {
        block(scope)
        delayOrYield(delayTime())
    }
}
suspend fun delayOrYield(dt: Long = 0L) {
    if (dt > 0) delay(dt)
    else if (dt == 0L) yield()
}

inline fun <R> trySafely(block: () -> R) =
    try { block() } catch (_: Throwable) {}
inline fun <R> trySafelyForResult(block: () -> R) =
    try { block() } catch (_: Throwable) { null }
inline fun <R> trySafelyCanceling(block: () -> R) =
    try { block() } catch (ex: CancellationException) { throw ex } catch (_: Throwable) {}
inline fun <R> tryCanceling(block: () -> R) =
    try { block() } catch (ex: Throwable) { throw CancellationException(null, ex) }
inline fun <R> tryCancelingForResult(block: () -> R, exit: (Throwable) -> R? = { null }) =
    try { block() } catch (ex: CancellationException) { throw ex } catch (ex: Throwable) { exit(ex) }
suspend inline fun <R> tryCancelingSuspended(crossinline block: suspend () -> R) =
    tryCanceling { block() }
suspend inline fun <T, R> tryCancelingSuspended(scope: T, crossinline block: suspend T.() -> R) =
    tryCanceling { block(scope) }
inline fun <R> tryInterrupting(block: () -> R) =
    try { block() } catch (ex: Throwable) { throw InterruptedException() }
fun <R> tryInterrupting(step: suspend CoroutineScope.() -> R, blockOf: (suspend CoroutineScope.() -> R) -> () -> R = ::blockOf) =
    try { blockOf(step)() } catch (ex: Throwable) { throw InterruptedStepException(step, ex) }
inline fun <R> trySafelyInterrupting(block: () -> R) =
    try { block() } catch (ex: InterruptedException) { throw ex } catch (_: Throwable) {}
fun <R> trySafelyInterrupting(step: suspend CoroutineScope.() -> R, blockOf: (suspend CoroutineScope.() -> R) -> () -> R = ::blockOf) =
    try { blockOf(step)() } catch (ex: InterruptedException) { throw InterruptedStepException(step, ex) } catch (_: Throwable) {}
fun <R> tryInterruptingForResult(step: suspend CoroutineScope.() -> R, blockOf: (suspend CoroutineScope.() -> R) -> () -> R = ::blockOf, exit: (Throwable) -> R? = { null }) =
    try { blockOf(step)() } catch (ex: InterruptedException) { throw InterruptedStepException(step, ex) } catch (ex: Throwable) { exit(ex) }

@Retention(SOURCE)
@Target(CLASS)
@Repeatable
annotation class File(val name: String)
fun <T : Any> KClass<out T>.lastAnnotatedFile() = annotations.last { it is File } as File
fun <T : Any> KClass<out T>.lastAnnotatedFilename() = lastAnnotatedFile().name

inline fun <reified R : Any> Any?.asType(): R? =
    if (this is R) this else null
inline fun <reified R : Any> R?.singleton(lock: Any = R::class.lock()) =
    commitAsyncForResult(lock, { this === null }, this, R::class::emptyConstructor) as R
fun <T : Any> KClass<out T>.lock() = objectInstance ?: this
fun <T : Any> KClass<out T>.reconstruct(vararg args: Any?): T = when {
    isCompanion -> objectInstance!!
    args.isEmpty() -> emptyConstructor().call()
    else -> firstConstructor().call(*args)
}
fun <T : Any> KClass<out T>.emptyConstructor() = constructors.first { it.parameters.isEmpty() }
fun <T : Any> KClass<out T>.firstConstructor() = constructors.first()
inline fun <reified T : Any> KMutableProperty<out T?>.reconstruct(provider: Any = T::class) = apply {
    if (getter.call() === null)
        setter.call(
            if (provider is KClass<*>)
                provider.emptyConstructor().call()
            else
                provider.asType<Provider>()?.invoke(T::class))
}
typealias Provider = (KClass<*>) -> Any

var jsonConverter: Gson? = null
    get() = field ?: Gson()
fun IntArray.toJson() = jsonConverter!!.toJson(this, IntArray::class.java)
fun String.toIntArray() = jsonConverter!!.fromJson(this, IntArray::class.java)

fun Byte.asPercentage() =
    (this * 100 / Byte.MAX_VALUE).toByte()

open class BaseImplementationRestriction(
    msg: String = "Base implementation restricted",
    cause: Throwable? = null
) : UnsupportedOperationException(msg, cause) {
    companion object : BaseImplementationRestriction()
}
open class InterruptedStepException(
    val step: Any,
    override val cause: Throwable? = null
) : InterruptedException()

var info: LogFunction = fun(tag: String, msg: String) = Log.i(tag, msg)
var debug: LogFunction = fun(tag: String, msg: String) = Log.d(tag, msg)
var warning: LogFunction = fun(tag: String, msg: String) = Log.w(tag, msg)
private val bypass: LogFunction = { _, _ -> }

private typealias LogFunction = (String, String) -> Any?
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
    bypassWarningLog()
}

const val START_TIME_KEY = "1"
const val MODE_KEY = "2"
const val ACTION_KEY = "3"