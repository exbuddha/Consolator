package data.consolator.entity

import androidx.room.*

@Entity(tableName = ExceptionTypeEntity.TABLE)
internal data class ExceptionTypeEntity(
    @ColumnInfo(name = TYPE)
    val type: String,

    override val id: Long,
) : BaseEntity(id) {
    companion object {
        const val TYPE = "type"
        const val TABLE = "exception_types" } }