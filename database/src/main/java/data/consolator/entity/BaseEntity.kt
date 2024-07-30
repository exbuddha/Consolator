package data.consolator.entity

import androidx.room.*

abstract class BaseEntity(
    @PrimaryKey
    internal open val id: Long,
)