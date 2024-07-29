package net.consolator.database.dao

import androidx.room.*
import net.consolator.logDb

@Dao
internal abstract class LogDao {
    companion object {
        suspend operator fun <R> invoke(block: suspend LogDao.() -> R) =
            logDb!!.logDao().block()
} }