package net.consolator.database.entity

import androidx.room.*

@Entity(tableName = ExceptionTypeEntity.TABLE)
data class ExceptionTypeEntity(
    @ColumnInfo(name = TYPE)
    val type: String,

    override val id: Long,
) : BaseEntity(id) {
    companion object {
        const val TYPE = "type"
        const val TABLE = "exception_types" } }