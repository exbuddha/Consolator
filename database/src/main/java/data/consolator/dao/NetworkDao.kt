package data.consolator.dao

import androidx.room.*
import data.consolator.*
import data.consolator.entity.*

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
    abstract suspend fun updateNetworkCapabilities(capabilities: String, downstream: Int, upstream: Int, strength: Int, nid: Int, sid: Long = session!!.id)

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

    companion object {
        @JvmStatic suspend operator fun <R> invoke(block: suspend NetworkDao.() -> R) =
            netDb!!.networkDao().block()
} }