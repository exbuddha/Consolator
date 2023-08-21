package backgammon.module.application

import backgammon.module.*
import backgammon.module.Scheduler.EventBus.signal
import backgammon.module.BaseApplication.Companion.ACTION_NAV_MAIN_UI

class Migration : Deferral() {
    override fun commit() {
        // listen to db updates
        // preload data
        // reset function pointers
        // repeat until stable
        signal(@JobTreeRoot ACTION_NAV_MAIN_UI)
    }

    var progress: Byte = 0
        get() = (field * 100 / Byte.MAX_VALUE).toByte()
        private set
}