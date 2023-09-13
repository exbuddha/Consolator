package net.consolator.activity

import net.consolator.StepResolver

abstract class Reconfiguration : StepResolver() {
    override fun commit(vararg context: Any?) {}
}