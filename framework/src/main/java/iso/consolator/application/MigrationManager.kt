package iso.consolator.application

import androidx.fragment.app.*
import iso.consolator.*
import iso.consolator.Scheduler.applicationMigrationManager

class MigrationManager : Resolver {
    override fun commit(vararg context: Any?) {
        when (context.firstOrNull()) {
            Fragment::onViewCreated -> {
                context.lastOrNull()?.asType<Work>()?.invoke()
                ::applicationMigrationManager.expire()
        } }
    }

    var progress: Byte = 0
        private set

    private companion object
}