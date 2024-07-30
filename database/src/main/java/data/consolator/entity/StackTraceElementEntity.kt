package data.consolator.entity

import androidx.room.*
import data.consolator.*

@Entity(tableName = StackTraceElementEntity.TABLE, foreignKeys = [
    ForeignKey(
        entity = ExceptionEntity::class,
        parentColumns = [AppDatabase.ID],
        childColumns = [StackTraceElementEntity.EXCEPTION_ID],
    )])
internal data class StackTraceElementEntity(
    @ColumnInfo(name = EXCEPTION_ID)
    val exception: Long,

    @ColumnInfo(name = ELEMENT)
    val element: StackTraceElement,

    override val id: Long,
) : BaseEntity(id) {
    companion object {
        const val EXCEPTION_ID = "exception_id"
        const val ELEMENT = "element"
        const val TABLE = "stack_trace_elements" } }