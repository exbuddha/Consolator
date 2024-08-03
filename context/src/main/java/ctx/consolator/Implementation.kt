package ctx.consolator

import java.util.*

interface UniqueContext { var startTime: Long }

fun now() = Calendar.getInstance().timeInMillis