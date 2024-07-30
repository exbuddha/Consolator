package data.consolator.entity

import androidx.room.*
import data.consolator.*
import data.consolator.AppDatabase.Companion.CURRENT_TIMESTAMP

open class TimeSensitiveSessionEntity(
    @ColumnInfo(name = RuntimeSessionEntity.DB_TIME, defaultValue = CURRENT_TIMESTAMP)
    internal open var dbTime: String,

    override val id: Long,
    override var sid: Long? = session?.startTime,
) : BaseSessionEntity(id, sid)