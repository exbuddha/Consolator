package net.consolator.database.entity

import androidx.room.*
import net.consolator.BuildConfig
import net.consolator.UniqueContext
import net.consolator.now
import net.consolator.database.AppDatabase.Companion.CURRENT_TIMESTAMP

@Entity(tableName = RuntimeSessionEntity.TABLE)
internal data class RuntimeSessionEntity(
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