package data.consolator.entity

import androidx.room.*
import data.consolator.*

open class NetworkEntity(
    @ColumnInfo(name = NETWORK_ID)
    open val nid: Int,

    override var dbTime: String,
    override val id: Long,
    override var sid: Long? = session?.startTime,
) : TimeSensitiveSessionEntity(dbTime, id, sid) {
    internal companion object {
        const val NETWORK_ID = "nid" } }