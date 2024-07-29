package net.consolator.database.dao

import androidx.room.*
import net.consolator.database.entity.RuntimeSessionEntity
import net.consolator.database.entity.ThreadEntity
import net.consolator.db

@Dao
internal abstract class RuntimeDao {
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

    companion object {
        suspend operator fun <R> invoke(block: suspend RuntimeDao.() -> R) =
            db!!.runtimeDao().block()
} }