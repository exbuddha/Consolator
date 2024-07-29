package net.consolator.database

import androidx.room.*
import net.consolator.File
import net.consolator.database.entity.NetworkCapabilitiesEntity
import net.consolator.database.entity.NetworkStateEntity
import net.consolator.database.dao.NetworkDao
import net.consolator.database.AppDatabase.Companion.DB_VERSION

@Database(version = DB_VERSION, exportSchema = false, entities = [
    NetworkCapabilitiesEntity::class,
    NetworkStateEntity::class,
])
@File("net.db")
internal abstract class NetworkDatabase : RoomDatabase() {
    abstract fun networkDao(): NetworkDao
}