package data.consolator.entity

import androidx.room.*
import data.consolator.*

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
    internal companion object {
        const val CAPABILITIES = "capabilities"
        const val DOWNSTREAM = "downstream"
        const val UPSTREAM = "upstream"
        const val STRENGTH = "strength"
        const val TABLE = "network_capabilities" } }