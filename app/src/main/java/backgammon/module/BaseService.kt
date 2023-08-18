package backgammon.module

import android.app.*
import android.content.*
import android.os.*
import android.util.*
import kotlin.coroutines.*
import kotlinx.coroutines.*
import java.io.*

open class BaseService : Service(), BaseServiceScope {
    override var startTime = 0L
    override var mode: Int? = null
        get() = field ?: START_NOT_STICKY

    override fun onCreate() {
        super.onCreate()
        service = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mode = super.onStartCommand(intent, flags, startId)
        mode = getModeExtra(intent)
        defer<StartCommandResolver, _>(::onStartCommand) ?:
        work<StartCommandResolver> {
            clockAhead {
                startTime = getStartTimeExtra(intent)
                with(Scheduler) {
                    clock?.startTime = startTime
                    if (netDb === null)
                        sequencer = Scheduler.Sequencer().apply {
                            ioStart {
                                reset()
                                netDb = trySafelyForResult(::buildDatabase)
                            }
                        }
                }
                Log.i(SVC_TAG, "Clock is detected.")
            }
        }
        return mode!!
    }

    override fun onBind(intent: Intent?): BaseServiceScope {
        defer<BindResolver, _>(::onBind) ?:
        work<BindResolver> {
            // ...
        }
        return this
    }

    override fun onDestroy() {
        service = null
        super.onDestroy()
    }

    companion object {
        val SVC_TAG
            get() = if (onMainThread()) "SERVICE" else "CLOCK"
    }

    inner class StartCommandResolver : ForgetfulWorkResolver()
    abstract inner class BindResolver : ForgetfulWorkResolver()
}

interface BaseServiceScope : IBinder, SchedulerScope, UniqueContext {
    fun getStartTimeExtra(intent: Intent?) =
        intent?.getLongExtra(START_TIME_KEY, instance!!.startTime)!!

    var mode: Int?
    fun getModeExtra(intent: Intent?) =
        intent?.getIntExtra(MODE_KEY, mode!!)!!

    override fun getInterfaceDescriptor(): String? {
        return null
    }

    override fun pingBinder(): Boolean {
        return true
    }

    override fun isBinderAlive(): Boolean {
        return false
    }

    override fun queryLocalInterface(descriptor: String): IInterface? {
        return null
    }

    override fun dump(fd: FileDescriptor, args: Array<out String>?) {}

    override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) {}

    override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return true
    }

    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {}

    override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
        return true
    }

    fun clearObjects() {
        mode = null
    }
}