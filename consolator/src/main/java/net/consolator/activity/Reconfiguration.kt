package net.consolator.activity

import net.consolator.ForgetfulStepResolver
import net.consolator.Work
import net.consolator.asType

abstract class Reconfiguration : ForgetfulStepResolver() {
    override fun commit() = (id?.last()?.asType<Work>())?.invoke()
}