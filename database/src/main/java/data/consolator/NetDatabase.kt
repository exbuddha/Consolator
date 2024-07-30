package data.consolator

import androidx.room.*
import data.consolator.dao.*
import data.consolator.entity.*
import data.consolator.AppDatabase.Companion.DB_VERSION

var netDb: NetworkDatabase? = null

@Database(version = DB_VERSION, exportSchema = false, entities = [
    NetworkCapabilitiesEntity::class,
    NetworkStateEntity::class,
])
@File("net.db")
abstract class NetworkDatabase : RoomDatabase() {
    abstract fun networkDao(): NetworkDao
}