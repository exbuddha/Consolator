package net.consolator.application

import net.consolator.BaseFragment
import net.consolator.EventBus
import net.consolator.JobTreeRoot
import net.consolator.Resolver
import net.consolator.expire
import net.consolator.BaseApplication.Companion.COMMIT_NAV_MAIN_UI
import net.consolator.Scheduler.applicationMigrationManager

class MigrationManager : Resolver {
    override fun commit(vararg context: Any?) {
        when (context.firstOrNull()) {
            BaseFragment::onViewCreated -> {
                // listen to db updates
                // preload data
                // reset function pointers
                // repeat until stable
                EventBus.commit(@JobTreeRoot COMMIT_NAV_MAIN_UI)
                ::applicationMigrationManager.expire()
        } }
    }

    var progress: Byte = 0
        private set

    private companion object
}