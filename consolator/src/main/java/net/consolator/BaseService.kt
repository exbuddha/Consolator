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
        invoke(intent)
        return mode!!
    }

    override fun onBind(intent: Intent?) = invoke(intent)

    override fun onDestroy() {
        service = null
        super.onDestroy()
    }
}

typealias Sequencer = Scheduler.Sequencer