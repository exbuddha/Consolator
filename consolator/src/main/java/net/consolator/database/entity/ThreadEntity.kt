package net.consolator.database.entity

import androidx.room.*
import net.consolator.session

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