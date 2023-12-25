package net.consolator

import android.app.*
import android.content.*
import net.consolator.Scheduler.clock

open class BaseService : Service(), Scheduler.BaseServiceScope {
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
                    if (logDb === null) with(LogDatabase) {
                        io(true, @Tag(STAGE_BUILD) seqStepBuildDatabase(
                            ::logDb, STAGE_BUILD,
                            Context::stageLogDbCreated)) }
                    if (netDb === null) with(NetworkDatabase) {
                        io(true, @Tag(STAGE_BUILD) seqStepBuildDatabase(
                            ::netDb, STAGE_BUILD,
                            { /* update net db records */ },
                            Context::stageNetDbInitialized)) }
                    resume()
                }
                if (info.isOn)
                    info(SVC_TAG, "Clock is detected.")
            }
        return mode!!
    }

    override fun onBind(intent: Intent?): BaseService {
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
}

typealias Sequencer = Scheduler.Sequencer