package net.consolator.database.entity

import androidx.room.*
import net.consolator.database.AppDatabase.Companion.CURRENT_TIMESTAMP
import net.consolator.session

open class TimeSensitiveSessionEntity(
    @ColumnInfo(name = RuntimeSessionEntity.DB_TIME, defaultValue = CURRENT_TIMESTAMP)
    open var dbTime: String,

    override val id: Long,
    override var sid: Long? = session?.startTime,
) : BaseSessionEntity(id, sid)