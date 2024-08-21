package iso.consolator.application

import androidx.fragment.app.*
import iso.consolator.*
import iso.consolator.Scheduler.applicationMigrationManager

class MigrationManager : Resolver {
    override fun commit(vararg context: Any?) {
        super.commit(*context)
        when (context.firstOrNull()) {
            Fragment::onViewCreated ->
                ::applicationMigrationManager.expire()
        }
    }

    private companion object
}