package backgammon.module.application

import backgammon.module.*
import backgammon.module.BaseApplication.Companion.ACTION_NAV_MAIN_UI

class Migration : StepResolver() {
    override fun resolve(vararg id: Any?) {
        // listen to db updates
        // preload data
        // reset function pointers
        // repeat until stable
        Scheduler.EventBus.signal(@JobTreeRoot ACTION_NAV_MAIN_UI)
    }
}