package data.consolator

import androidx.room.*
import data.consolator.dao.*
import data.consolator.entity.*
import data.consolator.AppDatabase.Companion.DB_VERSION

var logDb: LogDatabase? = null

@Database(version = DB_VERSION, exportSchema = false, entities = [
    ThreadEntity::class,
    ExceptionEntity::class,
    ExceptionTypeEntity::class,
    StackTraceElementEntity::class,
])
@File("log.db")
abstract class LogDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao
}