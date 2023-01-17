package backgammon.module

import android.Manifest
import android.content.*
import android.content.pm.*
import androidx.core.content.*
import androidx.lifecycle.*
import kotlin.reflect.*
import kotlinx.coroutines.*
import java.lang.*
import backgammon.module.BaseApplication.Companion.ACTION_MIGRATE_APP
import backgammon.module.Scheduler.Event

var instance: BaseApplication? = null
var service: BaseService? = null
var db: AppDatabase? = null
var netDb: NetworkDatabase? = null
var session: RuntimeSessionEntity? = null
lateinit var reactToUncaughtExceptionThrown: (Thread, Throwable) -> Unit

val foregroundLifecycleOwner: LifecycleOwner
    get() = TODO()
val foregroundContext: Context
    get() = instance!!

@Event(ACTION_MIGRATE_APP)
fun Context.signalDbCreated() {
    reactToUncaughtExceptionThrown = { thread, throwable ->
        // record in db
    }
}

@Event(ACTION_MIGRATE_APP)
fun Context.signalSessionCreated() {
    // update db records
}

fun Context.intendFor(cls: Class<*>) = Intent(this, cls)
fun Context.intendFor(cls: KClass<*>) = intendFor(cls.java)
fun Context.isPermissionGranted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
fun Context.isNetworkStateAccessPermitted() =
    isPermissionGranted(Manifest.permission.ACCESS_NETWORK_STATE)
fun Context.isInternetAccessPermitted() =
    isNetworkStateAccessPermitted() and isPermissionGranted(Manifest.permission.INTERNET)

fun now() = java.util.Calendar.getInstance().timeInMillis
fun getDelayTime(interval: Long, last: Long) =
    last + interval - now()
fun isTimeIntervalExceeded(interval: Long, last: Long) =
    (now() - last) >= interval || last == 0L

interface UniqueContext { var startTime: Long }
typealias ContextStep = suspend Context.() -> Unit

typealias AnyArray = Array<out Any?>
typealias AnyFunction = () -> Any?
typealias AnyToAnyFunction = (Any?) -> Any?
typealias LongFunction = () -> Long
typealias Predicate = () -> Boolean
typealias AnyPredicate = (Any?) -> Boolean
typealias IntPredicate = (Int) -> Boolean
typealias ThrowablePredicate = (Throwable?) -> Boolean

const val START_TIME_KEY = "1"
const val MODE_KEY = "2"
const val ACTION_KEY = "3"

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
inline fun <R> tryCanceling(block: () -> R, exit: (Throwable) -> Any?) =
    try { block() } catch (ex: CancellationException) { throw ex } catch (ex: Throwable) { exit(ex) }
inline fun <R> tryCancelingForResult(block: () -> R, exit: (Throwable) -> R?) =
    try { block() } catch (ex: CancellationException) { throw ex } catch (ex: Throwable) { exit(ex) }

inline fun <reified R : Any> Any?.asType(): R? =
    if (this is R) this else null
fun <T : Any> KClass<out T>.reconstruct(vararg args: Any?): T =
    if (isCompanion) objectInstance!!
    else firstConstructor().call(*args)
fun <T : Any> KClass<out T>.firstConstructor() = constructors.first()
fun <T : Any> KMutableProperty<out T?>.reconstruct(type: KClass<out T>, relay: KMutableProperty<out T?>? = this) =
    if (getter.call() === null) {
        setter.call(type.firstConstructor().call())
        relay
    } else this

open class BaseImplementationRestriction(
    msg: String = "Base implementation restricted",
    cause: Throwable? = null
) : UnsupportedOperationException(msg, cause) {
    companion object : BaseImplementationRestriction()
}