package net.consolator.application

import net.consolator.Resolver
import net.consolator.Scheduler
import net.consolator.Scheduler.EventBus.signal
import net.consolator.JobTreeRoot
import net.consolator.expire
import net.consolator.BaseApplication.Companion.COMMIT_NAV_MAIN_UI

class Migration : Resolver {
    override fun commit(vararg context: Any?) {
        // listen to db updates
        // preload data
        // reset function pointers
        // repeat until stable
        signal(@JobTreeRoot COMMIT_NAV_MAIN_UI)
        Scheduler::applicationMigrationResolver.expire()
    }

    var progress: Byte = 0
        private set
}