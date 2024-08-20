package data.consolator.entity

import androidx.room.*
import data.consolator.*

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
    @JvmField val type: Long,

    @ColumnInfo(name = THREAD)
    @JvmField val thread: Long,

    @ColumnInfo(name = CAUSE)
    @JvmField val cause: Long? = null,

    @ColumnInfo(name = MESSAGE)
    @JvmField val message: String? = null,

    @ColumnInfo(name = UNHANDLED)
    @JvmField val unhandled: Boolean = false,

    override var dbTime: String,
    override val id: Long,
    override var sid: Long? = session?.id,
) : TimeSensitiveSessionEntity(dbTime, id, sid) {
    companion object {
        const val TYPE = "type_id"
        const val UNHANDLED = "unhandled"
        const val THREAD = "thread_id"
        const val MESSAGE = "message"
        const val CAUSE = "cause"
        const val TABLE = "exceptions" } }