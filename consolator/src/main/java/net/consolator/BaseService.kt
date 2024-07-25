package net.consolator

import android.app.*
import android.content.*

open class BaseService : Service(), BaseServiceScope {
    override var startTime = 0L
    override var mode: Int? = null

    override fun onCreate() {
        super.onCreate()
        service = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mode = super.onStartCommand(intent, flags, startId)
        invoke(intent)
        return mode ?: START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        service = null
        super.onDestroy()
    }

    override var ref: WeakContext? = null
        get() = field.unique(this).also { field = it }
}