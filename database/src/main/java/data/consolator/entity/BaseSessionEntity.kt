package data.consolator.entity

import data.consolator.*

abstract class BaseSessionEntity(
    override val id: Long,
    internal open val sid: Long? = session?.id,
) : BaseEntity(id)