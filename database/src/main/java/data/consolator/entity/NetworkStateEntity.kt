package data.consolator.entity

import androidx.room.*
import data.consolator.*

@Entity(tableName = NetworkStateEntity.TABLE)
data class NetworkStateEntity(
    @ColumnInfo(name = IS_CONNECTED)
    @JvmField var isConnected: Boolean,

    @ColumnInfo(name = HAS_INTERNET)
    @JvmField var hasInternet: Boolean,

    @ColumnInfo(name = HAS_WIFI)
    @JvmField var hasWifi: Boolean,

    @ColumnInfo(name = HAS_MOBILE)
    @JvmField var hasMobile: Boolean,

    override var dbTime: String,
    override val id: Long,
    override var sid: Long? = session?.startTime,
) : TimeSensitiveSessionEntity(dbTime, id, sid) {
    internal companion object {
        const val IS_CONNECTED = "is_connected"
        const val HAS_INTERNET = "has_internet"
        const val HAS_WIFI = "has_wifi"
        const val HAS_MOBILE = "has_mobile"
        const val TABLE = "network_states" } }