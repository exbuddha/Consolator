package backgammon.module.activity

import backgammon.module.*

abstract class Reconfiguration : ForgetfulStepResolver() {
    override fun commit() = (id?.last()?.asType<Work>())?.invoke()
}