package iso.consolator.application

import iso.consolator.*
import iso.consolator.Scheduler.applicationMigrationManager

abstract class MigrationManager : Resolver {
    internal fun expire() = ::applicationMigrationManager.expire()
}