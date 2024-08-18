package data.consolator.entity

import androidx.room.*
import ctx.consolator.*
import data.consolator.AppDatabase.Companion.CURRENT_TIMESTAMP

@Entity(tableName = RuntimeSessionEntity.TABLE)
data class RuntimeSessionEntity(
    @ColumnInfo(name = CTX_TIME)
    override var startTime: Long,

    @ColumnInfo(name = INIT_TIME)
    @JvmField internal var initTime: Long = now(),

    @ColumnInfo(name = DB_TIME, defaultValue = CURRENT_TIMESTAMP)
    @JvmField internal var dbTime: String,

    @ColumnInfo(name = APP_ID)
    @JvmField val appId: String?,

    @ColumnInfo(name = BUILD_TYPE)
    @JvmField val buildType: String?,

    @ColumnInfo(name = BUILD_VERSION)
    @JvmField val buildVersion: String?,

    override val id: Long,
) : BaseEntity(id), UniqueContext {
    internal companion object {
        const val CTX_TIME = "ctx_time"
        const val INIT_TIME = "init_time"
        const val DB_TIME = "db_time"
        const val APP_ID = "app_id"
        const val BUILD_TYPE = "build_type"
        const val BUILD_VERSION = "build_ver"
        const val TABLE = "sessions" } }