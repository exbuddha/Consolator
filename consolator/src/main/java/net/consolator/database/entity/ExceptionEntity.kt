package net.consolator.database.entity

import androidx.room.*
import net.consolator.database.AppDatabase
import net.consolator.session

@Entity(tableName = ExceptionEntity.TABLE, foreignKeys = [
    ForeignKey(
        entity = ExceptionTypeEntity::class,
        parentColumns = [AppDatabase.ID],
        childColumns = [ExceptionEntity.TYPE],
    ), ForeignKey(
        entity = ThreadEntity::class,
        parentColumns = [AppDatabase.ID],
        childColumns = [ExceptionEntity.THREAD],
    ), ForeignKey(
        entity = ExceptionEntity::class,
        parentColumns = [AppDatabase.ID],
        childColumns = [ExceptionEntity.CAUSE],
    )])
internal data class ExceptionEntity(
    @ColumnInfo(name = TYPE)
    val type: Long,

    @ColumnInfo(name = THREAD)
    val thread: Long,

    @ColumnInfo(name = CAUSE)
    val cause: Long? = null,

    @ColumnInfo(name = MESSAGE)
    val message: String? = null,

    @ColumnInfo(name = UNHANDLED)
    val unhandled: Boolean = false,

    override var dbTime: String,
    override val id: Long,
    override var sid: Long? = session?.startTime,
) : TimeSensitiveSessionEntity(dbTime, id, sid) {
    companion object {
        const val TYPE = "type_id"
        const val UNHANDLED = "unhandled"
        const val THREAD = "thread_id"
        const val MESSAGE = "message"
        const val CAUSE = "cause"
        const val TABLE = "exceptions" } }