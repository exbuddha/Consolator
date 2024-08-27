@file:JvmName(JVM_CLASS_NAME)
@file:JvmMultifileClass

package ctx.consolator

import java.util.*
import kotlin.reflect.*

interface UniqueContext { var startTime: Long }

fun now() = Calendar.getInstance().timeInMillis

fun <R, V : R> KCallable<R>.receive(value: V) = value

fun Byte.toPercentage() =
    (this * 100 / Byte.MAX_VALUE).toByte()

const val JVM_CLASS_NAME = "Companion"