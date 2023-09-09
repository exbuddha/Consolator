package net.consolator

import android.app.*
import android.content.*
import android.os.*
import android.util.*
import java.io.*
import kotlin.coroutines.*
import kotlin.reflect.*
import kotlinx.coroutines.*
import net.consolator.Scheduler.Sequencer

open class BaseService : Service(), BaseServiceScope, Provider {
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
        if (hasMoreInitWork)
            defer<StartCommandResolver, _>(::onStartCommand) ?:
            work<StartCommandResolver> {
                clockAhead {
                    startTime = getStartTimeExtra(intent)
                    Sequencer {
                        if (logDb === null)
                            io(true) @Tag("log-db.build") {
                                logDb = resetOnError(::buildDatabase)
                                change(Context::stageLogDbCreated)
                            }
                        if (netDb === null)
                            io(true) @Tag("net-db.build") {
                                netDb = resetOnError(::buildDatabase)
                                // update net db records
                                change(Context::stageNetDbInitialized)
                            }
                        resume()
                    }
                    if (infoLogIsNotBypassed)
                        info(SVC_TAG, "Clock is detected.")
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

    override fun invoke(type: KClass<*>) = when (type) {
        StartCommandResolver::class ->
            StartCommandResolver()
        else ->
            throw BaseImplementationRestriction
    }
}

interface BaseServiceScope : IBinder, SchedulerScope, UniqueContext {
    fun getStartTimeExtra(intent: Intent?) =
        intent?.getLongExtra(START_TIME_KEY, instance!!.startTime)!!

    var mode: Int?
    fun getModeExtra(intent: Intent?) =
        intent?.getIntExtra(MODE_KEY, mode!!)!!

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