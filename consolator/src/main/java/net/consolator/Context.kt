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
import kotlin.reflect.*
import kotlinx.coroutines.*
import com.google.gson.Gson
import net.consolator.Scheduler.Event
import net.consolator.Scheduler.EventBus
import net.consolator.State.Pending
import net.consolator.State.Resolved
import net.consolator.AppDatabase.Companion.File
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
    EventBus.event(stage)

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

suspend fun <T> T.repeatSuspended(
    predicate: Predicate,
    block: JobFunction,
    delayTime: LongFunction = { 0L },
    scope: T = this) {
    if (scope is CoroutineScope)
        scope.markFunctionTags(predicate, block, delayTime)
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
inline fun <R> tryCancelingForResult(block: () -> R, exit: (Throwable) -> R? = { null }) =
    try { block() } catch (ex: CancellationException) { throw ex } catch (ex: Throwable) { exit(ex) }
inline fun <R> tryCanceling(block: () -> R) =
    try { block() } catch (ex: Throwable) { throw CancellationException(null, ex) }
suspend inline fun <R> tryCancelingSuspended(crossinline block: suspend () -> R) =
    tryCanceling { block() }
inline fun <R> trySafelyInterrupting(block: () -> R) =
    try { block() } catch (ex: InterruptedException) { throw ex } catch (_: Throwable) {}
inline fun <R> tryInterrupting(block: () -> R) =
    try { block() } catch (ex: Throwable) { throw InterruptedException() }
fun <R> trySafelyInterrupting(step: suspend CoroutineScope.() -> R) =
    try { blockOf(step)() } catch (ex: InterruptedException) { throw InterruptedStepException(step, ex) } catch (_: Throwable) {}
fun <R> tryInterruptingForResult(step: suspend CoroutineScope.() -> R, exit: (Throwable) -> R? = { null }) =
    try { blockOf(step)() } catch (ex: InterruptedException) { throw InterruptedStepException(step, ex) } catch (ex: Throwable) { exit(ex) }
fun <R> tryInterrupting(step: suspend CoroutineScope.() -> R, block: () -> R = blockOf(step)) =
    try { block() } catch (ex: Throwable) { throw InterruptedStepException(step, ex) }
inline fun <R> Context.trySafelyCanceling(block: Context.() -> R) =
    net.consolator.trySafelyCanceling { block() }
inline fun <R> Context.tryCanceling(block: Context.() -> R) =
    net.consolator.tryCanceling { block() }

inline fun <reified R : Any> Any?.asType(): R? =
    if (this is R) this else null
inline fun <reified R : Any> R?.singleton() =
    commitAsyncForResult(R::class.lock(), { this === null }, this, R::class::emptyConstructor) as R
fun <T : Any> KClass<out T>.lock() = objectInstance ?: this
fun <T : Any> KClass<out T>.reconstruct(vararg args: Any?): T = when {
    isCompanion -> objectInstance!!
    args.isEmpty() -> emptyConstructor().call()
    else -> firstConstructor().call(*args)
}
fun <T : Any> KClass<out T>.emptyConstructor() = constructors.first { it.parameters.isEmpty() }
fun <T : Any> KClass<out T>.firstConstructor() = constructors.first()
fun <T : Any> KClass<out T>.lastAnnotatedFile() = annotations.last { it is File } as File
fun <T : Any> KClass<out T>.lastAnnotatedFilename() = lastAnnotatedFile().name
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

fun info(tag: String, msg: String) = _infoLogger(tag, msg)
fun debug(tag: String, msg: String) = _debugLogger(tag, msg)
fun warning(tag: String, msg: String) = _warningLogger(tag, msg)

private typealias LogFunction = (String, String) -> Any?
fun bypassInfoLog() { _infoLogger = _emptyLogger }
fun bypassDebugLog() { _debugLogger = _emptyLogger }
fun bypassWarningLog() { _warningLogger = _emptyLogger }
fun bypassAllLogs() {
    bypassInfoLog()
    bypassDebugLog()
    bypassWarningLog()
}
private var _infoLogger: LogFunction = Log::i
private var _debugLogger: LogFunction = Log::d
private var _warningLogger: LogFunction = Log::w
private val _emptyLogger: LogFunction = { _, _ -> }
private fun LogFunction.isBypassed() = this === _emptyLogger
private fun LogFunction.isNotBypassed() = this !== _emptyLogger
val infoLogIsNotBypassed
    get() = _infoLogger.isNotBypassed()
val debugLogIsNotBypassed
    get() = _debugLogger.isNotBypassed()
val warningLogIsNotBypassed
    get() = _warningLogger.isNotBypassed()

const val START_TIME_KEY = "1"
const val MODE_KEY = "2"
const val ACTION_KEY = "3"