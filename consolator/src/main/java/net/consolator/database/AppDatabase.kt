package net.consolator.database

import androidx.room.*
import java.text.*
import java.util.*
import net.consolator.*
import net.consolator.database.entity.*
import net.consolator.database.dao.*
import net.consolator.database.AppDatabase.Companion.DB_VERSION

@Database(version = DB_VERSION, exportSchema = false, entities = [
    RuntimeSessionEntity::class,
])
@File("app.db")
internal abstract class AppDatabase : RoomDatabase() {
    abstract fun runtimeDao(): RuntimeDao
    companion object {
        var dateTimeFormat: DateFormat? = null
            get() = field.require { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }.also { field = it }

        var dbTimeDiff: Long? = null
            get() = field.require { session?.run { dbTime.toLocalTime() - initTime } }.also { field = it }

        fun clearObjects() {
            dateTimeFormat = null
            dbTimeDiff = null }

        const val DB_VERSION = 1
        const val CURRENT_TIMESTAMP = "CURRENT_TIMESTAMP"
        const val ID = "_id"
        const val DB_TAG = "DATABASE" } }