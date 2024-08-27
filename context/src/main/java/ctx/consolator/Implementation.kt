package ctx.consolator

import java.util.*
import kotlin.reflect.*

interface UniqueContext { var startTime: Long }

fun now() = Calendar.getInstance().timeInMillis

fun <R, V : R> KCallable<R>.receive(value: V) = value

const val JVM_CLASS_NAME = "Companion"