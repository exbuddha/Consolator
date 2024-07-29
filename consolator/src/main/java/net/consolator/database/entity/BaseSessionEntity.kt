package net.consolator.database.entity

import net.consolator.session

internal abstract class BaseSessionEntity(
    override val id: Long,
    open val sid: Long? = session?.startTime,
) : BaseEntity(id)