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
import data.consolator.AppDatabase.Companion.dbTimeDiff
import kotlin.annotation.AnnotationRetention.SOURCE
import kotlin.annotation.AnnotationTarget.CLASS

internal const val DB_VERSION = 1

var db: AppDatabase? = null
    set(value) {
        field = ::db.receive(value) }

var session: RuntimeSessionEntity? = null
    set(value) {
        field = ::session.receive(value) }

@Database(version = DB_VERSION, exportSchema = false, entities = [
    RuntimeSessionEntity::class,
])
@File("app.db")
abstract class AppDatabase : RoomDatabase() {
    internal abstract fun runtimeDao(): RuntimeDao

    companion object {
        @JvmStatic var dbTimeDiff: Long? = null
            get() = field ?: session?.run {
                dbTime.toLocalTime()!! - initTime }
                ?.also { field = it }

        internal const val CURRENT_TIMESTAMP = "CURRENT_TIMESTAMP"
        internal const val ID = "_id"
        internal const val DB_TAG = "DATABASE"
} }

suspend fun buildNewSession(startTime: Long) {
    RuntimeDao {
    session = getSession(
        newSession(startTime)) } }

fun <D : RoomDatabase> buildDatabase(cls: KClass<D>, context: Context) =
    with(cls) { buildDatabase(java, context, lastAnnotatedFilename()) }

private fun <D : RoomDatabase> buildDatabase(cls: Class<D>, context: Context, name: String?) =
    Room.databaseBuilder(context, cls, name).build()

var dateTimeFormat: DateFormat? = null
    get() = field ?: SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss", Locale.US)
        .also { field = it }

fun String.toLocalTime() = dateTimeFormat!!.parse(this)?.time

private fun Long.toLocalTimestamp() = dateTimeFormat!!.format(Date(this))

private fun String.toAppTime() = toLocalTime()?.run { dbTimeDiff?.let { minus(it) } }

private fun Long.toDbTime() = dbTimeDiff?.let { plus(it) }

internal fun <R> KCallable<R>.receive(value: R) = value

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