package net.consolator.database

import androidx.room.*
import net.consolator.File
import net.consolator.database.entity.ExceptionEntity
import net.consolator.database.entity.ExceptionTypeEntity
import net.consolator.database.entity.StackTraceElementEntity
import net.consolator.database.entity.ThreadEntity
import net.consolator.database.dao.LogDao
import net.consolator.database.AppDatabase.Companion.DB_VERSION

@Database(version = DB_VERSION, exportSchema = false, entities = [
    ThreadEntity::class,
    ExceptionEntity::class,
    ExceptionTypeEntity::class,
    StackTraceElementEntity::class,
])
@File("log.db")
internal abstract class LogDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao
}