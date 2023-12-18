package net.consolator

import android.app.*
import android.content.*
import android.os.*
import java.io.*
import net.consolator.Scheduler.Sequencer
import net.consolator.Scheduler.clock

open class BaseService : Service(), BaseServiceScope {
    override val ref: WeakContext? = null
        get() = field.unique(this)
    override var startTime = 0L
    override var mode: Int? = null

    override fun onCreate() {
        super.onCreate()
        service = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        clock?.start()
        mode = super.onStartCommand(intent, flags, startId)
        mode = getModeExtra(intent)
        if (hasMoreInitWork)
            service @Tag("start") {
                startTime = getStartTimeExtra(intent)
                Sequencer {
                    if (logDb === null)
                        io(true) @Tag("log-db.build") {
                            commitAsyncBlocking(LogDatabase::class.lock(), { logDb === null }, {
                                logDb = resetOnError(::buildDatabase)
                                change(Context::stageLogDbCreated)
                            }, SequencerScope::emitReset)
                        }
                    if (netDb === null)
                        io(true) @Tag("net-db.build") {
                            commitAsyncBlocking(NetworkDatabase::class.lock(), { netDb === null }, {
                                netDb = resetOnError(::buildDatabase)
                                // update net db records
                                change(Context::stageNetDbInitialized)
                            }, SequencerScope::emitReset)
                        }
                    resume()
                }
                if (info.isOn)
                    info(SVC_TAG, "Clock is detected.")
            }
        return mode!!
    }

    override fun onBind(intent: Intent?): BaseServiceScope {
        // ...
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

    override fun commit(step: CoroutineStep) = clockAhead(step::invoke)
}

interface BaseServiceScope : IBinder, SchedulerScope, SystemContext, UniqueContext {
    fun getStartTimeExtra(intent: Intent?) =
        intent?.getLongExtra(START_TIME_KEY, instance!!.startTime)!!

    var mode: Int?
    fun getModeExtra(intent: Intent?) =
        intent?.getIntExtra(MODE_KEY, mode ?: Service.START_NOT_STICKY)!!

    val hasMoreInitWork
        get() = logDb === null || netDb === null
    val hasNoMoreInitWork
        get() = logDb !== null && netDb !== null

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