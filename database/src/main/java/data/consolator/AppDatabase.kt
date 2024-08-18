@file:JvmName(JVM_CLASS_NAME)
@file:JvmMultifileClass

package data.consolator

import android.content.*
import androidx.room.*
import ctx.consolator.*
import data.consolator.dao.*
import data.consolator.entity.*
import java.text.*
import java.util.*
import kotlin.reflect.*
import data.consolator.AppDatabase.Companion.DB_VERSION
import data.consolator.AppDatabase.Companion.dateTimeFormat
import data.consolator.AppDatabase.Companion.dbTimeDiff
import kotlin.annotation.AnnotationRetention.SOURCE
import kotlin.annotation.AnnotationTarget.CLASS

var db: AppDatabase? = null
var session: RuntimeSessionEntity? = null

suspend fun buildNewSession(startTime: Long) {
    RuntimeDao {
    session = getSession(
        newSession(startTime)) } }

@Database(version = DB_VERSION, exportSchema = false, entities = [
    RuntimeSessionEntity::class,
])
@File("app.db")
abstract class AppDatabase : RoomDatabase() {
    internal abstract fun runtimeDao(): RuntimeDao

    companion object {
        @JvmStatic var dateTimeFormat: DateFormat? = null
            get() = field ?: SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.US)
                .also { field = it }

        @JvmStatic var dbTimeDiff: Long? = null
            get() = field ?: session?.run {
                dbTime.toLocalTime() - initTime }
                ?.also { field = it }

        internal const val DB_VERSION = 1
        internal const val CURRENT_TIMESTAMP = "CURRENT_TIMESTAMP"
        internal const val ID = "_id"
        internal const val DB_TAG = "DATABASE"
} }

fun <D : RoomDatabase> buildDatabase(cls: KClass<D>, context: Context) =
    with(cls) { buildDatabase(java, context, lastAnnotatedFilename()) }

private fun <D : RoomDatabase> buildDatabase(cls: Class<D>, context: Context, name: String?) =
    Room.databaseBuilder(context, cls, name).build()

fun String.toLocalTime() = dateTimeFormat!!.parse(this)!!.time

private fun Long.toLocalTimestamp() = dateTimeFormat!!.format(Date(this))

private fun String.toAppTime() = toLocalTime() - dbTimeDiff!!

private fun Long.toDbTime() = plus(dbTimeDiff!!)

fun clearObjects() {
    dateTimeFormat = null
    dbTimeDiff = null }

@Retention(SOURCE)
@Target(CLASS)
@Repeatable
internal annotation class File(
    @JvmField val name: String)

internal fun <T : Any> KClass<out T>.annotatedFiles() =
    annotations.filterIsInstance<File>()

internal fun <T : Any> KClass<out T>.lastAnnotatedFile() =
    annotations.last { it is File } as File

internal fun <T : Any> KClass<out T>.lastAnnotatedFilename() =
    lastAnnotatedFile().name