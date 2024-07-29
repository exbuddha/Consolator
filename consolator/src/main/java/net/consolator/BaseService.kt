package net.consolator

import android.app.*
import android.content.*

internal open class BaseService : Service(), BaseServiceScope {
    override var startTime = 0L

    override fun onCreate() {
        super.onCreate()
        service = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) =
        intent.invoke(flags, startId,
            super.onStartCommand(intent, flags, startId))
        ?: START_NOT_STICKY

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        service = null
        super.onDestroy()
    }

    override var ref: WeakContext? = null
        get() = field.unique(this).also { field = it }
}