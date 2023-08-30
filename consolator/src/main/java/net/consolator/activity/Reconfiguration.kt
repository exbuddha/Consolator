package net.consolator.activity

import net.consolator.*

abstract class Reconfiguration : ForgetfulStepResolver() {
    override fun commit() = (id?.last()?.asType<Work>())?.invoke()
}