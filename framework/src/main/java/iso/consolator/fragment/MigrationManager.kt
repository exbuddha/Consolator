package iso.consolator.fragment

import androidx.fragment.app.*

abstract class MigrationManager : iso.consolator.application.MigrationManager() {
    override fun commit(vararg context: Any?) {
        when (context.firstOrNull()) {
            Fragment::onViewCreated -> {
                super.commit(*context)
                expire() }
    } }
}