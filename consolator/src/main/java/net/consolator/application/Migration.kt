package net.consolator.application

import net.consolator.*
import net.consolator.Scheduler.EventBus.signal
import net.consolator.BaseApplication.Companion.COMMIT_NAV_MAIN_UI

class Migration : Deferral() {
    override fun commit() {
        // listen to db updates
        // preload data
        // reset function pointers
        // repeat until stable
        signal(@JobTreeRoot COMMIT_NAV_MAIN_UI)
        Scheduler.applicationMigrationResolver = null
    }

    var progress: Byte = 0
        private set
}