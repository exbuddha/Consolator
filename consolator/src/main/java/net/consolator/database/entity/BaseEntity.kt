package net.consolator.database.entity

import androidx.room.*

abstract class BaseEntity(
    @PrimaryKey
    open val id: Long,
)