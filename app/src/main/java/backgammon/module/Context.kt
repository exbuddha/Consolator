package backgammon.module

import android.Manifest
import android.content.*
import android.content.pm.*
import android.os.*
import android.util.*
import androidx.core.content.*
import androidx.lifecycle.*
import androidx.room.*
import java.lang.*
import kotlin.annotation.AnnotationRetention.*
import kotlin.annotation.AnnotationTarget.*
import kotlin.reflect.*
import kotlinx.coroutines.*
import backgammon.module.Scheduler.Event
import backgammon.module.Scheduler.EventBus
import backgammon.module.State.Resolved
import backgammon.module.AppDatabase.Companion.File
import backgammon.module.BaseApplication.Companion.ACTION_MIGRATE_APP

var instance: BaseApplication? = null
var service: BaseService? = null
var receiver: BaseReceiver? = null
    get() = commitAsyncForResult(BaseReceiver, { field === null }, field) {
        BaseReceiver()
    }
var db: AppDatabase? = null
var logDb: LogDatabase? = null
var netDb: NetworkDatabase? = null
var session: RuntimeSessionEntity? = null
lateinit var reactToUncaughtExceptionThrown: ExceptionHandler

val foregroundLifecycleOwner: LifecycleOwner
    get() = TODO()
val foregroundContext: Context
    get() = instance!!

fun Context.event(stage: ContextStep) =
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
    reactToUncaughtExceptionThrown[0] = @Tag("uncaught-db") { th, ex ->
        // record in db safely
    }
}

fun Context.stageNetDbInitialized() {
    // update net function pointers
}

fun Context.registerReceiver(filter: IntentFilter) =
    ContextCompat.registerReceiver(this, receiver, filter, null, Scheduler.clock?.handler, 0)
inline fun <reified D : RoomDatabase> Context.buildDatabase() = with(D::class) {
    Room.databaseBuilder(
        this@buildDatabase,
        java,
        (annotations.last { it is File } as File).name
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

fun Context.isNetworkStateAccessPermitted() =
    isPermissionGranted(Manifest.permission.ACCESS_NETWORK_STATE)
fun Context.isInternetAccessPermitted() =
    isNetworkStateAccessPermitted() and isPermissionGranted(Manifest.permission.INTERNET)
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

@Retention(SOURCE)
@Target(CONSTRUCTOR, FUNCTION, PROPERTY, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION)
annotation class Tag(val string: String, val keep: Boolean = true)
private val KCallable<*>.tag
    get() = annotations.find { it is Tag } as? Tag
fun trySafelyForAnnotatedTag(item: KCallable<*>) =
    trySafelyForResult { annotatedTag(item) }
fun annotatedTag(item: KCallable<*>) =
    item.tag!!

private typealias ExceptionHandler = (Thread, Throwable) -> Unit
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

suspend inline fun <T> T.repeatSuspended(predicate: Predicate, block: JobFunction, delayTime: LongFunction = { 0L }, scope: T = this) {
    while (predicate()) {
        block(scope)
        delayOrYield(delayTime())
    }
}
suspend fun delayOrYield(dt: Long = 0L) {
    if (dt > 0) delay(dt) else if (dt == 0L) yield()
}

inline fun <R> trySafely(block: () -> R) =
    try { block() } catch (_: Throwable) {}
inline fun <R> trySafelyForResult(block: () -> R) =
    try { block() } catch (_: Throwable) { null }
inline fun <R> trySafelyCanceling(block: () -> R) =
    try { block() } catch (ex: CancellationException) { throw ex } catch (_: Throwable) {}
inline fun <R> trySafelyCancelingForResult(block: () -> R) =
    try { block() } catch (ex: CancellationException) { throw ex } catch (_: Throwable) { null }
inline fun <R> tryCancelingForResult(block: () -> R, exit: (Throwable) -> R? = { null }) =
    try { block() } catch (ex: CancellationException) { throw ex } catch (ex: Throwable) { exit(ex) }
inline fun <R> tryCanceling(block: () -> R) =
    try { block() } catch (ex: Throwable) { throw CancellationException(null, ex) }
inline fun <R> Context.trySafelyCanceling(block: Context.() -> R) =
    try { block() } catch (ex: CancellationException) { throw ex } catch (_: Throwable) {}
inline fun <R> Context.tryCanceling(block: Context.() -> R) =
    try { block() } catch (ex: Throwable) { throw CancellationException(null, ex) }

inline fun <reified R : Any> Any?.asType(): R? =
    if (this is R) this else null
fun <T : Any> KClass<out T>.reconstruct(vararg args: Any?): T = when {
    isCompanion -> objectInstance!!
    args.isEmpty() -> emptyConstructor().call()
    else -> firstConstructor().call(*args)
}
fun <T : Any> KClass<out T>.emptyConstructor() = constructors.first { it.parameters.isEmpty() }
fun <T : Any> KClass<out T>.firstConstructor() = constructors.first()
fun <T : Any> KMutableProperty<out T?>.reconstruct(type: KClass<out T>, provider: Any? = null, relay: KMutableProperty<out T?>? = this) =
    if (getter.call() === null) {
        setter.call(when {
            type.isInner ->
                (provider.asType<Provider>())?.invoke(type)
            else ->
                type.emptyConstructor().call()
        })
        relay
    } else this
typealias Provider = (KClass<*>) -> Any

fun Byte.asPercentage() =
    (this * 100 / Byte.MAX_VALUE).toByte()

open class BaseImplementationRestriction(
    msg: String = "Base implementation restricted",
    cause: Throwable? = null
) : UnsupportedOperationException(msg, cause) {
    companion object : BaseImplementationRestriction()
}

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