package net.consolator

import androidx.room.*
import java.text.*
import java.util.*
import net.consolator.AppDatabase.Companion.dateTimeFormat
import net.consolator.AppDatabase.Companion.dbTimeDiff
import net.consolator.AppDatabase.Companion.CURRENT_TIMESTAMP
import net.consolator.AppDatabase.Companion.DB_VERSION

@Database(version = DB_VERSION, exportSchema = false, entities = [
    RuntimeSessionEntity::class,
])
@File("app.db")
abstract class AppDatabase : RoomDatabase() {
    abstract fun runtimeDao(): RuntimeDao
    companion object {
        var dateTimeFormat: DateFormat? = null
            get() = field.require { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }.also { field = it }

        var dbTimeDiff: Long? = null
            get() = field.require { session?.run { dbTime.toLocalTime() - initTime } }.also { field = it }

        const val DB_VERSION = 1
        const val CURRENT_TIMESTAMP = "CURRENT_TIMESTAMP"
        const val ID = "_id"
        const val DB_TAG = "DATABASE" } }

abstract class BaseEntity(
    @PrimaryKey
    open val id: Long,
)

@Entity(tableName = RuntimeSessionEntity.TABLE)
data class RuntimeSessionEntity(
    @ColumnInfo(name = CTX_TIME)
    override var startTime: Long,

    @ColumnInfo(name = INIT_TIME)
    var initTime: Long = now(),

    @ColumnInfo(name = DB_TIME, defaultValue = CURRENT_TIMESTAMP)
    var dbTime: String,

    @ColumnInfo(name = APP_ID, defaultValue = BuildConfig.APPLICATION_ID)
    val appId: String?,

    @ColumnInfo(name = BUILD_TYPE, defaultValue = BuildConfig.BUILD_TYPE)
    val buildType: String?,

    @ColumnInfo(name = BUILD_VERSION, defaultValue = BuildConfig.VERSION_NAME)
    val buildVersion: String?,

    override val id: Long,
) : BaseEntity(id), UniqueContext {
    companion object {
        const val CTX_TIME = "ctx_time"
        const val INIT_TIME = "init_time"
        const val DB_TIME = "db_time"
        const val APP_ID = "app_id"
        const val BUILD_TYPE = "build_type"
        const val BUILD_VERSION = "build_ver"
        const val TABLE = "sessions" } }

abstract class BaseSessionEntity(
    override val id: Long,
    open val sid: Long? = session?.startTime,
) : BaseEntity(id)

open class TimeSensitiveSessionEntity(
    @ColumnInfo(name = RuntimeSessionEntity.DB_TIME, defaultValue = CURRENT_TIMESTAMP)
    open var dbTime: String,

    override val id: Long,
    override var sid: Long? = session?.startTime,
) : BaseSessionEntity(id, sid)

@Dao
abstract class RuntimeDao {
    @Query("INSERT INTO ${RuntimeSessionEntity.TABLE}(${RuntimeSessionEntity.CTX_TIME},${ThreadEntity.RUNTIME_ID}) VALUES (:ctxTime)")
    abstract suspend fun newSession(ctxTime: Long): Long

    @Query("SELECT * FROM ${RuntimeSessionEntity.TABLE} WHERE id == :id")
    abstract suspend fun getSession(id: Long): RuntimeSessionEntity

    @Query("SELECT * FROM ${RuntimeSessionEntity.TABLE} ORDER BY id DESC LIMIT 1")
    abstract suspend fun getFirstSession(): RuntimeSessionEntity

    @Query("DELETE FROM ${RuntimeSessionEntity.TABLE} WHERE id NOT IN (SELECT id FROM ${RuntimeSessionEntity.TABLE} ORDER BY id DESC LIMIT :n)")
    abstract suspend fun truncateSessions(n: Int = 1)

    @Query("DELETE FROM ${RuntimeSessionEntity.TABLE}")
    abstract suspend fun dropSessions()
}

@Database(version = DB_VERSION, exportSchema = false, entities = [
    ThreadEntity::class,
    ExceptionEntity::class,
    ExceptionTypeEntity::class,
    StackTraceElementEntity::class,
])
@File("log.db")
abstract class LogDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao }

@Entity(tableName = ThreadEntity.TABLE)
data class ThreadEntity(
    @ColumnInfo(name = RUNTIME_ID)
    val rid: Long,

    @ColumnInfo(name = MAIN)
    val main: Boolean,

    override var dbTime: String,
    override val id: Long,
    override var sid: Long? = session?.startTime,
) : TimeSensitiveSessionEntity(dbTime, id, sid) {
    companion object {
        const val RUNTIME_ID = "rid"
        const val MAIN = "main"
        const val TABLE = "threads" } }

@Entity(tableName = ExceptionEntity.TABLE, foreignKeys = [
    ForeignKey(
        entity = ExceptionTypeEntity::class,
        parentColumns = [AppDatabase.ID],
        childColumns = [ExceptionEntity.TYPE],
    ), ForeignKey(
        entity = ThreadEntity::class,
        parentColumns = [AppDatabase.ID],
        childColumns = [ExceptionEntity.THREAD],
    ), ForeignKey(
        entity = ExceptionEntity::class,
        parentColumns = [AppDatabase.ID],
        childColumns = [ExceptionEntity.CAUSE],
    )])
data class ExceptionEntity(
    @ColumnInfo(name = TYPE)
    val type: Long,

    @ColumnInfo(name = THREAD)
    val thread: Long,

    @ColumnInfo(name = CAUSE)
    val cause: Long? = null,

    @ColumnInfo(name = MESSAGE)
    val message: String? = null,

    @ColumnInfo(name = UNHANDLED)
    val unhandled: Boolean = false,

    override var dbTime: String,
    override val id: Long,
    override var sid: Long? = session?.startTime,
) : TimeSensitiveSessionEntity(dbTime, id, sid) {
    companion object {
        const val TYPE = "type_id"
        const val UNHANDLED = "unhandled"
        const val THREAD = "thread_id"
        const val MESSAGE = "message"
        const val CAUSE = "cause"
        const val TABLE = "exceptions" } }

@Entity(tableName = StackTraceElementEntity.TABLE, foreignKeys = [
    ForeignKey(
        entity = ExceptionEntity::class,
        parentColumns = [AppDatabase.ID],
        childColumns = [StackTraceElementEntity.EXCEPTION_ID],
    )])
data class StackTraceElementEntity(
    @ColumnInfo(name = EXCEPTION_ID)
    val exception: Long,

    @ColumnInfo(name = ELEMENT)
    val element: StackTraceElement,

    override val id: Long,
) : BaseEntity(id) {
    companion object {
        const val EXCEPTION_ID = "exception_id"
        const val ELEMENT = "element"
        const val TABLE = "stack_trace_elements" } }

@Entity(tableName = ExceptionTypeEntity.TABLE)
data class ExceptionTypeEntity(
    @ColumnInfo(name = TYPE)
    val type: String,

    override val id: Long,
) : BaseEntity(id) {
    companion object {
        const val TYPE = "type"
        const val TABLE = "exception_types" } }

@Dao
abstract class LogDao

@Database(version = DB_VERSION, exportSchema = false, entities = [
    NetworkCapabilitiesEntity::class,
    NetworkStateEntity::class,
])
@File("net.db")
abstract class NetworkDatabase : RoomDatabase() {
    abstract fun networkDao(): NetworkDao }

open class NetworkEntity(
    @ColumnInfo(name = NETWORK_ID)
    open val nid: Int,

    override var dbTime: String,
    override val id: Long,
    override var sid: Long? = session?.startTime,
) : TimeSensitiveSessionEntity(dbTime, id, sid) {
    companion object {
        const val NETWORK_ID = "nid" } }

@Entity(tableName = NetworkStateEntity.TABLE)
data class NetworkStateEntity(
    @ColumnInfo(name = IS_CONNECTED)
    var isConnected: Boolean,

    @ColumnInfo(name = HAS_INTERNET)
    var hasInternet: Boolean,

    @ColumnInfo(name = HAS_WIFI)
    var hasWifi: Boolean,

    @ColumnInfo(name = HAS_MOBILE)
    var hasMobile: Boolean,

    override var dbTime: String,
    override val id: Long,
    override var sid: Long? = session?.startTime,
) : TimeSensitiveSessionEntity(dbTime, id, sid) {
    companion object {
        const val IS_CONNECTED = "is_connected"
        const val HAS_INTERNET = "has_internet"
        const val HAS_WIFI = "has_wifi"
        const val HAS_MOBILE = "has_mobile"
        const val TABLE = "network_states" } }

@Entity(tableName = NetworkCapabilitiesEntity.TABLE)
data class NetworkCapabilitiesEntity(
    override val nid: Int,

    @ColumnInfo(name = CAPABILITIES)
    var capabilities: String,

    @ColumnInfo(name = DOWNSTREAM)
    var downstream: Int,

    @ColumnInfo(name = UPSTREAM)
    var upstream: Int,

    @ColumnInfo(name = STRENGTH)
    var strength: Int,

    override var dbTime: String,
    override val id: Long,
    override var sid: Long? = session?.startTime,
) : NetworkEntity(nid, dbTime, id, sid) {
    companion object {
        const val CAPABILITIES = "capabilities"
        const val DOWNSTREAM = "downstream"
        const val UPSTREAM = "upstream"
        const val STRENGTH = "strength"
        const val TABLE = "network_capabilities" } }

@Dao
abstract class NetworkDao {
    @Query("INSERT INTO ${NetworkStateEntity.TABLE}(${NetworkStateEntity.IS_CONNECTED},${NetworkStateEntity.HAS_INTERNET},${NetworkStateEntity.HAS_MOBILE},${NetworkStateEntity.HAS_WIFI},sid) VALUES (:isConnected,:hasInternet,:hasMobile,:hasWifi,:sid)")
    abstract suspend fun updateNetworkState(isConnected: Boolean, hasInternet: Boolean, hasMobile: Boolean, hasWifi: Boolean, sid: Long = session!!.id)

    @Update(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun updateNetworkState(networkStateEntity: NetworkStateEntity)

    @Query("SELECT * FROM ${NetworkStateEntity.TABLE} ORDER BY id DESC LIMIT 1")
    abstract suspend fun getNetworkState(): NetworkStateEntity?

    @Query("SELECT * FROM ${NetworkStateEntity.TABLE} ORDER BY id DESC LIMIT 1 OFFSET :n")
    abstract suspend fun getPrevNetworkState(n: Int = 1): NetworkStateEntity?

    @Query("SELECT * FROM ${NetworkStateEntity.TABLE} ORDER BY id DESC LIMIT :n")
    abstract suspend fun getLastNetworkStates(n: Int): List<NetworkStateEntity>

    @Query("DELETE FROM ${NetworkStateEntity.TABLE} WHERE id NOT IN (SELECT id FROM ${NetworkStateEntity.TABLE} ORDER BY id LIMIT 1)")
    abstract suspend fun dequeueNetworkState()

    @Query("DELETE FROM ${NetworkStateEntity.TABLE} WHERE id NOT IN (SELECT id FROM ${NetworkStateEntity.TABLE} ORDER BY id DESC LIMIT :n)")
    abstract suspend fun truncateNetworkStates(n: Int = 30)

    @Query("DELETE FROM ${NetworkStateEntity.TABLE} WHERE sid <> :sid")
    abstract suspend fun cleanNetworkStates(sid: Long = session!!.id)

    @Query("DELETE FROM ${NetworkStateEntity.TABLE}")
    abstract suspend fun dropNetworkStates()

    @Query("INSERT INTO ${NetworkCapabilitiesEntity.TABLE}(${NetworkEntity.NETWORK_ID},${NetworkCapabilitiesEntity.CAPABILITIES},${NetworkCapabilitiesEntity.DOWNSTREAM},${NetworkCapabilitiesEntity.UPSTREAM},${NetworkCapabilitiesEntity.STRENGTH},sid) VALUES (:nid,:capabilities,:downstream,:upstream,:strength,:sid)")
    abstract suspend fun updateNetworkCapabilities(capabilities: String, downstream: Int, upstream: Int, strength: Int, nid: Int = network.hashCode(), sid: Long = session!!.id)

    @Update(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun updateNetworkCapabilities(networkCapabilitiesEntity: NetworkCapabilitiesEntity)

    @Query("SELECT * FROM ${NetworkCapabilitiesEntity.TABLE} ORDER BY id DESC LIMIT 1")
    abstract suspend fun getNetworkCapabilities(): NetworkCapabilitiesEntity?

    @Query("SELECT * FROM ${NetworkCapabilitiesEntity.TABLE} ORDER BY id DESC LIMIT 1 OFFSET :n")
    abstract suspend fun getPrevNetworkCapabilities(n: Int = 1): NetworkCapabilitiesEntity?

    @Query("SELECT * FROM ${NetworkCapabilitiesEntity.TABLE} ORDER BY id DESC LIMIT :n")
    abstract suspend fun getLastNetworkCapabilities(n: Int): List<NetworkCapabilitiesEntity>

    @Query("DELETE FROM ${NetworkCapabilitiesEntity.TABLE} WHERE id NOT IN (SELECT id FROM ${NetworkCapabilitiesEntity.TABLE} ORDER BY id LIMIT 1)")
    abstract suspend fun dequeueNetworkCapabilities()

    @Query("DELETE FROM ${NetworkCapabilitiesEntity.TABLE} WHERE id NOT IN (SELECT id FROM ${NetworkCapabilitiesEntity.TABLE} ORDER BY id DESC LIMIT :n)")
    abstract suspend fun truncateNetworkCapabilities(n: Int = 30)

    @Query("DELETE FROM ${NetworkCapabilitiesEntity.TABLE} WHERE sid <> :sid")
    abstract suspend fun cleanNetworkCapabilities(sid: Long = session!!.id)

    @Query("DELETE FROM ${NetworkCapabilitiesEntity.TABLE}")
    abstract suspend fun dropNetworkCapabilities()
}

suspend fun <R> runtimeDao(block: suspend RuntimeDao.() -> R) = db!!.runtimeDao().block()
suspend fun <R> logDao(block: suspend LogDao.() -> R) = logDb!!.logDao().block()
suspend fun <R> networkDao(block: suspend NetworkDao.() -> R) = netDb!!.networkDao().block()

private fun String.toLocalTime() = dateTimeFormat!!.parse(this)!!.time

private fun Long.toLocalTimestamp() = dateTimeFormat!!.format(Date(this))

private fun String.toAppTime() = toLocalTime() - dbTimeDiff!!

private fun Long.toDbTime() = plus(dbTimeDiff!!)

private fun clearObjects() {
    dateTimeFormat = null
    dbTimeDiff = null }
fun clearAppDbObjects() {
    db = null }
fun clearLogDbObjects() {
    logDb = null }
fun clearNetDbObjects() {
    netDb = null }
fun clearAllDbObjects() {
    clearAppDbObjects()
    clearLogDbObjects()
    clearNetDbObjects()
    clearObjects() }
fun clearSessionObjects() {
    session = null }