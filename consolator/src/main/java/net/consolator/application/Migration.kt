package net.consolator.application

import net.consolator.JobTreeRoot
import net.consolator.Resolver
import net.consolator.Scheduler.EventBus.signal
import net.consolator.expire
import net.consolator.BaseApplication.Companion.ACTION_MIGRATE_APP
import net.consolator.BaseApplication.Companion.COMMIT_NAV_MAIN_UI
import net.consolator.Scheduler.applicationMigrationResolver

class Migration : Resolver {
    override fun commit(vararg context: Any?) {
        when (context.firstOrNull()) {
            ACTION_MIGRATE_APP -> {
                // listen to db updates
                // preload data
                // reset function pointers
                // repeat until stable
                signal(@JobTreeRoot COMMIT_NAV_MAIN_UI)
                ::applicationMigrationResolver.expire()
            }
        }
    }

    var progress: Byte = 0
        private set

    private companion object
}