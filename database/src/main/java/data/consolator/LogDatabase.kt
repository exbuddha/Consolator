@file:JvmName(JVM_CLASS_NAME)
@file:JvmMultifileClass

package data.consolator

import androidx.room.*
import data.consolator.dao.*
import data.consolator.entity.*
import ctx.consolator.JVM_CLASS_NAME

var logDb: LogDatabase? = null
    set(value) {
        field = ::logDb.receive(value) }

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