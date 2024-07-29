package net.consolator.database.entity

import androidx.room.*

internal abstract class BaseEntity(
    @PrimaryKey
    open val id: Long,
)